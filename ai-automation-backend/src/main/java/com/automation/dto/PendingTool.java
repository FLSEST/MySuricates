package com.automation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Map;

/**
 * A tool call that the assistant has proposed but not yet executed.
 * Stored in {@code PendingToolRegistry} (service.assistant) between the chat response and a
 * user-issued {@code POST /api/v1/assistant/pending-tools/{id}/confirm} or {@code /reject} call.
 * A DTO because the chat response carries it to the frontend.
 *
 * <p>{@code payloadJson} is the raw JSON body between the {@code <tool>…</tool>} tags —
 * kept as-is so {@code AssistantToolExecutor#executeConfirmed(PendingTool)} can re-parse it
 * with the same sanitization/error handling as inline execution.
 *
 * <p>The frontend confirmation card formulates its text in the UI language from {@code type},
 * {@code subject} (the human name the action is about — workflow name, run's workflow name, TODO
 * title, chain name; the shortened id when it could not be resolved, marked by
 * {@code params.ref = "id"}) and {@code params} (e.g. {@code priority}, {@code status}).
 * {@code description} is the English fallback sentence for older clients and unknown types
 * (TODO 4e152029).
 */
public record PendingTool(
        String id,
        String type,
        String description,
        String subject,
        Map<String, String> params,
        @JsonIgnore String payloadJson,
        Instant expiresAt
) {
    public PendingTool {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    /** Without structured label data — only {@code description} is shown. */
    public PendingTool(String id, String type, String description, String payloadJson, Instant expiresAt) {
        this(id, type, description, null, Map.of(), payloadJson, expiresAt);
    }

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
