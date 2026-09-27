package com.automation.dto.response;

import java.util.List;

/**
 * Outcome of a factory call. {@code buildFindings} are the build-check findings from MEDIUM up that the
 * generated definition still has (TODO 326d5987) — one line each, HIGH first, empty when clean.
 */
public record WorkflowFactoryResult(
        String workflowId,
        String workflowName,
        String status,
        String testRunId,
        String testStatus,
        String testError,
        boolean promotedAsTemplate,
        long durationMs,
        List<String> retryErrors,
        List<String> buildFindings
) {
    public WorkflowFactoryResult {
        if (retryErrors == null) retryErrors = List.of();
        if (buildFindings == null) buildFindings = List.of();
    }

    /** Without build findings — for paths that do not generate a definition. */
    public WorkflowFactoryResult(String workflowId, String workflowName, String status, String testRunId,
                                 String testStatus, String testError, boolean promotedAsTemplate,
                                 long durationMs, List<String> retryErrors) {
        this(workflowId, workflowName, status, testRunId, testStatus, testError, promotedAsTemplate,
                durationMs, retryErrors, List.of());
    }
}
