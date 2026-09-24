package com.automation.dto.request;

/** Teilaenderung eines Meilensteins (TODO 259f4f66): {@code null} laesst ein Feld stehen. */
public record UpdateGoalMilestoneRequest(
        String title,
        String dueDate
) {}
