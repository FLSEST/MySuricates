package com.automation.dto.response;

import com.automation.entity.Todo;

import java.time.Instant;
import java.util.List;

/** A TODO in the goal view - without description (TODO dc0566f8). */
public record GoalTodoItem(
        String id,
        String title,
        Todo.Status status,
        Todo.Priority priority,
        String projectId,
        String projectName,
        List<String> tags,
        Integer goalRank,
        Todo.Complexity complexity,
        String suggestedEffort,
        String ratingNote,
        Todo.RatedBy ratedBy,
        Instant ratedAt,
        String openQuestion,
        Instant updatedAt,
        /** Due date of the TODO (TODO 259f4f66); null = unplanned. */
        Instant dueDate,
        /** Work package of the goal the TODO is assigned to (TODO c60bf914); null = none. */
        String workPackageId,
        /** Decision, when and by whom (TODO 68a5e24e); null = none. */
        String decision,
        Instant decidedAt,
        Todo.RatedBy decidedBy,
        /** QUESTION (open question), DECIDED (decision, no open question) or NONE. */
        Todo.DecisionState decisionState
) {}
