package com.automation.dto.response;

import com.automation.entity.GoalMilestone;

import java.time.Instant;

/**
 * Ein Meilenstein mit berechnetem Zustand (TODO 259f4f66). {@code total}/{@code done} zaehlen die
 * zugeordneten TODOs ohne ARCHIVED. {@code reopened}: war schon einmal erreicht
 * ({@code reachedAt} gesetzt), ist es jetzt aber nicht mehr – etwa weil ein neues TODO dazukam.
 */
public record GoalMilestoneResponse(
        String id,
        String goalId,
        String title,
        Instant dueDate,
        GoalMilestone.State state,
        long total,
        long done,
        Instant reachedAt,
        boolean reopened,
        Instant createdAt,
        Instant updatedAt
) {}
