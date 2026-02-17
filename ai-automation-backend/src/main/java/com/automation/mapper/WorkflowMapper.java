package com.automation.mapper;

import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.dto.response.StepResultResponse;
import com.automation.entity.StepResult;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Mapper(componentModel = "spring", uses = {SkillMapper.class})
public abstract class WorkflowMapper {

    @Autowired
    protected WorkflowRunRepository workflowRunRepository;

    @Mapping(source = "project.id", target = "projectId")
    @Mapping(target = "runCount", expression = "java(workflowRunRepository.countByWorkflowId(workflow.getId()))")
    public abstract WorkflowResponse toResponse(Workflow workflow);

    public abstract List<WorkflowResponse> toResponseList(List<Workflow> workflows);

    @Mapping(source = "workflow.id", target = "workflowId")
    @Mapping(source = "workflow.name", target = "workflowName")
    public abstract WorkflowRunResponse toRunResponse(WorkflowRun run);

    public abstract List<WorkflowRunResponse> toRunResponseList(List<WorkflowRun> runs);

    public abstract StepResultResponse toStepResponse(StepResult step);
}
