package com.automation.dto.response;

import java.util.List;

/**
 * Ein Ziel mit seinen TODOs in Arbeitsreihenfolge (Rang, dann Prioritaet, dann Alter), dem
 * Fortschritt je Projekt und seinen Meilensteinen (TODO 259f4f66).
 *
 * <p>{@code byProject} leitet die Projekte aus den TODOs ab und bleibt fuer bestehende Aufrufer.
 * {@code projects} (TODO c60bf914) sind die ausdruecklich zugeordneten Projekte in ihrer
 * Reihenfolge, gezaehlt aus den nicht archivierten TODOs des Ziels in diesem Projekt. Die
 * Meilensteine stehen nach Projekt-Position, dann nach ihrer eigenen Position.
 */
public record GoalDetailResponse(
        GoalResponse goal,
        List<GoalTodoItem> todos,
        List<ProjectProgress> byProject,
        List<GoalMilestoneResponse> milestones,
        List<ProjectSummary> projects
) {
    public record ProjectProgress(String projectId, String projectName, long total, long done) {}

    /**
     * Ein Zielprojekt: {@code total}/{@code done} ohne ARCHIVED, {@code openQuestions} zaehlt aktive
     * TODOs (OPEN/IN_PROGRESS/BACKLOG) mit offener Frage.
     */
    public record ProjectSummary(String projectId, String name, boolean archived, int position,
                                 long total, long done, long openQuestions) {}
}
