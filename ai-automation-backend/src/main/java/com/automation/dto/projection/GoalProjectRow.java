package com.automation.dto.projection;

import java.time.Instant;

/**
 * Ein ausdruecklich zugeordnetes Projekt eines Ziels mit Name und Archivzeitpunkt, aus einer
 * Abfrage ueber {@code goal_projects} und {@code projects} (TODO c60bf914).
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
