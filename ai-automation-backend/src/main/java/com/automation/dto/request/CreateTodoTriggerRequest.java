package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record CreateTodoTriggerRequest(
        @NotBlank String tagPattern,
        String projectId,
        String sourceFilter,
        String minPriority,
        @NotBlank String targetWorkflowId,
        Map<String, Object> contextTemplate,
        String description,
        Integer maxAttempts,
        Boolean autoClose,
        Boolean processBacklog
) {}
