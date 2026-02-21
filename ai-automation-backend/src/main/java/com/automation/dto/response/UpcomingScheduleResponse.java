package com.automation.dto.response;

import java.time.Instant;
import java.util.List;

public record UpcomingScheduleResponse(
        int count,
        List<UpcomingRun> schedules
) {
    public record UpcomingRun(
            String scheduleId,
            String workflowName,
            Instant scheduledAt
    ) {}
}
