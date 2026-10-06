package com.automation.dto.projection;

import com.automation.entity.Todo;

import java.time.Instant;

/**
 * Lean row of a TODO in the goal view (TODO dc0566f8): everything for order,
 * estimate and progress, but without the description – it can be 30,000 characters long.
 */
public record GoalTodoRow(
        String id,
        String title,
        Todo.Status status,
        Todo.Priority priority,
        String projectId,
        String tags,
        Integer goalRank,
        Todo.Complexity complexity,
        String suggestedEffort,
        String ratingNote,
        Todo.RatedBy ratedBy,
        Instant ratedAt,
        String openQuestion,
        Instant createdAt,
        Instant updatedAt,
        Instant dueDate,
        String workPackageId,
        /** Decision that answered an open question (TODO 68a5e24e); null = none. */
        String decision,
        Instant decidedAt,
        Todo.RatedBy decidedBy
) {
    /** Form ohne Entscheidung – fuer Aufrufer von vorher. */
    public GoalTodoRow(String id, String title, Todo.Status status, Todo.Priority priority, String projectId, String tags,
                       Integer goalRank, Todo.Complexity complexity, String suggestedEffort, String ratingNote,
                       Todo.RatedBy ratedBy, Instant ratedAt, String openQuestion, Instant createdAt, Instant updatedAt,
                       Instant dueDate, String workPackageId) {
        this(id, title, status, priority, projectId, tags, goalRank, complexity, suggestedEffort, ratingNote, ratedBy,
                ratedAt, openQuestion, createdAt, updatedAt, dueDate, workPackageId, null, null, null);
    }

    public Todo.DecisionState decisionState() {
        return Todo.DecisionState.of(openQuestion, decision);
    }
}
