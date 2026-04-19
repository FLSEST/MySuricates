package com.automation.service.assistant;

/**
 * Outcome of executing (or rejecting) a previously proposed {@link PendingTool}.
 * Emitted via {@link AssistantController} to the WebSocket broadcaster so the frontend can
 * replace the pending-tool card with the result line.
 */
public record ToolExecutionResult(
        String toolId,
        String type,
        boolean success,
        String message
) {
    public static ToolExecutionResult rejected(String toolId, String type) {
        return new ToolExecutionResult(toolId, type, false, "✗ Abgelehnt");
    }

    public static ToolExecutionResult expired(String toolId, String type) {
        return new ToolExecutionResult(toolId, type, false, "✗ Vorschlag abgelaufen");
    }
}
