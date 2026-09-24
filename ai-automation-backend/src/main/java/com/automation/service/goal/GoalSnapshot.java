package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.dto.response.GoalProgressResponse;
import com.automation.entity.Goal;

import java.util.List;

/**
 * Ein Ziel mit allem, was ein Blick darauf braucht (TODO 259f4f66): TODO-Zeilen (ohne
 * Beschreibung), Meilensteine mit Zustand und Fortschritt. {@code goal} ist nach dem Lesen
 * losgeloest – nur seine einfachen Felder sind gedacht, nicht {@code milestones}.
 */
public record GoalSnapshot(
        Goal goal,
        List<GoalTodoRow> rows,
        List<GoalMilestoneResponse> milestones,
        GoalProgressResponse progress
) {}
