package com.automation.dto.projection;

import com.automation.entity.Goal;
import com.automation.entity.GoalWorkPackage;

import java.util.List;
import java.util.Map;

/**
 * A goal with its work packages from one query (TODO 259f4f66, c60bf914).
 * {@code projectPositions}: position of the packages' projects in the goal – from the same query, for
 * sorting; a project that is not assigned to the goal is missing.
 */
public record GoalWithWorkPackages(Goal goal, List<GoalWorkPackage> workPackages, Map<String, Integer> projectPositions) {

    public GoalWithWorkPackages(Goal goal, List<GoalWorkPackage> workPackages) {
        this(goal, workPackages, Map.of());
    }
}
