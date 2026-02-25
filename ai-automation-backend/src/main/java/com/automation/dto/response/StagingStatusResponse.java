package com.automation.dto.response;

public record StagingStatusResponse(
        boolean containerRunning,
        boolean healthy,
        String promotePhase,
        int promoteStep,
        int promoteTotalSteps,
        String lastSnapshot,
        String error,
        String stableVersion,
        String stableBuildTimestamp
) {
}
