package com.automation.dto.projection;

import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;

import java.util.List;
import java.util.Map;

/**
 * Ein Ziel mit seinen Meilensteinen aus einer Abfrage (TODO 259f4f66). {@code projectPositions}:
 * Position der Projekte der Meilensteine im Ziel (TODO c60bf914) – aus derselben Abfrage, fuer die
 * Sortierung; ein Projekt, das dem Ziel nicht zugeordnet ist, fehlt.
 */
public record GoalWithMilestones(Goal goal, List<GoalMilestone> milestones, Map<String, Integer> projectPositions) {

    public GoalWithMilestones(Goal goal, List<GoalMilestone> milestones) {
        this(goal, milestones, Map.of());
    }
}
