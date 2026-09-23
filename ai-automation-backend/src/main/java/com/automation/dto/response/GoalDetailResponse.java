package com.automation.dto.response;

import java.util.List;

/**
 * Ein Ziel mit seinen TODOs in Arbeitsreihenfolge (Rang, dann Prioritaet, dann Alter) und dem
 * Fortschritt je Projekt.
 */
public record GoalDetailResponse(
        GoalResponse goal,
        List<GoalTodoItem> todos,
        List<ProjectProgress> byProject
) {
    public record ProjectProgress(String projectId, String projectName, long total, long done) {}
}
