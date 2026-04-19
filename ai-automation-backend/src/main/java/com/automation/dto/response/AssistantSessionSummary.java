package com.automation.dto.response;

import java.time.Instant;

public record AssistantSessionSummary(
        String id,
        String preview,
        String page,
        int messageCount,
        Instant startedAt
) {}
