package com.automation.dto.response;

import java.util.List;

/**
 * Ein Ziel mit seinen TODOs in Arbeitsreihenfolge (Rang, dann Prioritaet, dann Alter), dem
 * Fortschritt je Projekt und seinen Meilensteinen nach Datum (TODO 259f4f66).
 */
public record GoalDetailResponse(
        GoalResponse goal,
        List<GoalTodoItem> todos,
        List<ProjectProgress> byProject,
        List<GoalMilestoneResponse> milestones
) {
    public record ProjectProgress(String projectId, String projectName, long total, long done) {}
}
