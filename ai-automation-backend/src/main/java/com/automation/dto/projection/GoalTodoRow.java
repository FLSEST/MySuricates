package com.automation.dto.projection;

import com.automation.entity.Todo;

import java.time.Instant;

/**
 * Schlanke Zeile eines TODOs in der Zielansicht (TODO dc0566f8): alles fuer Reihenfolge,
 * Einschaetzung und Fortschritt, aber ohne die Beschreibung – die kann 30 000 Zeichen lang sein.
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
        /** Entscheidung, die eine offene Frage beantwortet hat (TODO 68a5e24e); null = keine. */
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
