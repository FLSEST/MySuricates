package com.automation.mapper;

import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.dto.response.StepResultResponse;
import com.automation.entity.StepResult;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring", uses = {SkillMapper.class})
public interface WorkflowMapper {

    @Mapping(source = "project.id", target = "projectId")
    WorkflowResponse toResponse(Workflow workflow);

    List<WorkflowResponse> toResponseList(List<Workflow> workflows);

    @Mapping(source = "workflow.id", target = "workflowId")
    WorkflowRunResponse toRunResponse(WorkflowRun run);

    List<WorkflowRunResponse> toRunResponseList(List<WorkflowRun> runs);

    StepResultResponse toStepResponse(StepResult step);
}
