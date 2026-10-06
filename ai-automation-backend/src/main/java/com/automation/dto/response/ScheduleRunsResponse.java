package com.automation.dto.response;

import java.util.List;

/**
 * The runs of a schedule: the newest workflow and chain runs, each at most as many as the
 * request's limit, and how many there are in total (TODO 86777fb4). A schedule every five minutes
 * collects ~26 000 runs in 90 days; before, all of them came. {@code workflowRuns} are list rows without
 * steps (TODO 168e3d7f).
 *
 * @param workflowRunsTotal all workflow runs of the schedule; greater than {@code workflowRuns.size()}
 *                          when the limit applied
 * @param chainRunsTotal    the same for chain runs
 */
public record ScheduleRunsResponse(
        List<WorkflowRunListItemResponse> workflowRuns,
        List<ChainRunResponse> chainRuns,
        long workflowRunsTotal,
        long chainRunsTotal
) {}
