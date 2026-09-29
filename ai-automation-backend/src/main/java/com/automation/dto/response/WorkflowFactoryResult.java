package com.automation.dto.response;

import java.util.List;

/**
 * Outcome of a factory call. {@code buildFindings} are the build-check findings from MEDIUM up that the
 * generated definition still has (TODO 326d5987) — one line each, HIGH first, empty when clean; German.
 * {@code buildFindingsEn} carries the same lines in English (TODO 81773d7b) — the UI picks by its language and
 * falls back to {@code buildFindings} when it is empty (older backends).
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
        List<String> buildFindings,
        List<String> buildFindingsEn
) {
    public WorkflowFactoryResult {
        if (retryErrors == null) retryErrors = List.of();
        if (buildFindings == null) buildFindings = List.of();
        if (buildFindingsEn == null) buildFindingsEn = List.of();
    }

    /** With German build findings only. */
    public WorkflowFactoryResult(String workflowId, String workflowName, String status, String testRunId,
                                 String testStatus, String testError, boolean promotedAsTemplate,
                                 long durationMs, List<String> retryErrors, List<String> buildFindings) {
        this(workflowId, workflowName, status, testRunId, testStatus, testError, promotedAsTemplate,
                durationMs, retryErrors, buildFindings, List.of());
    }

    /** Without build findings — for paths that do not generate a definition. */
    public WorkflowFactoryResult(String workflowId, String workflowName, String status, String testRunId,
                                 String testStatus, String testError, boolean promotedAsTemplate,
                                 long durationMs, List<String> retryErrors) {
        this(workflowId, workflowName, status, testRunId, testStatus, testError, promotedAsTemplate,
                durationMs, retryErrors, List.of(), List.of());
    }
}
