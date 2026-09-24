package com.automation.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Runs still to come in a period (dashboard). {@code pausedUntil} is the end of an active
 * skip-until pause, else null — runs inside it are left out, the tick skips them (TODO a776fd2a).
 * {@code maintenance} is true while the maintenance switch is on: nothing starts then, so no run is
 * listed at all (TODO 7704b1e9).
 */
public record UpcomingScheduleResponse(
        int count,
        List<UpcomingRun> schedules,
        Instant pausedUntil,
        boolean maintenance
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
