package com.automation.dto.request;

import com.automation.entity.Todo;
import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Placement of a TODO in a goal and its estimate ({@code PATCH /todos/{id}/planning},
 * TODO dc0566f8).
 *
 * <p>{@code null} leaves a field as it is, an empty text clears it: {@code goalId = ""} detaches the
 * TODO from the goal, {@code openQuestion = ""} marks the question as resolved. {@code goalRank <= 0}
 * withdraws the rank. {@code complexity} is {@code S|M|L|XL}, {@code suggestedEffort}
 * {@code low|medium|high|xhigh}. {@code ratedBy} missing = {@code USER}; the plugin sends {@code AI}.
 *
 * <p>{@code workPackageId} (TODO c60bf914; until V92 the field was called {@code milestoneId}, the old name
 * is still accepted) assigns the TODO to a work package of its goal AND its project,
 * {@code ""} detaches it. A package of another goal or project is a 400. If the TODO changes or
 * leaves its goal, it loses the package.
 *
 * <p>{@code decision} (TODO 68a5e24e) records the decision that answers an open question:
 * text, timestamp, who ({@code ratedBy}, otherwise USER); it clears {@code openQuestion}. {@code ""} withdraws
 * it. A non-empty new question and a non-empty decision at the same time are a 400.
 */
public record UpdateTodoPlanningRequest(
        String goalId,
        Integer goalRank,
        String complexity,
        String suggestedEffort,
        String ratingNote,
        String openQuestion,
        Todo.RatedBy ratedBy,
        @JsonAlias("milestoneId") String workPackageId,
        String decision
) {
    /** Form ohne {@code workPackageId} – fuer Aufrufer von vorher. */
    public UpdateTodoPlanningRequest(String goalId, Integer goalRank, String complexity, String suggestedEffort,
                                     String ratingNote, String openQuestion, Todo.RatedBy ratedBy) {
        this(goalId, goalRank, complexity, suggestedEffort, ratingNote, openQuestion, ratedBy, null, null);
    }

    /** Form ohne {@code decision} – fuer Aufrufer von vorher. */
    public UpdateTodoPlanningRequest(String goalId, Integer goalRank, String complexity, String suggestedEffort,
                                     String ratingNote, String openQuestion, Todo.RatedBy ratedBy, String workPackageId) {
        this(goalId, goalRank, complexity, suggestedEffort, ratingNote, openQuestion, ratedBy, workPackageId, null);
    }
}
