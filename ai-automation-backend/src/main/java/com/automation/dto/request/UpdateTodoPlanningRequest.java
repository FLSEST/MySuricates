package com.automation.dto.request;

import com.automation.entity.Todo;

/**
 * Einordnung eines TODOs in ein Ziel und seine Einschaetzung ({@code PATCH /todos/{id}/planning},
 * TODO dc0566f8).
 *
 * <p>{@code null} laesst ein Feld stehen, ein leerer Text leert es: {@code goalId = ""} loest das
 * TODO vom Ziel, {@code openQuestion = ""} markiert die Frage als geklaert. {@code goalRank <= 0}
 * nimmt den Rang zurueck. {@code complexity} ist {@code S|M|L|XL}, {@code suggestedEffort}
 * {@code low|medium|high|xhigh}. {@code ratedBy} fehlt = {@code USER}; das Plugin schickt {@code AI}.
 *
 * <p>{@code milestoneId} (TODO 259f4f66) ordnet das TODO einem Meilenstein seines Ziels zu,
 * {@code ""} loest es davon. Ein Meilenstein eines anderen Ziels ist ein 400. Wechselt oder verlaesst
 * das TODO sein Ziel, verliert es den Meilenstein.
 */
public record UpdateTodoPlanningRequest(
        String goalId,
        Integer goalRank,
        String complexity,
        String suggestedEffort,
        String ratingNote,
        String openQuestion,
        Todo.RatedBy ratedBy,
        String milestoneId
) {
    /** Form ohne {@code milestoneId} – fuer Aufrufer von vorher. */
    public UpdateTodoPlanningRequest(String goalId, Integer goalRank, String complexity, String suggestedEffort,
                                     String ratingNote, String openQuestion, Todo.RatedBy ratedBy) {
        this(goalId, goalRank, complexity, suggestedEffort, ratingNote, openQuestion, ratedBy, null);
    }
}
