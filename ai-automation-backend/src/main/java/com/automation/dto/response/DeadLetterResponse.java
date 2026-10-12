package com.automation.dto.response;

import com.automation.entity.DeadLetterEntry;

import java.time.Instant;

/**
 * A dead letter entry as the API returns it — the same fields the entity used to serialize, decoupled from the
 * table so a schema change no longer changes the contract silently.
 */
public record DeadLetterResponse(
        String id,
        String workflowRunId,
        String workflowId,
        String workflowName,
        String errorMessage,
        Integer failedStepIndex,
        int retryCount,
        Instant lastFailureAt,
        DeadLetterEntry.Status status,
        Instant createdAt,
        Instant updatedAt
) {}
