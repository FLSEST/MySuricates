package com.automation.mapper;

import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ScheduleMapper {

    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;

    public ScheduleMapper(WorkflowRunRepository workflowRunRepository,
                          ChainRunRepository chainRunRepository) {
        this.workflowRunRepository = workflowRunRepository;
        this.chainRunRepository = chainRunRepository;
    }

    /**
     * Einzelner Zeitplan — fuer die Detail-Route, wo zwei Abfragen zwei Abfragen sind.
     *
     * <p>Fuer Listen {@link #toResponseList(List)} nehmen: Diese Methode loest den letzten
     * Run-Status je Aufruf auf, und das ist in einer Liste ein N+1 (TODO 172a6a67).
     */
    public ScheduledWorkflowResponse toResponse(ScheduledWorkflow s) {
        return toResponse(s, null);
    }

    /**
     * @param lastStatusByScheduleId vorab gesammelte Status je Zeitplan-Id, oder {@code null} fuer
     *     die Einzelauflösung. Ein Zeitplan ohne Eintrag hatte keinen Run — das ist kein
     *     fehlender Wert, sondern {@code null} als Status, genau wie vorher.
     */
    private ScheduledWorkflowResponse toResponse(ScheduledWorkflow s,
                                                 Map<String, RunStatus> lastStatusByScheduleId) {
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
                s.getNextRunAt(),
                s.getCreatedAt(),
                lastRunStatus,
                s.getEndDate(),
                s.getMaxRuns(),
                s.getRunCount(),
                s.getConflictBehavior() != null ? s.getConflictBehavior().name() : "SKIP",
                s.isCatchUpOnResume()
        );
    }

    private RunStatus resolveLastRunStatus(ScheduledWorkflow s) {
        var workflowRuns = workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(s.getId());
        if (!workflowRuns.isEmpty()) {
            return workflowRuns.get(0).getStatus();
        }
        var chainRuns = chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(s.getId());
        if (!chainRuns.isEmpty()) {
            return chainRuns.get(0).getStatus();
        }
        return null;
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
        return scheduled.stream().map(s -> toResponse(s, lastStatus)).toList();
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
