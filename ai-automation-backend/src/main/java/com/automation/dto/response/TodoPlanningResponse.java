package com.automation.dto.response;

import com.automation.entity.Todo;

import java.time.Instant;

/** Goal and estimate of a TODO (TODO dc0566f8); {@code goalTitle} for display. */
public record TodoPlanningResponse(
        String goalId,
        String goalTitle,
        Integer goalRank,
        Todo.Complexity complexity,
        String suggestedEffort,
        String ratingNote,
        Todo.RatedBy ratedBy,
        Instant ratedAt,
        String openQuestion,
        /** Work package in the goal (TODO c60bf914, until V92 {@code milestoneId}); null = none. */
        String workPackageId,
        /** Decision, when and by whom (TODO 68a5e24e); null = none. */
        String decision,
        Instant decidedAt,
        Todo.RatedBy decidedBy,
        /** QUESTION (open question), DECIDED (decision, no open question) or NONE. */
        Todo.DecisionState decisionState
) {}
