package com.automation.dto.projection;

import com.automation.entity.GoalMilestone;

import java.util.List;

/** A milestone with the IDs of its linked work packages, from one query (TODO c60bf914). */
public record GoalMilestoneLinks(GoalMilestone milestone, List<String> workPackageIds) {}
