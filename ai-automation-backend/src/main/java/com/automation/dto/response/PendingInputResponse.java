package com.automation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A run waiting for an answer - for the UI's approvals inbox.
 *
 * @param type          {@code approval} or {@code human-input}
 * @param question      the resolved question or prompt (Markdown)
 * @param rejectOptions the {@code options} that are a rejection
 * @param timeoutAt     when the waiting block gives up; {@code null} if unknown
 * @param workflowName  the name recorded when the run started
 * @param fields        the fields of a human-input form (as in the WebSocket event); absent from the
 *                      response without a form (TODO ec913801)
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
    /** Without form fields - approvals and simple human input. */
    public PendingInputResponse(String runId, String workflowId, String workflowName, String type, String blockId,
                                String question, List<String> options, List<String> rejectOptions, int stepIndex,
                                Instant requestedAt, Instant timeoutAt) {
        this(runId, workflowId, workflowName, type, blockId, question, options, rejectOptions, stepIndex,
                requestedAt, timeoutAt, null);
    }
}
