package com.automation.dto.projection;

import com.automation.entity.GoalMilestone;

import java.util.List;

/** Ein Meilenstein mit den IDs seiner verknuepften Arbeitspakete, aus einer Abfrage (TODO c60bf914). */
public record GoalMilestoneLinks(GoalMilestone milestone, List<String> workPackageIds) {}
