package com.automation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Ein Lauf, der auf eine Antwort wartet — für den Freigaben-Eingang der UI.
 *
 * @param type          {@code approval} oder {@code human-input}
 * @param question      die aufgelöste Frage bzw. der Prompt (Markdown)
 * @param rejectOptions die {@code options}, die eine Ablehnung sind
 * @param timeoutAt     wann der wartende Block aufgibt; {@code null}, wenn unbekannt
 * @param workflowName  der beim Start des Laufs festgehaltene Name
 * @param fields        die Felder eines Human-Input-Formulars (wie im WebSocket-Ereignis); fehlt in der
 *                      Antwort ohne Formular (TODO ec913801)
 */
public record PendingInputResponse(
        String runId,
        String workflowId,
        String workflowName,
        String type,
        String blockId,
        String question,
        List<String> options,
        List<String> rejectOptions,
        int stepIndex,
        Instant requestedAt,
        Instant timeoutAt,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Map<String, Object>> fields
) {
    /** Ohne Formularfelder – Approvals und einfacher Human-Input. */
    public PendingInputResponse(String runId, String workflowId, String workflowName, String type, String blockId,
                                String question, List<String> options, List<String> rejectOptions, int stepIndex,
                                Instant requestedAt, Instant timeoutAt) {
        this(runId, workflowId, workflowName, type, blockId, question, options, rejectOptions, stepIndex,
                requestedAt, timeoutAt, null);
    }
}
