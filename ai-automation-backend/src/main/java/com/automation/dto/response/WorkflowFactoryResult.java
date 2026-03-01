package com.automation.dto.response;

import java.util.List;

public record WorkflowFactoryResult(
        String workflowId,
        String workflowName,
        String status,
        String testRunId,
        String testStatus,
        String testError,
        boolean promotedAsTemplate,
        long durationMs,
        List<String> retryErrors
) {
    public WorkflowFactoryResult {
        if (retryErrors == null) retryErrors = List.of();
    }
}
