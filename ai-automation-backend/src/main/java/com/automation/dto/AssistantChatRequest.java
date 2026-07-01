package com.automation.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record AssistantChatRequest(
        @NotBlank String message,
        List<MessageEntry> history,
        PageContext context,
        String provider,
        String language,
        String sessionId,
        String requestId
) {
    /**
     * Backward-compatible constructor without an explicit UI language.
     * {@code language} defaults to {@code null}, which resolves to German (the platform default).
     */
    public AssistantChatRequest(String message, List<MessageEntry> history, PageContext context,
                                String provider, String sessionId, String requestId) {
        this(message, history, context, provider, null, sessionId, requestId);
    }

    public record MessageEntry(String role, String content) {}

    public record PageContext(
            String page,
            String entityId,
            String projectId,
            String workflowId,
            String runId,
            String pathname
    ) {
        public PageContext(String page, String entityId, String projectId) {
            this(page, entityId, projectId, null, null, null);
        }
    }
}
