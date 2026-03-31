package com.automation.dto;

public record AssistantStatusUpdate(
        String type,
        String requestId,
        String phase,
        String detail,
        long elapsedMs,
        String timestamp
) {}
