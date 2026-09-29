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
     * Einzelner Zeitplan — fuer die Detail-Route, wo zwei Abfragen zwei Abfragen sind.
     *
     * <p>Fuer Listen {@link #toResponseList(List)} nehmen: Diese Methode loest den letzten
     * Run-Status je Aufruf auf, und das ist in einer Liste ein N+1 (TODO 172a6a67).
     */
    public ScheduledWorkflowResponse toResponse(ScheduledWorkflow s) {
        return toResponse(s, null, activePause());
    }

    private SchedulerPause activePause() {
        return skipPolicy.activePause(Instant.now(clock));
    }

    /**
     * @param lastStatusByScheduleId vorab gesammelte Status je Zeitplan-Id, oder {@code null} fuer
     *     die Einzelauflösung. Ein Zeitplan ohne Eintrag hatte keinen Run — das ist kein
     *     fehlender Wert, sondern {@code null} als Status, genau wie vorher.
     * @param pause Wartungsmodus und aktive skip-until-Pause, einmal je Aufruf ermittelt
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

    /** Nur der neueste Status — eine Zeile, eine Spalte (TODO 86286572). */
    private static final Pageable NEWEST_ONLY = PageRequest.of(0, 1);

    /**
     * Letzter Run-Status eines einzelnen Zeitplans. Liest je Run-Art hoechstens eine Status-Zeile,
     * statt die komplette Run-Liste des Zeitplans als Entitaeten zu laden (TODO 86286572).
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
     * Liste mit gebuendelter Status-Auflösung: zwei Abfragen fuer alle Zeitplaene statt zwei je
     * Zeitplan (TODO 172a6a67, Muster wie {@code WorkflowMapper.toResponseListWithRunCounts}).
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
     * Letzter Run-Status je Zeitplan, aus zwei Abfragen.
     *
     * <p>Die Reihenfolge der Abfragen ist absteigend nach Startzeit, deshalb gewinnt der erste
     * Treffer je Zeitplan — {@code putIfAbsent} statt {@code put}. Workflow-Runs haben Vorrang vor
     * Ketten-Runs, wie in {@link #resolveLastRunStatus}: Ein Zeitplan zielt auf das eine oder das
     * andere, beides zugleich gibt es nicht.
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
