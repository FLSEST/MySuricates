package com.automation.service.assistant.fetch;

import com.automation.entity.BlockDefinition;
import com.automation.repository.WorkflowRepository;
import com.automation.service.assistant.format.AssistantFormatUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a one-glance summary of a block's config (prompt/command/condition/…)
 * per block type, and the numbered block list around it ({@link #appendBlockList}). Shared by
 * {@link WorkflowFetchHandler} and {@link PoolFetchHandler} so stored workflows and pool templates
 * read identically.
 *
 * <p>It shows the keys the executors read ({@link #SHOWN}); {@code BlockSpecConfigKeysGateTest}
 * checks each against the executor source. It used to read {@code prompt}, {@code true_blocks},
 * {@code max_attempts} and others no executor has, so the assistant saw neither prompts nor goals,
 * and every conditional had 0/0 branches (TODO e6ea63bc). A type without an entry shows all its
 * non-blank keys.
 */
@Component
@RequiredArgsConstructor
public class BlockConfigSummaryFormatter {

    /** A shown key: its value cut to {@code maxChars}, or — with {@code maxChars} 0 — the size of its list. */
    private record Shown(String key, int maxChars) {
    }

    private static final String WORKFLOW_ID = "workflow_id";
    private static final int SHORT = 80;
    private static final int LONG = 200;

    /** What the summary shows per block type, in this order. */
    private static final Map<String, List<Shown>> SHOWN = Map.ofEntries(
            Map.entry("simple", List.of(text("command", LONG), text("model", SHORT))),
            Map.entry("agentic", List.of(text("goal", LONG), text("success_criteria", 120),
                    text("max_iterations", SHORT), text("model", SHORT))),
            Map.entry("shell", List.of(text("command", 300), text("container_image", SHORT))),
            Map.entry("conditional", List.of(text("condition", LONG), text("then_command", 150),
                    text("else_command", 150), text("execution_mode", SHORT))),
            Map.entry("foreach", List.of(text("items_source", SHORT), text("items", 150), text("items_command", 150),
                    text("command", LONG), text(WORKFLOW_ID, SHORT), text("execution_mode", SHORT))),
            Map.entry("while", List.of(text("condition_command", 150), text("command", LONG),
                    text("max_iterations", SHORT), text("execution_mode", SHORT))),
            Map.entry("switch", List.of(text("expression", 120), count("cases"), count("default"))),
            Map.entry("retry", List.of(text("command", LONG), text("max_retries", SHORT), text("execution_mode", SHORT))),
            Map.entry("approval", List.of(text("question", LONG), text("options", SHORT))),
            Map.entry("human-input", List.of(text("prompt", LONG), text("input_type", SHORT), count("fields"))),
            Map.entry("sub-workflow", List.of(text(WORKFLOW_ID, SHORT))),
            Map.entry("compress", List.of(text("source", SHORT), count("stages"))),
            Map.entry("compress-ai", List.of(text("source", SHORT), text("instruction", LONG))));

    private final WorkflowRepository workflowRepository;

    private static Shown text(String key, int maxChars) {
        return new Shown(key, maxChars);
    }

    private static Shown count(String key) {
        return new Shown(key, 0);
    }

    /** The keys the summary shows per block type — for the drift gate. */
    public static Map<String, List<String>> shownKeys() {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        SHOWN.forEach((type, shown) -> keys.put(type, shown.stream().map(Shown::key).toList()));
        return keys;
    }

    /**
     * Names of all sub-workflows the blocks point to ({@code workflow_id}), in one query
     * instead of one {@code findById} per block (TODO bf009b6f). Soft-deleted and unknown IDs are missing
     * from the map; the summary then shows the ID.
     */
    public Map<String, String> subWorkflowNames(List<BlockDefinition> blocks) {
        Set<String> ids = new LinkedHashSet<>();
        if (blocks != null) {
            for (BlockDefinition block : blocks) {
                Map<String, Object> cfg = block.getConfig();
                if (cfg == null || !showsWorkflowId(block.getType())) continue;
                Object value = cfg.get(WORKFLOW_ID);
                if (value != null && !String.valueOf(value).isBlank()) ids.add(String.valueOf(value).trim());
            }
        }
        if (ids.isEmpty()) return Map.of();
        Map<String, String> names = new HashMap<>();
        for (Object[] row : workflowRepository.findIdAndNameByIdIn(ids)) {
            if (row[1] != null) names.put((String) row[0], (String) row[1]);
        }
        return names;
    }

    private static boolean showsWorkflowId(String type) {
        return SHOWN.getOrDefault(type, List.of()).stream().anyMatch(item -> WORKFLOW_ID.equals(item.key()));
    }

    /**
     * Appends the numbered block list — a {@code Blocks (n):} heading, then per block {@code [type] label}
     * and its config summary — or nothing when there are no blocks. Resolves the sub-workflow names
     * once for the whole list.
     */
    public void appendBlockList(StringBuilder sb, List<BlockDefinition> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        sb.append("\nBlocks (").append(blocks.size()).append("):\n");
        Map<String, String> subWorkflowNames = subWorkflowNames(blocks);
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            Map<String, Object> cfg = block.getConfig() != null ? block.getConfig() : Map.of();
            String label = cfg.containsKey("label") ? String.valueOf(cfg.get("label")) : "";
            sb.append("  ").append(i + 1).append(". [").append(block.getType()).append("]");
            if (!label.isBlank()) sb.append(" ").append(label);
            sb.append("\n");
            appendBlockConfigSummary(sb, block.getType(), cfg, subWorkflowNames);
        }
    }

    /**
     * @param subWorkflowNames names from {@link #subWorkflowNames(List)} — determine once per workflow or
     *                         pool entry, not per block
     */
    public void appendBlockConfigSummary(StringBuilder sb, String type, Map<String, Object> cfg,
                                         Map<String, String> subWorkflowNames) {
        List<Shown> shown = SHOWN.get(type);
        if (shown == null) {
            cfg.forEach((key, value) -> {
                if (value != null && !String.valueOf(value).isBlank()) {
                    line(sb, key, AssistantFormatUtils.truncate(String.valueOf(value), SHORT));
                }
            });
            return;
        }
        for (Shown item : shown) {
            Object value = cfg.get(item.key());
            if (item.maxChars() == 0) {
                if (value instanceof List<?> list) line(sb, item.key(), String.valueOf(list.size()));
            } else if (value != null && !String.valueOf(value).isBlank()) {
                String text = String.valueOf(value).trim();
                if (WORKFLOW_ID.equals(item.key())) {
                    line(sb, "sub-workflow", subWorkflowNames.getOrDefault(text, text));
                } else {
                    line(sb, item.key(), AssistantFormatUtils.truncate(text, item.maxChars()));
                }
            }
        }
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append("     ").append(key).append(": ").append(value).append("\n");
    }
}
