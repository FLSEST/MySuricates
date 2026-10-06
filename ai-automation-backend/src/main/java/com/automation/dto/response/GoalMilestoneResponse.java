package com.automation.dto.response;

import com.automation.entity.GoalDueState;

import java.time.Instant;
import java.util.List;

/**
 * A milestone - a deadline for the whole goal - with computed state (TODO c60bf914).
 * With linked work packages ({@code workPackageIds}) it is REACHED once all are completed
 * ({@code packagesDone == packagesTotal}); without packages, when it was marked by hand.
 * {@code reopened}: was reached once ({@code reachedAt} set), but the packages no longer are
 * now.
 */
public record GoalMilestoneResponse(
        String id,
        String goalId,
        String title,
        Instant dueDate,
        GoalDueState state,
        List<String> workPackageIds,
        long packagesTotal,
        long packagesDone,
        Instant reachedAt,
        boolean reopened,
        Instant createdAt,
        Instant updatedAt
) {}
