package com.automation.dto.response;

import java.time.Instant;
import java.util.Map;

public record WebhookTriggerResponse(
        String id,
        String workflowId,
        String workflowName,
        String secretToken,
        String webhookUrl,
        boolean enabled,
        String description,
        Map<String, Object> defaultContext,
        Instant lastTriggeredAt,
        long triggerCount,
        Instant createdAt
) {}
