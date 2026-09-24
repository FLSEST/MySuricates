package com.automation.dto.projection;

import java.time.Instant;

/** Zeitplan eines Ziel-Waechter-Workflows, ohne die EAGER-Assoziationen zu laden (TODO 259f4f66). */
public record GuardScheduleRow(
        String workflowId,
        String scheduleId,
        boolean enabled,
        Instant nextRunAt,
        Instant createdAt
) {}
