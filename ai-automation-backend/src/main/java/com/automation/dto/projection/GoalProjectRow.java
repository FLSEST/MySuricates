package com.automation.dto.projection;

import java.time.Instant;

/**
 * An explicitly assigned project of a goal with name and archive timestamp, from a
 * query over {@code goal_projects} and {@code projects} (TODO c60bf914).
 */
public record GoalProjectRow(
        String goalId,
        String projectId,
        String projectName,
        Instant archivedAt,
        int position
) {
    public boolean archived() {
        return archivedAt != null;
    }
}
