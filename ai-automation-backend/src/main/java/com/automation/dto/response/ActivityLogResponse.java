package com.automation.dto.response;

import java.time.Instant;
import java.util.Map;

public record ActivityLogResponse(
        String id,
        Instant timestamp,
        String level,
        String category,
        String message,
        String source,
        String referenceId,
        Map<String, Object> metadata,
        Instant createdAt
) {}
