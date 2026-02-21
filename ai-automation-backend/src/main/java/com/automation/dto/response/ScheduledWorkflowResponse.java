package com.automation.dto.response;

import java.time.Instant;
import java.util.Map;

public record ScheduledWorkflowResponse(
        String id,
        String workflowId,
        String workflowName,
        String cronExpression,
        String scheduleType,
        Instant scheduledAt,
        boolean enabled,
        String description,
        Map<String, Object> inputContext,
        Instant lastRunAt,
        Instant nextRunAt,
        Instant createdAt
) {}
