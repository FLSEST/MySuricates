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

    /**
     * One earlier turn. {@code toolResults} (optional, TODO 2f7766e7) are the results of the tools the user
     * confirmed on an assistant turn — older clients leave the field out, and the prompt stays as before.
     * Everything in it is client input: {@code ToolResultHistory} (service.assistant) filters, bounds and
     * marks it as data before it reaches the prompt.
     */
    public record MessageEntry(String role, String content, List<ToolResultEntry> toolResults) {
        /** Without tool results — the form of clients before TODO 2f7766e7. */
        public MessageEntry(String role, String content) {
            this(role, content, null);
        }
    }

    /**
     * What a confirmed tool returned: its type, whether it succeeded and the result line of
     * {@code ToolExecutionResult} (service.assistant). Declined or expired proposals carry the
     * {@code ✗} result and never reach the prompt.
     */
    public record ToolResultEntry(String type, Boolean success, String message) {}

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
