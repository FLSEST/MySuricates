package com.automation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

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
            /** run_workflow | show_workflow | start_wish | show_run | ask_choice */
            String type,
            /** Entity ID (workflowId, runId). Null for start_wish and ask_choice. */
            String id,
            /** Human-readable name of the entity. For start_wish: pre-filled wish text. */
            String name,
            /** Button label to display in the UI. */
            String label,
            /** Only for ask_choice: list of options rendered as individual clickable buttons. */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            List<String> options
    ) {
        /** Backwards-compatible constructor for existing 4-field callsites. */
        public AssistantAction(String type, String id, String name, String label) {
            this(type, id, name, label, null);
        }
    }
}
