package com.automation.dto.response;

import java.time.Instant;
import java.util.Map;

public record WebhookTriggerResponse(
        String id,
        String workflowId,
        String workflowName,
        String secretToken,
        String webhookUrl,
        boolean tokenVisible,
        boolean enabled,
        String description,
        Map<String, Object> defaultContext,
        Instant lastTriggeredAt,
        long triggerCount,
        Instant createdAt,
        /** True when trigger calls must be signed (the secret itself is never returned here). */
        boolean signatureRequired
) {
    public WebhookTriggerResponse(String id, String workflowId, String workflowName, String secretToken,
                                  String webhookUrl, boolean tokenVisible, boolean enabled, String description,
                                  Map<String, Object> defaultContext, Instant lastTriggeredAt, long triggerCount,
                                  Instant createdAt) {
        this(id, workflowId, workflowName, secretToken, webhookUrl, tokenVisible, enabled, description,
                defaultContext, lastTriggeredAt, triggerCount, createdAt, false);
    }
}
