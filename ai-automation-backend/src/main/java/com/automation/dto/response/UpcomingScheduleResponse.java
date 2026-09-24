package com.automation.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Runs still to come in a period (dashboard). {@code pausedUntil} is the end of an active
 * skip-until pause, else null — runs inside it are left out, the tick skips them (TODO a776fd2a).
 */
public record UpcomingScheduleResponse(
        int count,
        List<UpcomingRun> schedules,
        Instant pausedUntil
) {
    public record UpcomingRun(
            String scheduleId,
            String workflowName,
            String projectName,
            String chainId,
            String chainName,
            String targetType,
            Instant scheduledAt
    ) {}
}
