package com.automation.executor.blocks;

import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;

import java.util.Map;

/**
 * Parsed and validated configuration for an agentic block.
 */
public record AgenticBlockConfig(
        String goal,
        String successCriteria,
        int maxIterations,
        int iterationTimeoutSeconds,
        int totalTimeoutSeconds,
        int maxTotalTokens,
        int maxRepeatedActions,
        boolean requireApproval,
        Integer claudeTimeout
) {

    /**
     * Extracts and validates agentic block config from raw config map + execution context.
     */
    public static AgenticBlockConfig from(Map<String, Object> config, ExecutionContext context) {
        String goal = context.resolveTemplate(requireString(config, "goal"));
        String successCriteria = context.resolveTemplate(
                (String) config.getOrDefault("success_criteria", ""));
        int maxIterations = ((Number) config.getOrDefault("max_iterations", 10)).intValue();
        int iterationTimeoutSeconds = ((Number) config.getOrDefault(
                "iteration_timeout_seconds", 300)).intValue();
        int totalTimeoutSeconds = ((Number) config.getOrDefault(
                "total_timeout_seconds", 1800)).intValue();
        int maxTotalTokens = ((Number) config.getOrDefault(
                "max_total_tokens", 200_000)).intValue();
        int maxRepeatedActions = ((Number) config.getOrDefault(
                "max_repeated_actions", 3)).intValue();
        boolean requireApproval = Boolean.TRUE.equals(config.get("require_approval"));
        Integer claudeTimeout = config.containsKey("timeout_seconds")
                ? ((Number) config.get("timeout_seconds")).intValue()
                : null;

        return new AgenticBlockConfig(
                goal, successCriteria, maxIterations, iterationTimeoutSeconds,
                totalTimeoutSeconds, maxTotalTokens, maxRepeatedActions,
                requireApproval, claudeTimeout);
    }

    private static String requireString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            throw new WorkflowExecutionException("Agentic block requires '" + key + "' in config");
        }
        return value.toString();
    }
}
