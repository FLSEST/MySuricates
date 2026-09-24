package com.automation.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

public record AssistantChatResponse(
        String message,
        List<AssistantAction> actions,
        String model,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        String provider,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Long responseTimeMs,
        /**
         * Tool actions the assistant proposed in this turn. The frontend renders a confirmation
         * card for each and calls {@code POST /api/v1/assistant/pending-tools/{id}/confirm|reject}.
         * Empty/null if the turn contained no {@code <tool>} tags.
         */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<PendingTool> pendingTools
) {
    /** Backwards-compatible constructor — existing callsites use 2 args. */
    public AssistantChatResponse(String message, List<AssistantAction> actions) {
        this(message, actions, null, null, null, null);
    }

    public AssistantChatResponse(String message, List<AssistantAction> actions, String model) {
        this(message, actions, model, null, null, null);
    }

    public AssistantChatResponse(String message, List<AssistantAction> actions, String model, String provider) {
        this(message, actions, model, provider, null, null);
    }

    /** Backwards-compatible 5-arg (pre-pendingTools era). */
    public AssistantChatResponse(String message, List<AssistantAction> actions, String model, String provider,
                                 Long responseTimeMs) {
        this(message, actions, model, provider, responseTimeMs, null);
    }

    public AssistantChatResponse withPendingTools(List<PendingTool> pendingTools) {
        return new AssistantChatResponse(message, actions, model, provider, responseTimeMs, pendingTools);
    }

    public AssistantChatResponse withResponseTimeMs(long responseTimeMs) {
        return new AssistantChatResponse(message, actions, model, provider, responseTimeMs, pendingTools);
    }

    /** An actionable suggestion the assistant is offering the user. */
    public record AssistantAction(
            /**
             * Navigational: run_workflow | show_workflow | start_wish | show_run | ask_choice.
             * Mutating proposal (server-converted to PendingTool, not surfaced to frontend as
             * an action button): propose_create_todo | propose_cancel_run |
             * propose_rerun_workflow | propose_update_todo_status.
             */
            String type,
            /** Entity ID (workflowId, runId, todoId). Null for start_wish and ask_choice. */
            String id,
            /** Human-readable name of the entity. For start_wish: pre-filled wish text. */
            String name,
            /** Button label to display in the UI. */
            String label,
            /** Only for ask_choice: list of options rendered as individual clickable buttons. */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            List<String> options,
            /**
             * Extra payload for mutating proposals (priority/tags for create_todo,
             * status for update_todo_status, etc.). Consumed server-side when the action is
             * converted to a PendingTool — never reaches the frontend.
             */
            @JsonInclude(JsonInclude.Include.NON_NULL)
            Map<String, Object> payload
    ) {
        /** Backwards-compatible constructor for existing 4-field callsites. */
        public AssistantAction(String type, String id, String name, String label) {
            this(type, id, name, label, null, null);
        }

        /** Constructor for ask_choice actions (with options, no payload). */
        public AssistantAction(String type, String id, String name, String label, List<String> options) {
            this(type, id, name, label, options, null);
        }
    }
}
