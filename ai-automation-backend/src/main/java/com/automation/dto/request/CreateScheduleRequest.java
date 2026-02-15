package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record CreateScheduleRequest(
        @NotBlank String workflowId,
        @NotBlank String cronExpression,
        String description,
        Map<String, Object> inputContext
) {}
