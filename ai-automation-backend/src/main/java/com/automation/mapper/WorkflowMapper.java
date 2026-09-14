package com.automation.mapper;

import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.dto.response.StepResultResponse;
import com.automation.entity.StepResult;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowDefinitionHasher;
import org.mapstruct.Context;
import org.mapstruct.IterableMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public abstract class WorkflowMapper {

    @Autowired
    protected WorkflowRunRepository workflowRunRepository;

    // Optional: controller slices that import the mapper without the module system still work
    @Autowired(required = false)
    protected WorkflowDefinitionHasher definitionHasher;

    @Autowired(required = false)
    protected ModuleRegistry modules;

    protected String definitionHashOf(Workflow workflow) {
        if (definitionHasher == null || modules == null || !modules.isEnabled(ModuleCatalog.PROVENANCE)) {
            return null; // mapper used outside Spring (tests) or provenance off
        }
        return definitionHasher.hash(workflow);
    }

    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.name", target = "projectName")
    @Mapping(source = "project.subtitle", target = "projectSubtitle")
    @Mapping(source = "project.description", target = "projectDescription")
    @Mapping(target = "runCount", expression = "java(workflowRunRepository.countByWorkflowId(workflow.getId()))")
    @Mapping(target = "activatedSkillIds", expression = "java(workflow.getActivatedSkills().stream().map(s -> s.getId()).toList())")
    @Mapping(target = "activatedSkillNames", expression = "java(workflow.getActivatedSkills().stream().map(s -> s.getName()).toList())")
    @Mapping(target = "definitionHash", expression = "java(definitionHashOf(workflow))")
    @Named("single")
    public abstract WorkflowResponse toResponse(Workflow workflow);

    @IterableMapping(qualifiedByName = "single")
    public abstract List<WorkflowResponse> toResponseList(List<Workflow> workflows);

    // --- Batch variant: run counts injected via @Context (avoids the per-workflow countByWorkflowId N+1) ---

    @Mapping(source = "project.id", target = "projectId")
    @Mapping(source = "project.name", target = "projectName")
    @Mapping(source = "project.subtitle", target = "projectSubtitle")
    @Mapping(source = "project.description", target = "projectDescription")
    @Mapping(target = "runCount", expression = "java(runCounts.getOrDefault(workflow.getId(), 0L))")
    @Mapping(target = "activatedSkillIds", expression = "java(workflow.getActivatedSkills().stream().map(s -> s.getId()).toList())")
    @Mapping(target = "activatedSkillNames", expression = "java(workflow.getActivatedSkills().stream().map(s -> s.getName()).toList())")
    @Mapping(target = "definitionHash", ignore = true) // lists stay cheap; the detail response carries the hash
    @Named("withRunCounts")
    public abstract WorkflowResponse toResponse(Workflow workflow, @Context Map<String, Long> runCounts);

    @IterableMapping(qualifiedByName = "withRunCounts")
    public abstract List<WorkflowResponse> toResponseList(List<Workflow> workflows, @Context Map<String, Long> runCounts);

    /**
     * Liste mit Run-Counts aus EINER GROUP-BY-Query (statt {@code countByWorkflowId} pro Workflow).
     * Für die /workflows- und Projekt-Workflow-Listen. {@code runCount}-Werte identisch zum Einzelpfad.
     */
    public List<WorkflowResponse> toResponseListWithRunCounts(List<Workflow> workflows) {
        Map<String, Long> runCounts = workflowRunRepository.countRunsGroupedByWorkflow().stream()
                .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1]));
        return toResponseList(workflows, runCounts);
    }

    @Mapping(target = "workflowId", expression = "java(safeWorkflowId(run))")
    @Mapping(target = "workflowName", expression = "java(safeWorkflowName(run))")
    @Mapping(target = "projectName", expression = "java(safeProjectName(run))")
    @Mapping(target = "projectPath", expression = "java(safeProjectPath(run))")
    public abstract WorkflowRunResponse toRunResponse(WorkflowRun run);

    public abstract List<WorkflowRunResponse> toRunResponseList(List<WorkflowRun> runs);

    @Mapping(target = "blockType", expression = "java(metadataString(step, \"blockType\"))")
    @Mapping(target = "blockName", expression = "java(metadataString(step, \"blockName\"))")
    public abstract StepResultResponse toStepResponse(StepResult step);

    protected String metadataString(StepResult step, String key) {
        Map<String, Object> metadata = step.getMetadata();
        return metadata != null && metadata.get(key) instanceof String value ? value : null;
    }

    protected String safeWorkflowId(WorkflowRun run) {
        try {
            return run.getWorkflow() != null ? run.getWorkflow().getId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    protected String safeWorkflowName(WorkflowRun run) {
        // Prefer the snapshot captured at run creation — stable even when the
        // workflow is soft-deleted or the association cannot be lazy-loaded.
        String snapshot = run.getWorkflowName();
        if (snapshot != null && !snapshot.isBlank()) return snapshot;
        try {
            return run.getWorkflow() != null ? run.getWorkflow().getName() : "(deleted)";
        } catch (Exception e) {
            return "(deleted)";
        }
    }

    protected String safeProjectName(WorkflowRun run) {
        try {
            if (run.getWorkflow() != null && run.getWorkflow().getProject() != null) {
                return run.getWorkflow().getProject().getName();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    protected String safeProjectPath(WorkflowRun run) {
        try {
            if (run.getWorkflow() != null && run.getWorkflow().getProject() != null) {
                return run.getWorkflow().getProject().getPath();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
