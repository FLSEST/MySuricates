package com.automation.mapper;

import com.automation.dto.response.ScheduledWorkflowResponse;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.TargetType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ScheduleMapper {

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
                s.getCreatedAt()
        );
    }

    public List<ScheduledWorkflowResponse> toResponseList(List<ScheduledWorkflow> scheduled) {
        return scheduled.stream().map(this::toResponse).toList();
    }
}
