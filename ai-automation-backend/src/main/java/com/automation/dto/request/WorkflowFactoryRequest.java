package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record WorkflowFactoryRequest(
        @NotBlank String topic,
        String description,
        String category,
        String projectId,
        Map<String, Object> testInputs,
        boolean autoPromote,
        boolean autoFix,
        int maxRetries,
        String provider
) {
    public WorkflowFactoryRequest {
        if (maxRetries <= 0) maxRetries = 2;
        if (testInputs == null) testInputs = Map.of();
    }
}
