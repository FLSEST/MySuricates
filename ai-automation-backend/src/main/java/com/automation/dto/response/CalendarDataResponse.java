package com.automation.dto.response;

import java.util.List;

/**
 * The calendar of a period: the upcoming appointments and the past runs.
 *
 * <p>{@code pastRuns} are list rows without steps. With {@link WorkflowRunResponse} the
 * calendar carried every step output of all runs in the period: 3 697 137 bytes for one week of
 * 60 runs in RequestCostRatchetTest, although the calendar only shows name, status and start time
 * (TODO 168e3d7f).
 *
 * <p>{@code pastRuns} are the newest runs of the period up to the request's limit,
 * {@code pastRunsTotal} all of them (TODO 86777fb4): a month with a schedule every five minutes
 * is 8 640 runs.
 */
public record CalendarDataResponse(
        List<ScheduledWorkflowResponse> upcoming,
        List<WorkflowRunListItemResponse> pastRuns,
        long pastRunsTotal
) {
    /** All runs of the period in {@code pastRuns}, none truncated. */
    public CalendarDataResponse(List<ScheduledWorkflowResponse> upcoming, List<WorkflowRunListItemResponse> pastRuns) {
        this(upcoming, pastRuns, pastRuns == null ? 0 : pastRuns.size());
    }
}
