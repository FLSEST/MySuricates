package com.automation.dto.request;

import java.time.Instant;
import java.util.Map;

public record CreateScheduleRequest(
        String workflowId,
        String chainId,
        String targetType,
        String cronExpression,
        String description,
        Map<String, Object> inputContext,
        String scheduleType,
        Instant scheduledAt,
        String delayExpression,
        Instant endDate,
        Integer maxRuns,
        String conflictBehavior
) {}
