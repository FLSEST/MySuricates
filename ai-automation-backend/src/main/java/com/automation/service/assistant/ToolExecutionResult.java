package com.automation.service.assistant;

/**
 * Outcome of executing (or rejecting) a previously proposed {@link com.automation.dto.PendingTool}.
 * Emitted via {@link AssistantController} to the WebSocket broadcaster so the frontend can
 * replace the pending-tool card with the result line.
 */
public record ToolExecutionResult(
        String toolId,
        String type,
        boolean success,
        String message
) {
    /**
     * Start of the result line of a proposal that never ran — rejected by the user or expired. Such results
     * stay out of the chat history the model sees (TODO 2f7766e7); executed tools start with {@code ✓}/{@code ⚠}.
     */
    public static final String DECLINED_PREFIX = "✗";

    public static ToolExecutionResult rejected(String toolId, String type) {
        return new ToolExecutionResult(toolId, type, false, DECLINED_PREFIX + " Abgelehnt");
    }

    public static ToolExecutionResult expired(String toolId, String type) {
        return new ToolExecutionResult(toolId, type, false, DECLINED_PREFIX + " Vorschlag abgelaufen");
    }
}
