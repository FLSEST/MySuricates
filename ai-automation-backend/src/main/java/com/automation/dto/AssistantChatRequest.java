package com.automation.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AssistantChatRequest(
        @NotBlank String message,
        List<MessageEntry> history,
        PageContext context,
        String provider,
        String sessionId
) {
    public record MessageEntry(String role, String content) {}

    public record PageContext(String page, String entityId, String projectId) {}
}
