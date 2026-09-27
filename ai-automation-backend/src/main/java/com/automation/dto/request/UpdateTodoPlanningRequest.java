package com.automation.dto.request;

import com.automation.entity.Todo;
import com.fasterxml.jackson.annotation.JsonAlias;

/**
 * Einordnung eines TODOs in ein Ziel und seine Einschaetzung ({@code PATCH /todos/{id}/planning},
 * TODO dc0566f8).
 *
 * <p>{@code null} laesst ein Feld stehen, ein leerer Text leert es: {@code goalId = ""} loest das
 * TODO vom Ziel, {@code openQuestion = ""} markiert die Frage als geklaert. {@code goalRank <= 0}
 * nimmt den Rang zurueck. {@code complexity} ist {@code S|M|L|XL}, {@code suggestedEffort}
 * {@code low|medium|high|xhigh}. {@code ratedBy} fehlt = {@code USER}; das Plugin schickt {@code AI}.
 *
 * <p>{@code workPackageId} (TODO c60bf914; bis V92 hiess das Feld {@code milestoneId}, der alte Name
 * wird weiter angenommen) ordnet das TODO einem Arbeitspaket seines Ziels UND seines Projekts zu,
 * {@code ""} loest es davon. Ein Paket eines anderen Ziels oder Projekts ist ein 400. Wechselt oder
 * verlaesst das TODO sein Ziel, verliert es das Paket.
 */
public record UpdateTodoPlanningRequest(
        String goalId,
        Integer goalRank,
        String complexity,
        String suggestedEffort,
        String ratingNote,
        String openQuestion,
        Todo.RatedBy ratedBy,
        @JsonAlias("milestoneId") String workPackageId
) {
    /** Form ohne {@code workPackageId} – fuer Aufrufer von vorher. */
    public UpdateTodoPlanningRequest(String goalId, Integer goalRank, String complexity, String suggestedEffort,
                                     String ratingNote, String openQuestion, Todo.RatedBy ratedBy) {
        this(goalId, goalRank, complexity, suggestedEffort, ratingNote, openQuestion, ratedBy, null);
    }
}
