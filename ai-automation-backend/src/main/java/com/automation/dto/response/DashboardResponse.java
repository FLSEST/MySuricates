package com.automation.dto.response;

import java.util.List;

public record DashboardResponse(
        long totalWorkflows,
        long totalRuns,
        long successfulRuns,
        long failedRuns,
        double averageDurationMs,
        double successRate,
        List<TopWorkflowEntry> topWorkflows,
        List<WorkflowRunResponse> recentRuns,
        List<TimeSeriesPoint> timeSeries
) {
    public record TopWorkflowEntry(
            String workflowId,
            String workflowName,
            long runCount
    ) {}

    public record TimeSeriesPoint(
            String date,
            long count,
            double successRate
    ) {}
}
