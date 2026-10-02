package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse.RecentRunEntry;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;

import java.util.List;

/**
 * The dashboard's "recent runs" from the runs its time window has already loaded (workflow and
 * project fetched along, newest first) — no query of its own and no steps (TODO 2ccb9e40). The
 * widget shows status, name, project, duration and start; the full run is on its detail page.
 */
final class DashboardRecentRuns {

    static final int LIMIT = 10;

    private DashboardRecentRuns() {}

    static List<RecentRunEntry> of(List<WorkflowRun> windowNewestFirst) {
        return windowNewestFirst.stream().limit(LIMIT).map(DashboardRecentRuns::entry).toList();
    }

    private static RecentRunEntry entry(WorkflowRun run) {
        Workflow workflow = run.getWorkflow();
        String snapshot = run.getWorkflowName();
        String name = snapshot != null && !snapshot.isBlank() ? snapshot
                : workflow != null ? workflow.getName() : "(deleted)";
        String projectName = workflow != null && workflow.getProject() != null ? workflow.getProject().getName() : null;
        return new RecentRunEntry(run.getId(), workflow != null ? workflow.getId() : null, name, projectName,
                run.getStatus(), run.getStartedAt(), run.getCompletedAt(), run.getDurationMs());
    }
}
