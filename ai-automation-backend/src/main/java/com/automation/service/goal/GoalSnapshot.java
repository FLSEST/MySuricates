package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.dto.response.GoalProgressResponse;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.entity.Goal;

import java.util.List;

/**
 * A goal with everything a look at it needs (TODO 259f4f66, c60bf914): TODO rows (without
 * description), work packages and milestones with state and progress. {@code goal} is detached after
 * reading - only its simple fields are meant to be used.
 */
public record GoalSnapshot(
        Goal goal,
        List<GoalTodoRow> rows,
        List<GoalWorkPackageResponse> workPackages,
        List<GoalMilestoneResponse> milestones,
        GoalProgressResponse progress
) {}
