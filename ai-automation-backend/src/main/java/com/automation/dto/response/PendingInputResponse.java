package com.automation.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Ein Lauf, der auf eine Antwort wartet — für den Freigaben-Eingang der UI.
 *
 * @param type          {@code approval} oder {@code human-input}
 * @param question      die aufgelöste Frage bzw. der Prompt (Markdown)
 * @param rejectOptions die {@code options}, die eine Ablehnung sind
 * @param timeoutAt     wann der wartende Block aufgibt; {@code null}, wenn unbekannt
 * @param workflowName  der beim Start des Laufs festgehaltene Name
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
        Instant timeoutAt
) {}
