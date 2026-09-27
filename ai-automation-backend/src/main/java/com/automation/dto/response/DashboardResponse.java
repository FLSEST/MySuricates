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
        long totalInputTokens,
        long totalOutputTokens,
        List<TopWorkflowEntry> topWorkflows,
        List<WorkflowRunResponse> recentRuns,
        List<TimeSeriesPoint> timeSeries,
        List<ProviderStats> providerStats,
        List<IntegrationStats> integrationStats,
        List<ModelStats> modelStats,
        List<GateStatusEntry> gateStatusSummary,
        List<FailureReasonEntry> failureReasons,
        List<CostByWorkflowEntry> costByWorkflow,
        List<BlockTypeUsageEntry> blockTypeUsage,
        TriggerDistribution triggerDistribution
) {
    public record TopWorkflowEntry(
            String workflowId,
            String workflowName,
            long runCount
    ) {}

    public record TimeSeriesPoint(
            String date,
            long count,
            double successRate,
            long failedCount,
            double avgDurationMs,
            double totalCostUsd,
            long totalTokens
    ) {}

    public record ProviderStats(
            String provider,
            long runCount,
            long totalTokens,
            double totalCostUsd,
            double successRate
    ) {}

    public record IntegrationStats(
            String provider,
            long runCount,
            long totalTokens,
            double successRate
    ) {}

    public record ModelStats(
            String model,
            String requestedModel,
            String provider,
            long stepCount,
            long totalTokens,
            double totalCostUsd
    ) {}

    public record GateStatusEntry(
            String status,
            long count
    ) {}

    public record FailureReasonEntry(
            String reason,
            long count
    ) {}

    public record CostByWorkflowEntry(
            String workflowId,
            String workflowName,
            double totalCostUsd,
            long runCount
    ) {}

    public record BlockTypeUsageEntry(
            String blockType,
            long stepCount,
            long failedCount,
            double failureRate
    ) {}

    public record TriggerDistribution(
            long manual,
            long scheduled,
            long webhook,
            long completionTrigger,
            long todoTrigger,
            /** Chain, sub-workflow, wish and auto-repair runs — automatic, but none of the above. */
            long otherAutomatic
    ) {}
}
