package com.automation.dto;

public record AssistantPushEvent(
        String type,
        String sessionId,
        String eventKind,
        String title,
        String detail,
        String linkTo,
        String timestamp
) {}
