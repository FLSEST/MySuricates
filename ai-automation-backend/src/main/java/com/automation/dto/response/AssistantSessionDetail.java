package com.automation.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AssistantSessionDetail(
        String id,
        String preview,
        String page,
        String entityId,
        List<Map<String, Object>> messages,
        int messageCount,
        Instant startedAt,
        Instant endedAt
) {}
