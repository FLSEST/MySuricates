package com.automation.dto.projection;

import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;

import java.util.List;

/** Ein Ziel mit seinen Meilensteinen aus einer Abfrage (TODO 259f4f66). */
public record GoalWithMilestones(Goal goal, List<GoalMilestone> milestones) {}
