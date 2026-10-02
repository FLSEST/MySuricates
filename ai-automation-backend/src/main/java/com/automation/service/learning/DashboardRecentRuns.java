package com.automation.service.learning;

import com.automation.dto.projection.DashboardRunRow;
import com.automation.dto.response.DashboardResponse.RecentRunEntry;

import java.util.List;

/**
 * The dashboard's "recent runs" from the run rows its time window has already loaded (newest
 * first) — no query of its own and no steps (TODO 2ccb9e40). The
 * widget shows status, name, project, duration and start; the full run is on its detail page.
 */
final class DashboardRecentRuns {

    static final int LIMIT = 10;

    private DashboardRecentRuns() {}

    static List<RecentRunEntry> of(List<DashboardRunRow> windowNewestFirst) {
        return windowNewestFirst.stream().limit(LIMIT).map(DashboardRecentRuns::entry).toList();
    }

    private static RecentRunEntry entry(DashboardRunRow run) {
        String snapshot = run.workflowNameSnapshot();
        String name = snapshot != null && !snapshot.isBlank() ? snapshot
                : run.workflowName() != null ? run.workflowName() : "(deleted)";
        return new RecentRunEntry(run.id(), run.workflowId(), name, run.projectName(),
                run.status(), run.startedAt(), run.completedAt(), run.durationMs());
    }
}
