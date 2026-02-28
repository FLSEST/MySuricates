package com.automation.mapper;

import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.dto.response.StepResultResponse;
import com.automation.entity.StepResult;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import jakarta.persistence.EntityNotFoundException;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Mapper(componentModel = "spring", uses = {SkillMapper.class})
public abstract class WorkflowMapper {

    @Autowired
    protected WorkflowRunRepository workflowRunRepository;

    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.name", target = "projectName")
    @Mapping(source = "project.subtitle", target = "projectSubtitle")
    @Mapping(source = "project.description", target = "projectDescription")
    @Mapping(target = "runCount", expression = "java(workflowRunRepository.countByWorkflowId(workflow.getId()))")
    public abstract WorkflowResponse toResponse(Workflow workflow);

    public abstract List<WorkflowResponse> toResponseList(List<Workflow> workflows);

    @Mapping(target = "workflowId", expression = "java(safeWorkflowId(run))")
    @Mapping(target = "workflowName", expression = "java(safeWorkflowName(run))")
    @Mapping(target = "projectName", expression = "java(safeProjectName(run))")
    @Mapping(target = "projectPath", expression = "java(safeProjectPath(run))")
    public abstract WorkflowRunResponse toRunResponse(WorkflowRun run);

    public abstract List<WorkflowRunResponse> toRunResponseList(List<WorkflowRun> runs);

    public abstract StepResultResponse toStepResponse(StepResult step);

    protected String safeWorkflowId(WorkflowRun run) {
        try {
            return run.getWorkflow() != null ? run.getWorkflow().getId() : null;
        } catch (EntityNotFoundException e) {
            return null;
        }
    }

    protected String safeWorkflowName(WorkflowRun run) {
        try {
            return run.getWorkflow() != null ? run.getWorkflow().getName() : "(deleted)";
        } catch (EntityNotFoundException e) {
            return "(deleted)";
        }
    }

    protected String safeProjectName(WorkflowRun run) {
        try {
            if (run.getWorkflow() != null && run.getWorkflow().getProject() != null) {
                return run.getWorkflow().getProject().getName();
            }
            return null;
        } catch (EntityNotFoundException e) {
            return null;
        }
    }

    protected String safeProjectPath(WorkflowRun run) {
        try {
            if (run.getWorkflow() != null && run.getWorkflow().getProject() != null) {
                return run.getWorkflow().getProject().getPath();
            }
            return null;
        } catch (EntityNotFoundException e) {
            return null;
        }
    }
}
