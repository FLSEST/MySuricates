package com.automation.service.assistant;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * A tool call that the assistant has proposed but not yet executed.
 * Stored in {@link PendingToolRegistry} between the chat response and a user-issued
 * {@code POST /api/v1/assistant/pending-tools/{id}/confirm} or {@code /reject} call.
 *
 * <p>{@code payloadJson} is the raw JSON body between the {@code <tool>…</tool>} tags —
 * kept as-is so {@link AssistantToolExecutor#executeConfirmed(PendingTool)} can re-parse it
 * with the same sanitization/error handling as inline execution.
 *
 * <p>{@code description} is a human-readable summary rendered in the frontend confirmation card.
 */
public record PendingTool(
        String id,
        String type,
        String description,
        @JsonIgnore String payloadJson,
        Instant expiresAt
) {
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
