package com.automation.dto.request;

import java.time.Instant;
import java.util.Map;

/**
 * Changes to an existing schedule; {@code null} leaves a field as it is. {@code conflictBehavior}
 * ({@code SKIP}/{@code OVERLAP}) became editable with TODO ade3fef8 — before, the form showed it
 * but the request had no field for it.
 */
public record UpdateScheduleRequest(
        String cronExpression,
        String description,
        Map<String, Object> inputContext,
        Instant scheduledAt,
        Instant endDate,
        Integer maxRuns,
        Boolean catchUpOnResume,
        String conflictBehavior
) {}
