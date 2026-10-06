package com.automation.dto.projection;

import com.automation.entity.GoalDueState;

import java.time.Instant;

/**
 * A milestone without its links, but with package counters from subqueries
 * (TODO c60bf914): {@code packagesTotal} linked work packages, {@code packagesDone} of them
 * completed. Enough for the state – for the goal list and „Braucht dich“.
 */
public record GoalMilestoneSummaryRow(
        String id,
        String goalId,
        String title,
        Instant dueDate,
        Instant reachedAt,
        long packagesTotal,
        long packagesDone
) {
    /** Reached with packages when all are completed; without packages when marked by hand. */
    public boolean reached() {
        return packagesTotal > 0 ? packagesDone >= packagesTotal : reachedAt != null;
    }

    public GoalDueState state(Instant now) {
        return GoalDueState.of(dueDate, reached(), now);
    }
}
