package com.automation.executor.blocks;

import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

import static com.automation.config.ExecutionDefaults.*;

/**
 * Parsed and validated configuration for an agentic block.
 */
@Slf4j
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
        int maxIterations = ((Number) config.getOrDefault("max_iterations", AGENTIC_MAX_ITERATIONS)).intValue();
        int iterationTimeoutSeconds = ((Number) config.getOrDefault(
                "iteration_timeout_seconds", AGENTIC_ITERATION_TIMEOUT_SECONDS)).intValue();
        int totalTimeoutSeconds = ((Number) config.getOrDefault(
                "total_timeout_seconds", AGENTIC_TOTAL_TIMEOUT_SECONDS)).intValue();
        int maxTotalTokens = ((Number) config.getOrDefault(
                "max_total_tokens", AGENTIC_MAX_TOTAL_TOKENS)).intValue();
        int maxRepeatedActions = ((Number) config.getOrDefault(
                "max_repeated_actions", AGENTIC_MAX_REPEATED_ACTIONS)).intValue();
        boolean requireApproval = Boolean.TRUE.equals(config.get("require_approval"));
        Integer claudeTimeout = config.containsKey("timeout_seconds")
                ? ((Number) config.get("timeout_seconds")).intValue()
                : null;

        // Auto-align: iteration timeout must accommodate the CLI timeout (REASON + ACT + overhead)
        if (claudeTimeout != null && claudeTimeout > 0) {
            int minIterationTimeout = claudeTimeout * 2 + 60; // REASON + ACT + 60s overhead
            if (iterationTimeoutSeconds < minIterationTimeout) {
                log.warn("Auto-aligning iteration_timeout_seconds: {} → {} (timeout_seconds={}, need room for REASON + ACT + overhead)",
                        iterationTimeoutSeconds, minIterationTimeout, claudeTimeout);
                iterationTimeoutSeconds = minIterationTimeout;
            }
            // Also ensure total timeout can fit at least one full iteration
            if (totalTimeoutSeconds < minIterationTimeout) {
                int newTotalTimeout = minIterationTimeout * maxIterations;
                log.warn("Auto-aligning total_timeout_seconds: {} → {} (must fit at least one iteration of {}s)",
                        totalTimeoutSeconds, newTotalTimeout, minIterationTimeout);
                totalTimeoutSeconds = newTotalTimeout;
            }
        }

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
