package com.automation.dto.response;

import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @param triggerSource what started the chain (V96); null for chain runs from before V96 that no schedule started
 */
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
        Map<String, Object> metadata,
        String triggeredByScheduleId,
        TriggerSource triggerSource
) {
    /** Compatibility constructor for callers from before the starter was recorded on the chain (V96). */
    public ChainRunResponse(String id, String chainId, String chainName, RunStatus status,
                            List<ChainRunEntryResponse> entryRuns, Map<String, Object> inputContext,
                            Instant startedAt, Instant completedAt, Long durationMs, String error,
                            Map<String, Object> metadata, String triggeredByScheduleId) {
        this(id, chainId, chainName, status, entryRuns, inputContext, startedAt, completedAt, durationMs, error,
                metadata, triggeredByScheduleId, null);
    }

    public record ChainRunEntryResponse(
            int entryIndex,
            String workflowId,
            String workflowName,
            String projectName,
            String workflowRunId,
            RunStatus status,
            Instant startedAt,
            Instant completedAt,
            Long durationMs
    ) {}
}
