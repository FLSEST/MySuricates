package com.automation.dto.response;

import java.util.List;

public record DashboardResponse(
        long totalWorkflows,
        long totalRuns,
        long runsToday,
        long successfulRuns,
        long failedRuns,
        double averageDurationMs,
        double successRate,
        double totalCostUsd30d,
        long totalTokens30d,
        List<TopWorkflowEntry> topWorkflows,
        List<WorkflowRunResponse> recentRuns,
        List<TimeSeriesPoint> timeSeries,
        List<ProviderStats> providerStats,
        List<ModelStats> modelStats
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

    public record ProviderStats(
            String provider,
            long runCount,
            long totalTokens,
            double totalCostUsd,
            double successRate
    ) {}

    public record ModelStats(
            String model,
            long stepCount,
            long totalTokens,
            double totalCostUsd
    ) {}
}
