package com.automation.dto.response;

import com.automation.entity.Goal;

import java.time.Instant;

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
        GoalGuardResponse guard
) {}
