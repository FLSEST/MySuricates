package com.automation.executor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which blocks start another workflow, and under which config key they name it: {@code sub-workflow}
 * ({@code workflow_id}, falling back to the legacy {@code workflowId}) and {@code foreach} with
 * {@code workflow_id}, one run per item (TODO a5c9fbbf). Every place that looks for workflow references
 * in a definition asks {@link #startedWorkflowIds} — bundle export and the sealed sync scope used to keep
 * their own copies, and one of them had missed foreach (TODO 49558f78). Walk nested blocks with
 * {@link NestedBlocks}. The executors keep their key literals (the config-key drift gates measure those);
 * {@code WorkflowStartersExecutorKeysTest} pins that they read every key listed here.
 */
public final class WorkflowStarters {

    public static final String SUB_WORKFLOW = "sub-workflow";
    public static final String FOREACH = "foreach";
    /** The key both starters read. */
    public static final String WORKFLOW_ID_KEY = "workflow_id";
    /** The camelCase key only the sub-workflow block still reads, when {@code workflow_id} is missing or blank. */
    public static final String LEGACY_WORKFLOW_ID_KEY = "workflowId";

    private static final List<String> SUB_WORKFLOW_KEYS = List.of(WORKFLOW_ID_KEY, LEGACY_WORKFLOW_ID_KEY);
    private static final List<String> FOREACH_KEYS = List.of(WORKFLOW_ID_KEY);

    private WorkflowStarters() {
    }

    /** The config keys under which a block of {@code type} names the workflow it starts; empty for any other type. */
    public static List<String> workflowIdKeys(String type) {
        if (SUB_WORKFLOW.equals(type)) {
            return SUB_WORKFLOW_KEYS;
        }
        if (FOREACH.equals(type)) {
            return FOREACH_KEYS;
        }
        return List.of();
    }

    /**
     * The workflow ids a block of {@code type} starts, by config key, in key order — every non-blank string,
     * placeholders included (whether a {@code {{ … }}} id counts is the caller's call). Empty for blocks
     * that start no workflow or have no config. A fresh map, so the caller may write to {@code config}
     * while going through it.
     */
    public static Map<String, String> startedWorkflowIds(String type, Map<String, Object> config) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (config == null) {
            return ids;
        }
        for (String key : workflowIdKeys(type)) {
            if (config.get(key) instanceof String id && !id.isBlank()) {
                ids.put(key, id);
            }
        }
        return ids;
    }
}
