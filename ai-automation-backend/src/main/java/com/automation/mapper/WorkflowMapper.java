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
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowDefinitionHasher;
import com.automation.service.workflow.WorkflowSkillPins;
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

    @Autowired
    protected RunStepSummaries runStepSummaries;

    // Optional: controller slices that import the mapper without the module system still work
    @Autowired(required = false)
    protected WorkflowDefinitionHasher definitionHasher;

    @Autowired(required = false)
    protected ModuleRegistry modules;

    // Optional like the hasher: a mapper built in a test without Spring names no chain
    @Autowired(required = false)
    protected ChainRunRepository chainRunRepository;

    /**
     * Die Kette des Ketten-Runs, aus dem der Run stammt (3a35ced1) – eine Abfrage nur auf die
     * Fremdschlüsselspalte, kein Lazy-Zugriff (OSIV ist aus). Nur die Detailantwort trägt sie.
     */
    protected String chainIdOf(WorkflowRun run) {
        if (run.getChainRunId() == null || chainRunRepository == null) {
            return null;
        }
        return chainRunRepository.findChainIdById(run.getChainRunId()).orElse(null);
    }

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
    @Mapping(target = "sealedSkillPins", expression = "java(sealedSkillPinsOf(run))")
    @Mapping(target = "chainId", expression = "java(chainIdOf(run))")
    @Named("runSingle")
    public abstract WorkflowRunResponse toRunResponse(WorkflowRun run);

    @IterableMapping(qualifiedByName = "runSingle")
    public abstract List<WorkflowRunResponse> toRunResponseList(List<WorkflowRun> runs);

    // --- Run lists: workflow/project labels from ONE query (avoids the per-run lazy-load of
    // WorkflowRun.workflow, TODO 9db34f3b) ---

    /** Die Bezeichner, die eine Run-Zeile vom zugehörigen Workflow braucht. */
    public record RunOwner(String workflowName, String projectName, String projectPath) {}

    private static final RunOwner UNKNOWN_OWNER = new RunOwner(null, null, null);

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
     * Run-Liste fuer Listen-Routen ({@code /executions}, {@code /workflows/{id}/runs},
     * {@code /schedules/{id}/runs}, {@code /schedules/calendar}): Workflow- und Projektnamen aus
     * EINER Query, statt der Schritte ihre Anzahl und die gekuerzte Ausgabe des letzten. Namen wie
     * im Einzelpfad: Vorrang hat der Namens-Schnappschuss des Runs, dann der Workflow, sonst
     * "(deleted)".
     *
     * <p>Die Listen-Route trug jeden {@code step_results.output} (TEXT) in die Antwort — gemessen
     * 3 061 816 Bytes bei 60 Runs (TODO a05c9504). Statt der Schritte liefert sie deren Anzahl und
     * die gekuerzte Ausgabe des letzten; den Volltext hat die Detail-Route. Seit TODO 7fd9f654
     * werden die Schritte dafuer auch nicht mehr geladen: zwei Abfragen fuer die ganze Seite
     * zaehlen sie und schneiden die letzte Ausgabe in der Datenbank ab.
     */
    public List<WorkflowRunListItemResponse> toRunListItemsWithOwners(List<WorkflowRun> runs) {
        return toRunListItems(runs, ownersFor(runs), runStepSummaries.of(runs));
    }

    protected List<WorkflowRunListItemResponse> toRunListItems(List<WorkflowRun> runs, Map<String, RunOwner> owners,
                                                               Map<String, RunStepSummary> steps) {
        return runs.stream()
                .map(run -> toRunListItem(run, owners, steps.getOrDefault(run.getId(), RunStepSummary.NONE)))
                .toList();
    }

    private WorkflowRunListItemResponse toRunListItem(WorkflowRun run, Map<String, RunOwner> owners, RunStepSummary steps) {
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
                steps.count(),
                steps.lastStatus(),
                steps.preview(),
                run.getSealed(),
                run.getChainRunId());
    }

    /** Skill pins verified at start; unreadable JSON is shown as none rather than failing the whole response. */
    protected Map<String, String> sealedSkillPinsOf(WorkflowRun run) {
        if (run.getSealedSkillPins() == null) {
            return null;
        }
        try {
            return WorkflowSkillPins.fromJson(run.getSealedSkillPins());
        } catch (RuntimeException e) {
            return null;
        }
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
