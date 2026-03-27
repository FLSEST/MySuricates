package com.automation.dto.request;

import java.time.Instant;
import java.util.Map;

public record UpdateScheduleRequest(
        String cronExpression,
        String description,
        Map<String, Object> inputContext,
        Instant scheduledAt,
        Instant endDate,
        Integer maxRuns
) {}
