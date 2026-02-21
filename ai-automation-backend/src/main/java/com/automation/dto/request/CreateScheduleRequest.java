package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

public record CreateScheduleRequest(
        @NotBlank String workflowId,
        String cronExpression,
        String description,
        Map<String, Object> inputContext,
        String scheduleType,
        Instant scheduledAt
) {}
