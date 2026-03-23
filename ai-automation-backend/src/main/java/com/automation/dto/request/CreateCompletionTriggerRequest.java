package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record CreateCompletionTriggerRequest(
        @NotBlank String sourceWorkflowId,
        @NotBlank String targetWorkflowId,
        boolean triggerOnSuccess,
        boolean triggerOnFailure,
        boolean triggerAlways,
        String description,
        Map<String, Object> contextVariables
) {}
