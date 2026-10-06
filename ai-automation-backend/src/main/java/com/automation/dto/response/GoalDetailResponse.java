package com.automation.dto.response;

import java.util.List;

/**
 * A goal with its TODOs in working order (rank, then priority, then age), the
 * progress per project, its work packages and milestones (TODO 259f4f66, c60bf914).
 *
 * <p>{@code byProject} derives the projects from the TODOs and stays for existing callers.
 * {@code projects} are the explicitly assigned projects in their order, counted from
 * the goal's non-archived TODOs in this project. {@code workPackages} are sorted by
 * project position, then by their own position; {@code milestones} by date, then title.
 */
public record GoalDetailResponse(
        GoalResponse goal,
        List<GoalTodoItem> todos,
        List<ProjectProgress> byProject,
        List<ProjectSummary> projects,
        List<GoalWorkPackageResponse> workPackages,
        List<GoalMilestoneResponse> milestones
) {
    public record ProjectProgress(String projectId, String projectName, long total, long done) {}

    /**
     * A goal project: {@code total}/{@code done} without ARCHIVED, {@code openQuestions} counts active
     * TODOs (OPEN/IN_PROGRESS/BACKLOG) with an open question, {@code decided} the non-archived ones with
     * a decision and without an open question (TODO 68a5e24e).
     */
    public record ProjectSummary(String projectId, String name, boolean archived, int position,
                                 long total, long done, long openQuestions, long decided) {}
}
