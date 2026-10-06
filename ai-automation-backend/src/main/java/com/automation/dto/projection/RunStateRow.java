package com.automation.dto.projection;

import com.automation.entity.RunStatus;

import java.time.Instant;

/**
 * State of a run without steps, context and metadata (TODO c6ea8f5c) – for cards that only
 * need to know whether a run ran, succeeded or failed, and which workflow it belongs to.
 */
public record RunStateRow(
        String id,
        String workflowId,
        String workflowName,
        RunStatus status,
        Instant startedAt,
        Instant completedAt
) {}
