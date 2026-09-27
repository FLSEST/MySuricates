package com.automation.dto.response;

import com.automation.entity.Goal;

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
        /** Ziel-Waechter (TODO 259f4f66); nie null – {@code enabled=false} ohne Workflow, solange nie eingeschaltet. */
        GoalGuardResponse guard,
        /** Die ausdruecklich zugeordneten Projekte in ihrer Reihenfolge (TODO c60bf914); nie null. */
        List<ProjectRef> projects
) {
    /** Ein Zielprojekt in der Zielliste: nur ID und Name. */
    public record ProjectRef(String id, String name) {}
}
