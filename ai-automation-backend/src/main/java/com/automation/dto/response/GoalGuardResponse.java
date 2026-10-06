package com.automation.dto.response;

import java.time.Instant;

/**
 * State of the goal guard (TODO 259f4f66). {@code enabled} means its schedule is active.
 * When switched off the workflow remains ({@code workflowId}); only the schedule is paused.
 */
public record GoalGuardResponse(
        boolean enabled,
        String workflowId,
        String scheduleId,
        Instant nextRunAt
) {
    public static final GoalGuardResponse NONE = new GoalGuardResponse(false, null, null, null);
}
