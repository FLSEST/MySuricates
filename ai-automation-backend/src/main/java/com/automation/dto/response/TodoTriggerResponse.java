package com.automation.dto.response;

import java.time.Instant;
import java.util.Map;

public record TodoTriggerResponse(
        String id,
        String tagPattern,
        String projectId,
        String sourceFilter,
        String minPriority,
        String targetWorkflowId,
        String targetWorkflowName,
        Map<String, Object> contextTemplate,
        boolean enabled,
        int maxAttempts,
        boolean autoClose,
        boolean processBacklog,
        String description,
        Instant lastTriggeredAt,
        long triggerCount,
        Instant createdAt
) {}
