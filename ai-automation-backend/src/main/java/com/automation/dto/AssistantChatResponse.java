package com.automation.dto;

import java.util.List;

public record AssistantChatResponse(
        String message,
        List<AssistantAction> actions,
        String model
) {
    /** Backwards-compatible constructor — existing callsites use 2 args. */
    public AssistantChatResponse(String message, List<AssistantAction> actions) {
        this(message, actions, null);
    }

    /** An actionable suggestion the assistant is offering the user. */
    public record AssistantAction(
            /** run_workflow | show_workflow | start_wish | show_run */
            String type,
            /** Entity ID (workflowId, runId). Null for start_wish. */
            String id,
            /** Human-readable name of the entity. For start_wish: pre-filled wish text. */
            String name,
            /** Button label to display in the UI. */
            String label
    ) {}
}
