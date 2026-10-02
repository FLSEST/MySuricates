package com.automation.dto.projection;

import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;

import java.time.Instant;

/**
 * One run of the dashboard window with only the columns the dashboard reads (TODO 2ccb9e40) —
 * no {@code input_context}, and from the {@code metadata}/{@code error} JSON only the three values
 * SQLite extracts: {@code gate_status} (text only), the JSON type of {@code todoTriggerRun}
 * ({@code "true"} for a JSON true) and the error {@code message} of a FAILED run (text only).
 *
 * @param workflowNameSnapshot the workflow name captured when the run started (may be null)
 */
public record DashboardRunRow(
        String id,
        RunStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        TriggerSource triggerSource,
        String triggeredByScheduleId,
        String workflowNameSnapshot,
        String workflowId,
        String workflowName,
        String projectId,
        String projectName,
        String gateStatus,
        String todoTriggerRunType,
        String failureMessage
) {
    public boolean todoTriggerRun() {
        return "true".equals(todoTriggerRunType);
    }
}
