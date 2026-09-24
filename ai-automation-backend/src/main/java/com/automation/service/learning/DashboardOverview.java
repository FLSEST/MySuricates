package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse;
import com.automation.entity.RunStatus;
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
        if (projectId == null || projectId.isBlank()) {
            return new DashboardOverview(workflows.count(), runs.count(),
                    runs.countByStatus(RunStatus.COMPLETED) + runs.countByStatus(RunStatus.COMPLETED_WITH_ERRORS),
                    runs.countByStatus(RunStatus.FAILED), runs.countByStartedAtAfter(todayStart),
                    toEntries(runs.findTopWorkflows()));
        }
        return new DashboardOverview(workflows.countByProjectId(projectId), runs.countByProject(projectId),
                runs.countByProjectAndStatus(projectId, RunStatus.COMPLETED)
                        + runs.countByProjectAndStatus(projectId, RunStatus.COMPLETED_WITH_ERRORS),
                runs.countByProjectAndStatus(projectId, RunStatus.FAILED),
                runs.countByProjectAndStartedAtAfter(projectId, todayStart),
                toEntries(runs.findTopWorkflowsByProject(projectId)));
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
