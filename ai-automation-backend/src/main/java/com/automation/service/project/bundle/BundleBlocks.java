package com.automation.service.project.bundle;

import com.automation.executor.ExecutionMode;
import com.automation.executor.NestedBlocks;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Block-level work on the raw block maps of a bundle: sub-workflow references, skill references and
 * what the preview shows. Nested blocks are found by {@link NestedBlocks}, the walker the validators use.
 */
final class BundleBlocks {

    static final String SUB_WORKFLOW = "sub-workflow";
    /** Block types that start another workflow by {@code workflow_id}: foreach does it per item (a5c9fbbf). */
    private static final Set<String> WORKFLOW_STARTERS = Set.of(SUB_WORKFLOW, "foreach");
    /** Current key and the legacy key the sub-workflow executor still reads. */
    static final List<String> WORKFLOW_ID_KEYS = List.of("workflow_id", "workflowId");
    static final Pattern WORKFLOW_REF = Pattern.compile("@@workflow:([^@\\s]+)@@");
    static final Pattern SKILL_REF = Pattern.compile("@@skill:(.+)@@");
    static final int SHELL_PREVIEW_CHARS = 200;
    private static final List<String> SHELL_ONLY_FIELDS = List.of("condition_command", "items_command");

    private BundleBlocks() {
    }

    static String workflowRef(String key) {
        return "@@workflow:" + key + "@@";
    }

    static String skillRef(String name) {
        return "@@skill:" + name + "@@";
    }

    static String externalRefWarning(String workflowName, String workflowId) {
        return "Workflow '" + workflowName + "' calls a sub-workflow outside this project (" + workflowId
                + ") — the reference is kept unchanged and must exist on the target instance.";
    }

    /** Every block — top level first, then its nested blocks depth first — as {@code (type, config)}. */
    @SuppressWarnings("unchecked")
    static void forEachBlock(List<Map<String, Object>> blocks, BiConsumer<String, Map<String, Object>> visitor) {
        if (blocks == null) {
            return;
        }
        for (Map<String, Object> block : blocks) {
            if (block == null) {
                continue;
            }
            Map<String, Object> config = block.get("config") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
            visitor.accept(block.get("type") instanceof String t ? t : null, config);
            NestedBlocks.visit(config, nested -> visitor.accept(nested.type(), nested.config()));
        }
    }

    /**
     * Export: sub-workflow ids of workflows in the bundle become {@code @@workflow:<key>@@} (in place);
     * any other id stays and is returned as a warning.
     */
    static List<String> exportWorkflowRefs(List<Map<String, Object>> blocks, Map<String, String> keyById,
                                           String workflowName) {
        List<String> warnings = new ArrayList<>();
        forEachBlock(blocks, (type, config) -> {
            if (!WORKFLOW_STARTERS.contains(type) || config == null) {
                return;
            }
            for (String idKey : WORKFLOW_ID_KEYS) {
                if (config.get(idKey) instanceof String id && isFixedId(id)) {
                    String key = keyById.get(id);
                    if (key != null) {
                        config.put(idKey, workflowRef(key));
                    } else {
                        warnings.add(externalRefWarning(workflowName, id));
                    }
                }
            }
        });
        return warnings;
    }

    /** Preview: sub-workflow ids that are no bundle reference (the exporter kept them as they were). */
    static List<String> externalRefs(List<Map<String, Object>> blocks) {
        List<String> ids = new ArrayList<>();
        forEachBlock(blocks, (type, config) -> {
            if (WORKFLOW_STARTERS.contains(type) && config != null) {
                for (String idKey : WORKFLOW_ID_KEYS) {
                    if (config.get(idKey) instanceof String id && isFixedId(id) && !WORKFLOW_REF.matcher(id).matches()) {
                        ids.add(id);
                    }
                }
            }
        });
        return ids;
    }

    /** A concrete id — not blank and no {@code {{ … }}} placeholder, which the run resolves from its context. */
    private static boolean isFixedId(String id) {
        return !id.isBlank() && !id.contains("{{");
    }

    /** All {@code @@workflow:<key>@@} keys used anywhere in the blocks. */
    static Set<String> workflowRefKeys(List<Map<String, Object>> blocks) {
        Set<String> keys = new LinkedHashSet<>();
        if (blocks != null) {
            BundleStrings.copy(blocks, "", (value, field) -> {
                Matcher m = WORKFLOW_REF.matcher(value);
                while (m.find()) {
                    keys.add(m.group(1));
                }
                return value;
            });
        }
        return keys;
    }

    /**
     * Rewrites every {@code skillIds} list in the tree (top-level and nested blocks) in place;
     * a {@code null} from {@code mapper} drops the entry.
     */
    @SuppressWarnings("unchecked")
    static void mapSkillIds(Object node, Function<String, String> mapper) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("skillIds") instanceof List<?> ids) {
                List<String> mapped = new ArrayList<>();
                for (Object id : ids) {
                    String value = id instanceof String s ? mapper.apply(s) : null;
                    if (value != null) {
                        mapped.add(value);
                    }
                }
                ((Map<String, Object>) map).put("skillIds", mapped);
            }
            map.values().forEach(v -> mapSkillIds(v, mapper));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> mapSkillIds(v, mapper));
        }
    }

    static List<String> blockTypes(List<Map<String, Object>> blocks) {
        Set<String> types = new LinkedHashSet<>();
        forEachBlock(blocks, (type, config) -> {
            if (type != null) {
                types.add(type);
            }
        });
        return List.copyOf(types);
    }

    /** Distinct non-blank values of a config key ({@code provider}, {@code model}) over all blocks. */
    static Set<String> configValues(List<Map<String, Object>> blocks, String key) {
        Set<String> values = new LinkedHashSet<>();
        forEachBlock(blocks, (type, config) -> {
            if (config != null && config.get(key) instanceof String v && !v.isBlank()) {
                values.add(v);
            }
        });
        return values;
    }

    /** The first {@value #SHELL_PREVIEW_CHARS} characters of every field that runs as a shell command. */
    static List<String> shellCommands(List<Map<String, Object>> blocks) {
        List<String> commands = new ArrayList<>();
        forEachBlock(blocks, (type, config) -> {
            if (config == null) {
                return;
            }
            if (("shell".equals(type) || ExecutionMode.isShell(config)) && config.get("command") instanceof String c) {
                commands.add(shorten(c));
            }
            for (String field : SHELL_ONLY_FIELDS) {
                if (config.get(field) instanceof String c && !c.isBlank()) {
                    commands.add(shorten(c));
                }
            }
        });
        return commands;
    }

    private static String shorten(String command) {
        return command.length() <= SHELL_PREVIEW_CHARS ? command : command.substring(0, SHELL_PREVIEW_CHARS);
    }
}
