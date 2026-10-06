package com.automation.dto.response;

import com.automation.entity.Goal;
import com.automation.entity.GoalDueState;

import java.time.Instant;
import java.util.List;

public record GoalResponse(
        String id,
        String title,
        String description,
        Goal.Status status,
        Instant targetDate,
        Instant createdAt,
        Instant updatedAt,
        GoalProgressResponse progress,
        /** Goal guard (TODO 259f4f66); never null - {@code enabled=false} without workflow as long as it was never enabled. */
        GoalGuardResponse guard,
        /** The explicitly assigned projects in their order (TODO c60bf914); never null. */
        List<ProjectRef> projects,
        /** The next milestone not yet reached, by date (TODO c60bf914); null = none. */
        NextMilestone nextMilestone
) {
    /** A goal project in the goal list: only ID and name. */
    public record ProjectRef(String id, String name) {}

    /** The next milestone for the goal card. */
    public record NextMilestone(String id, String title, Instant dueDate, GoalDueState state) {}
}
