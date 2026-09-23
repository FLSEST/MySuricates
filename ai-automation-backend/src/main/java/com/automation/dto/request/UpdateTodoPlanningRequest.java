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
 */
public record UpdateTodoPlanningRequest(
        String goalId,
        Integer goalRank,
        String complexity,
        String suggestedEffort,
        String ratingNote,
        String openQuestion,
        Todo.RatedBy ratedBy
) {}
