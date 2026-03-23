package com.automation.dto.response;

import java.time.Instant;
import java.util.Map;

public record CompletionTriggerResponse(
        String id,
        String sourceWorkflowId,
        String sourceWorkflowName,
        String targetWorkflowId,
        String targetWorkflowName,
        boolean enabled,
        boolean triggerOnSuccess,
        boolean triggerOnFailure,
        boolean triggerAlways,
        String description,
        Map<String, Object> contextVariables,
        Instant lastTriggeredAt,
        long triggerCount,
        Instant createdAt
) {}
