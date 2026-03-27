package com.automation.mapper;

import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ScheduleMapper {

    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;

    public ScheduleMapper(WorkflowRunRepository workflowRunRepository,
                          ChainRunRepository chainRunRepository) {
        this.workflowRunRepository = workflowRunRepository;
        this.chainRunRepository = chainRunRepository;
    }

    public ScheduledWorkflowResponse toResponse(ScheduledWorkflow s) {
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

        RunStatus lastRunStatus = resolveLastRunStatus(s);

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
                s.getConflictBehavior() != null ? s.getConflictBehavior().name() : "SKIP"
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

    public List<ScheduledWorkflowResponse> toResponseList(List<ScheduledWorkflow> scheduled) {
        return scheduled.stream().map(this::toResponse).toList();
    }
}
