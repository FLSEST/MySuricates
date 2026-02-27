package com.automation.dto.response;

import com.automation.entity.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ChainRunResponse(
        String id,
        String chainId,
        String chainName,
        RunStatus status,
        List<ChainRunEntryResponse> entryRuns,
        Map<String, Object> inputContext,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String error,
        Map<String, Object> metadata
) {
    public record ChainRunEntryResponse(
            int entryIndex,
            String workflowId,
            String workflowName,
            String projectName,
            String workflowRunId,
            String status,
            Instant startedAt,
            Instant completedAt,
            Long durationMs
    ) {}
}
