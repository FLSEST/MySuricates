package com.automation.service.learning;

import com.automation.dto.projection.RunOverviewCounts;
import com.automation.dto.response.DashboardResponse;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;

import java.time.Instant;
import java.util.List;

/**
 * The all-time figures at the top of the dashboard — workflow and run counts, success rate, runs
 * today, top workflows. Without a project they count everything; with one they count only that
 * project's workflows and runs, like the time-window figures next to them (TODO 4e1c9ad4).
 */
record DashboardOverview(long totalWorkflows, long totalRuns, long successfulRuns, long failedRuns,
                         long runsToday, List<DashboardResponse.TopWorkflowEntry> topWorkflows) {

    static DashboardOverview load(WorkflowRunRepository runs, WorkflowRepository workflows,
                                  String projectId, Instant todayStart) {
        // The run counters come from ONE query instead of five count calls (TODO 2ccb9e40).
        if (projectId == null || projectId.isBlank()) {
            return of(workflows.count(), runs.countOverview(todayStart), runs.findTopWorkflows());
        }
        return of(workflows.countByProjectId(projectId), runs.countOverviewByProject(projectId, todayStart),
                runs.findTopWorkflowsByProject(projectId));
    }

    private static DashboardOverview of(long totalWorkflows, RunOverviewCounts counts, List<Object[]> topRows) {
        return new DashboardOverview(totalWorkflows, counts.total(), counts.succeeded(), counts.failed(),
                counts.startedSince(), toEntries(topRows));
    }

    double successRate() {
        return totalRuns > 0 ? (double) successfulRuns / totalRuns : 0;
    }

    private static List<DashboardResponse.TopWorkflowEntry> toEntries(List<Object[]> rows) {
        return rows.stream()
                .map(row -> new DashboardResponse.TopWorkflowEntry((String) row[0], (String) row[1], (Long) row[2]))
                .toList();
    }
}
