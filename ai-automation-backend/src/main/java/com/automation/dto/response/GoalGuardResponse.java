package com.automation.dto.response;

import java.time.Instant;

/**
 * Zustand des Ziel-Waechters (TODO 259f4f66). {@code enabled} heisst: sein Zeitplan ist aktiv.
 * Ausgeschaltet bleibt der Workflow bestehen ({@code workflowId}), nur der Zeitplan pausiert.
 */
public record GoalGuardResponse(
        boolean enabled,
        String workflowId,
        String scheduleId,
        Instant nextRunAt
) {
    public static final GoalGuardResponse NONE = new GoalGuardResponse(false, null, null, null);
}
