package com.automation.mapper;

import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.scheduler.ScheduleSkipPolicy;
import com.automation.service.scheduler.SchedulerPause;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Schedules for the views. {@code nextRunAt} is the run the scheduler will actually start: during
 * an active skip-until pause a stored next run inside the pause is replaced by the first one after
 * it and {@code pausedUntil} is set (TODO a776fd2a); while the maintenance switch is on there is
 * none and {@code maintenance} is set (TODO 7704b1e9) — list, calendar and workflow page read it.
 */
@Component
public class ScheduleMapper {

    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;
    private final ScheduleSkipPolicy skipPolicy;
    private final Clock clock;

    public ScheduleMapper(WorkflowRunRepository workflowRunRepository,
                          ChainRunRepository chainRunRepository,
                          ScheduleSkipPolicy skipPolicy,
                          Clock clock) {
        this.workflowRunRepository = workflowRunRepository;
        this.chainRunRepository = chainRunRepository;
        this.skipPolicy = skipPolicy;
        this.clock = clock;
    }

    /**
     * A single schedule — for the detail route, where two queries are two queries.
     *
     * <p>For lists use {@link #toResponseList(List)}: this method resolves the last
     * run status per call, which is an N+1 in a list (TODO 172a6a67).
     */
    public ScheduledWorkflowResponse toResponse(ScheduledWorkflow s) {
        return toResponse(s, null, activePause());
    }

    private SchedulerPause activePause() {
        return skipPolicy.activePause(Instant.now(clock));
    }

    /**
     * @param lastStatusByScheduleId statuses per schedule id collected up front, or {@code null} for
     *     single resolution. A schedule without an entry had no run — that is not a
     *     missing value but {@code null} as the status, exactly as before.
     * @param pause maintenance mode and active skip-until pause, determined once per call
     */
    private ScheduledWorkflowResponse toResponse(ScheduledWorkflow s,
                                                 Map<String, RunStatus> lastStatusByScheduleId,
                                                 SchedulerPause pause) {
        String workflowId = null;
        String workflowName = null;
        String projectName = null;
        String chainId = null;
        String chainName = null;

        if (s.getTargetType() == TargetType.CHAIN && s.getChain() != null) {
            chainId = s.getChain().getId();
            chainName = s.getChain().getName();
        } else if (s.getWorkflow() != null) {
            workflowId = s.getWorkflow().getId();
            workflowName = s.getWorkflow().getName();
            if (s.getWorkflow().getProject() != null) {
                projectName = s.getWorkflow().getProject().getName();
            }
        }

        RunStatus lastRunStatus = lastStatusByScheduleId != null
                ? lastStatusByScheduleId.get(s.getId())
                : resolveLastRunStatus(s);

        return new ScheduledWorkflowResponse(
                s.getId(),
                workflowId,
                workflowName,
                projectName,
                chainId,
                chainName,
                s.getTargetType().name(),
                s.getCronExpression(),
                s.getScheduleType().name(),
                s.getScheduledAt(),
                s.getDelayExpression(),
                s.isEnabled(),
                s.getDescription(),
                s.getInputContext(),
                s.getLastRunAt(),
                skipPolicy.effectiveNextRun(s, pause),
                s.getCreatedAt(),
                lastRunStatus,
                s.getEndDate(),
                s.getMaxRuns(),
                s.getRunCount(),
                s.getConflictBehavior() != null ? s.getConflictBehavior().name() : "SKIP",
                s.isCatchUpOnResume(),
                skipPolicy.heldBySkipUntil(s, pause.skipUntil()) ? pause.skipUntil().get() : null,
                skipPolicy.heldByMaintenance(s, pause)
        );
    }

    /** Only the newest status — one row, one column (TODO 86286572). */
    private static final Pageable NEWEST_ONLY = PageRequest.of(0, 1);

    /**
     * Last run status of a single schedule. Reads at most one status row per run kind
     * instead of loading the schedule's complete run list as entities (TODO 86286572).
     */
    private RunStatus resolveLastRunStatus(ScheduledWorkflow s) {
        List<RunStatus> workflowStatus = workflowRunRepository.findStatusesByScheduleIdNewestFirst(s.getId(), NEWEST_ONLY);
        if (!workflowStatus.isEmpty()) {
            return workflowStatus.get(0);
        }
        List<RunStatus> chainStatus = chainRunRepository.findStatusesByScheduleIdNewestFirst(s.getId(), NEWEST_ONLY);
        return chainStatus.isEmpty() ? null : chainStatus.get(0);
    }

    /**
     * List with bundled status resolution: two queries for all schedules instead of two per
     * schedule (TODO 172a6a67, pattern like {@code WorkflowMapper.toResponseListWithRunCounts}).
     */
    public List<ScheduledWorkflowResponse> toResponseList(List<ScheduledWorkflow> scheduled) {
        if (scheduled.isEmpty()) {
            return List.of();
        }
        Map<String, RunStatus> lastStatus = lastRunStatusByScheduleId(
                scheduled.stream().map(ScheduledWorkflow::getId).toList());
        SchedulerPause pause = activePause();
        return scheduled.stream().map(s -> toResponse(s, lastStatus, pause)).toList();
    }

    /**
     * Last run status per schedule, from two queries.
     *
     * <p>The queries are ordered by start time descending, so the first
     * hit per schedule wins — {@code putIfAbsent} instead of {@code put}. Workflow runs take precedence over
     * chain runs, as in {@link #resolveLastRunStatus}: a schedule targets one or the
     * other, never both at once.
     */
    private Map<String, RunStatus> lastRunStatusByScheduleId(List<String> scheduleIds) {
        Map<String, RunStatus> latest = new HashMap<>();
        for (Object[] row : workflowRunRepository.findStatusesByScheduleIds(scheduleIds)) {
            latest.putIfAbsent((String) row[0], (RunStatus) row[1]);
        }
        for (Object[] row : chainRunRepository.findStatusesByScheduleIds(scheduleIds)) {
            latest.putIfAbsent((String) row[0], (RunStatus) row[1]);
        }
        return latest;
    }
}
