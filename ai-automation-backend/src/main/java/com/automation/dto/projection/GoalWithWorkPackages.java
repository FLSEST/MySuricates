package com.automation.dto.projection;

import com.automation.entity.Goal;
import com.automation.entity.GoalWorkPackage;

import java.util.List;
import java.util.Map;

/**
 * Ein Ziel mit seinen Arbeitspaketen aus einer Abfrage (TODO 259f4f66, c60bf914).
 * {@code projectPositions}: Position der Projekte der Pakete im Ziel – aus derselben Abfrage, fuer die
 * Sortierung; ein Projekt, das dem Ziel nicht zugeordnet ist, fehlt.
 */
public record GoalWithWorkPackages(Goal goal, List<GoalWorkPackage> workPackages, Map<String, Integer> projectPositions) {

    public GoalWithWorkPackages(Goal goal, List<GoalWorkPackage> workPackages) {
        this(goal, workPackages, Map.of());
    }
}
