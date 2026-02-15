package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record CreateWebhookRequest(
        @NotBlank String workflowId,
        String description,
        Map<String, Object> defaultContext
) {}
