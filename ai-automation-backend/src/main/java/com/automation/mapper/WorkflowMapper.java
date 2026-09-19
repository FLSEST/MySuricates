package com.automation.mapper;

import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowRunListItemResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.dto.response.StepResultResponse;
import com.automation.entity.StepResult;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.WorkflowRepository;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public abstract class WorkflowMapper {

    @Autowired
    protected WorkflowRunRepository workflowRunRepository;

    @Autowired
    protected WorkflowRepository workflowRepository;

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
    @Named("runSingle")
    public abstract WorkflowRunResponse toRunResponse(WorkflowRun run);

    @IterableMapping(qualifiedByName = "runSingle")
    public abstract List<WorkflowRunResponse> toRunResponseList(List<WorkflowRun> runs);

    // --- Batch variant: workflow/project labels injected via @Context (avoids the per-run
    // lazy-load of WorkflowRun.workflow, TODO 9db34f3b) ---

    /** Die Bezeichner, die eine Run-Zeile vom zugehörigen Workflow braucht. */
    public record RunOwner(String workflowName, String projectName, String projectPath) {}

    private static final RunOwner UNKNOWN_OWNER = new RunOwner(null, null, null);

    @Mapping(target = "workflowId", expression = "java(safeWorkflowId(run))")
    @Mapping(target = "workflowName", expression = "java(batchedWorkflowName(run, owners))")
    @Mapping(target = "projectName", expression = "java(ownerOf(run, owners).projectName())")
    @Mapping(target = "projectPath", expression = "java(ownerOf(run, owners).projectPath())")
    @Named("runWithOwners")
    public abstract WorkflowRunResponse toRunResponse(WorkflowRun run, @Context Map<String, RunOwner> owners);

    @IterableMapping(qualifiedByName = "runWithOwners")
    public abstract List<WorkflowRunResponse> toRunResponseList(List<WorkflowRun> runs,
                                                                @Context Map<String, RunOwner> owners);

    /**
     * Run-Liste, deren Workflow- und Projektnamen aus EINER Query stammen (statt je Run die
     * Lazy-Assoziation aufzulösen). Werte identisch zum Einzelpfad: Vorrang hat der Namens-
     * Schnappschuss des Runs, dann der Workflow, sonst "(deleted)".
     */
    public List<WorkflowRunResponse> toRunResponseListWithOwners(List<WorkflowRun> runs) {
        return toRunResponseList(runs, ownersFor(runs));
    }

    /** Workflow- und Projektnamen aller Runs aus EINER Query (TODO 9db34f3b). */
    private Map<String, RunOwner> ownersFor(List<WorkflowRun> runs) {
        Set<String> workflowIds = runs.stream()
                .map(this::safeWorkflowId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return workflowIds.isEmpty()
                ? Map.of()
                : workflowRepository.findRunOwnerData(workflowIds).stream().collect(Collectors.toMap(
                        row -> (String) row[0],
                        row -> new RunOwner((String) row[1], (String) row[2], (String) row[3])));
    }

    // --- Lean list variant: no steps in the response (TODO a05c9504) ---

    /**
     * Run-Liste fuer Listen-Routen: wie {@link #toRunResponseListWithOwners}, aber ohne die
     * Schritte.
     *
     * <p>Die Listen-Route trug jeden {@code step_results.output} (TEXT) in die Antwort — gemessen
     * 3 061 816 Bytes bei 60 Runs. Statt der Schritte liefert sie jetzt deren Anzahl und die
     * gekuerzte Ausgabe des letzten; den Volltext hat die Detail-Route.
     */
    public List<WorkflowRunListItemResponse> toRunListItemsWithOwners(List<WorkflowRun> runs) {
        Map<String, RunOwner> owners = ownersFor(runs);
        return runs.stream().map(run -> toRunListItem(run, owners)).toList();
    }

    private WorkflowRunListItemResponse toRunListItem(WorkflowRun run, Map<String, RunOwner> owners) {
        List<StepResult> steps = run.getSteps() == null ? List.of() : run.getSteps();
        StepResult last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        return new WorkflowRunListItemResponse(
                run.getId(),
                safeWorkflowId(run),
                batchedWorkflowName(run, owners),
                ownerOf(run, owners).projectName(),
                ownerOf(run, owners).projectPath(),
                run.getStatus(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getDurationMs(),
                run.getUserRating(),
                run.getError(),
                run.getMetadata(),
                run.getInputContext(),
                run.getTriggeredByScheduleId(),
                run.getDefinitionHash(),
                run.getDefinitionVersionId(),
                run.getTriggerSource(),
                steps.size(),
                last == null ? null : last.getStatus(),
                preview(last == null ? null : last.getOutput()));
    }

    /** Gekuerzte Vorschau; {@code null} bleibt {@code null}, damit "kein Wert" nicht wie "leer" aussieht. */
    private static String preview(String output) {
        if (output == null) {
            return null;
        }
        return output.length() <= WorkflowRunListItemResponse.PREVIEW_LENGTH
                ? output
                : output.substring(0, WorkflowRunListItemResponse.PREVIEW_LENGTH) + " […]";
    }

    protected RunOwner ownerOf(WorkflowRun run, Map<String, RunOwner> owners) {
        String workflowId = safeWorkflowId(run);
        return workflowId == null ? UNKNOWN_OWNER : owners.getOrDefault(workflowId, UNKNOWN_OWNER);
    }

    protected String batchedWorkflowName(WorkflowRun run, Map<String, RunOwner> owners) {
        String snapshot = run.getWorkflowName();
        if (snapshot != null && !snapshot.isBlank()) return snapshot;
        String name = ownerOf(run, owners).workflowName();
        return name != null ? name : "(deleted)";
    }

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
