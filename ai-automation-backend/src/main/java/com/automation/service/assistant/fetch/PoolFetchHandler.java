package com.automation.service.assistant.fetch;

import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.automation.service.assistant.format.AssistantFormatUtils;
import com.automation.service.pool.WorkflowPoolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Fetch handler for the workflow pool: template index and per-template detail
 * including blocks ({@code pool_workflows}, {@code pool_workflow}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PoolFetchHandler {

    private final WorkflowPoolService workflowPoolService;
    private final BlockConfigSummaryFormatter blockConfigSummaryFormatter;

    public String formatPoolWorkflows() {
        try {
            List<PoolWorkflowEntry> entries = workflowPoolService.listAll();
            if (entries.isEmpty()) return "No pool workflows available.\n";
            var sb = new StringBuilder();
            sb.append("**Workflow pool (").append(entries.size()).append(" templates):**\n\n");
            entries.forEach(e -> {
                String deName = e.name() != null ? e.name().getOrDefault("de", e.name().getOrDefault("en", e.id())) : e.id();
                sb.append("- **").append(deName).append("** (ID: `").append(e.id()).append("`");
                if (e.category() != null) sb.append(", cat: ").append(e.category());
                if (e.blocks() != null) sb.append(", ").append(e.blocks().size()).append(" blocks");
                sb.append(")");
                if (e.tags() != null && !e.tags().isEmpty()) sb.append(" Tags: ").append(String.join(", ", e.tags()));
                String deDesc = e.description() != null ? e.description().getOrDefault("de", e.description().getOrDefault("en", "")) : "";
                if (!deDesc.isBlank()) sb.append("\n  ").append(AssistantFormatUtils.truncate(deDesc, 120));
                sb.append("\n");
            });
            sb.append("\nUse `<fetch type=\"pool_workflow\" id=\"<pool-id>\"/>` for details incl. blocks.\n");
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not load pool workflows for assistant", e);
            return "Pool workflows could not be loaded.\n";
        }
    }

    public String formatPoolWorkflowDetail(String poolId) {
        try {
            PoolWorkflowEntry entry = workflowPoolService.findById(poolId);
            if (entry == null) return "Pool workflow '" + poolId + "' not found.\n";
            var sb = new StringBuilder();
            String deName = entry.name() != null ? entry.name().getOrDefault("de", poolId) : poolId;
            sb.append("**Pool workflow: ").append(deName).append("** (").append(poolId).append(")\n");
            String deDesc = entry.description() != null ? entry.description().getOrDefault("de", "") : "";
            if (!deDesc.isBlank()) sb.append("Description: ").append(deDesc).append("\n");
            if (entry.model() != null) sb.append("Model: ").append(entry.model()).append("\n");
            if (entry.instructions() != null && !entry.instructions().isBlank())
                sb.append("Instructions: ").append(AssistantFormatUtils.truncate(entry.instructions(), 500)).append("\n");
            if (entry.defaultContext() != null && !entry.defaultContext().isEmpty())
                sb.append("Default variables: ").append(entry.defaultContext()).append("\n");
            if (entry.blocks() != null && !entry.blocks().isEmpty()) {
                sb.append("\nBlocks (").append(entry.blocks().size()).append("):\n");
                for (int i = 0; i < entry.blocks().size(); i++) {
                    BlockDefinition block = entry.blocks().get(i);
                    Map<String, Object> cfg = block.getConfig() != null ? block.getConfig() : Map.of();
                    String label = cfg.containsKey("label") ? String.valueOf(cfg.get("label")) : "";
                    sb.append("  ").append(i + 1).append(". [").append(block.getType()).append("]");
                    if (!label.isBlank()) sb.append(" ").append(label);
                    sb.append("\n");
                    blockConfigSummaryFormatter.appendBlockConfigSummary(sb, block.getType(), cfg);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not load pool workflow detail for {}", poolId, e);
            return "Pool workflow '" + poolId + "' not found.\n";
        }
    }
}
