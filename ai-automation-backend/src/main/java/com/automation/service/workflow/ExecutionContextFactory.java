package com.automation.service.workflow;

import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.memory.WorkflowMemoryService;
import com.automation.service.project.ProjectContextService;
import com.automation.service.system.SystemIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collection;

/**
 * Builds the {@link ExecutionContext} for a workflow run — input variables, previous-output seed,
 * system template variables, workflow-version variables, system identity run metadata, platform
 * data ({@code previous_report}, {@code open_todos}, for audit workflows {@code audit_findings}) and
 * instruction prefix / model / provider / effort — and writes the project-side context files
 * (.claude/rules/) every CLI call picks up.
 *
 * <p>Extracted from {@code WorkflowExecutor} (review C1, roadmap 3.1). The variable set is the
 * contract documented for workflow authors in docs/WORKFLOW-GUIDE.md.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionContextFactory {

    private final WorkflowVersionService workflowVersionService;
    private final WorkflowRunRepository runRepository;
    private final SystemIdentityService systemIdentity;
    private final WorkflowContextDataService contextDataService;
    private final AgentInstructionsService agentInstructionsService;
    private final CommandExecutorService commandExecutorService;
    private final WorkflowMemoryService workflowMemoryService;
    private final ProjectContextService projectContextService;

    /** Everything executeBlocks knows before the loop starts. */
    public record ContextSeed(String runId, String workflowId, String workflowName, Project project,
                              String containerId, Map<String, Object> inputContext, String instructions,
                              String model, String provider, String effort, String resumePreviousOutput,
                              String definitionHash, TriggerSource triggerSource,
                              /** metadata.userPreferences des Workflows (TODO d49fbde0). */
                              boolean userPreferences,
                              /** Metadaten des Workflows: metadata.audit bringt audit_findings (TODO 3643beac). */
                              Map<String, Object> workflowMeta) {

        public ContextSeed {
            workflowMeta = workflowMeta != null ? workflowMeta : Map.of();
        }

        /** Kurzform ohne Arbeitspräferenzen und Metadaten — der Weg fuer alles, was sie nicht anfordert. */
        public ContextSeed(String runId, String workflowId, String workflowName, Project project,
                           String containerId, Map<String, Object> inputContext, String instructions,
                           String model, String provider, String effort, String resumePreviousOutput,
                           String definitionHash, TriggerSource triggerSource) {
            this(runId, workflowId, workflowName, project, containerId, inputContext, instructions,
                    model, provider, effort, resumePreviousOutput, definitionHash, triggerSource,
                    false, Map.of());
        }
    }

    public ExecutionContext create(ContextSeed seed) {
        String runId = seed.runId();
        String workflowId = seed.workflowId();
        String workflowName = seed.workflowName();
        Project project = seed.project();
        String containerId = seed.containerId();
        Map<String, Object> inputContext = seed.inputContext();
        String instructions = seed.instructions();
        String model = seed.model();
        String provider = seed.provider();
        String effort = seed.effort();
        String resumePreviousOutput = seed.resumePreviousOutput();

        ExecutionContext context = ExecutionContext.builder()
                .containerId(containerId)
                .workflowRunId(runId)
                .build();
        context.setProjectPath(project.getPath());
        Set<String> untrustedKeys = reservedNames(inputContext, ExecutionContext.UNTRUSTED_KEYS_VARIABLE);
        inputContext.forEach((name, value) -> {
            if (name.startsWith("execution_path_")) return;
            // audit_findings describes the running workflow itself and is set by injectPlatformData only.
            // A value handed over by a parent run (sub-workflow copies every variable) or passed as input
            // would otherwise stay here as a trusted variable — unquoted in shell blocks (TODO 3643beac).
            if (AuditContextSource.VARIABLE.equals(name)) return;
            if (untrustedKeys.contains(name)) {
                context.setUntrustedVariable(name, value);
            } else {
                context.setVariable(name, value);
            }
        });
        // Outputs tainted in an earlier segment of this run (resume) or in the parent (sub-workflow)
        reservedNames(inputContext, ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE)
                .forEach(context::markOutputUntrusted);
        // Platform data already reached a shell command line earlier in the run (TODO cadfc423)
        if (Boolean.parseBoolean(String.valueOf(inputContext.get(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE)))) {
            context.markPlatformUsedInShell();
        }
        if (resumePreviousOutput != null) {
            context.setPreviousOutput(resumePreviousOutput);
        } else if (inputContext.get("previous_output") != null) {
            context.setPreviousOutput(String.valueOf(inputContext.get("previous_output")));
        }

        // Inject workflow & run metadata as template variables
        context.setVariable("workflow_run_id", runId);
        context.setVariable("workflow_id", workflowId);
        context.setVariable("workflow_name", workflowName != null ? workflowName : "");
        context.setVariable("project_id", project.getId() != null ? project.getId() : "");
        context.setVariable("project_path", project.getPath() != null ? project.getPath() : "");
        context.setVariable("execution_path", project.getPath() != null ? project.getPath() : "");
        for (String reserved : List.of("workspace_path", "workspace_logs", "workspace_source_sha")) {
            context.setVariable(reserved, "");
        }
        context.setVariable("model", model != null ? model : "");
        context.setVariable("provider", provider != null ? provider : "");
        context.setVariable("effort", effort != null ? effort : "");
        java.time.Instant startedAt = java.time.Instant.now();
        context.setVariable("started_at", startedAt.toString());
        // Path-safe timestamp for output-directory naming (no ':' or timezone).
        // Format: yyyyMMdd-HHmmss in UTC. Use together with {{ workflow_run_id }}
        // to version workflow outputs per run (see TODO 2738fda0).
        context.setVariable("run_timestamp", java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(startedAt));
        // Host/user metadata so workflow configs can stay user-agnostic
        // (e.g. "mounts": ["{{ home_dir }}/dokumente:{{ home_dir }}/dokumente:rw"])
        context.setVariable("home_dir", System.getProperty("user.home", ""));
        context.setVariable("user", System.getProperty("user.name", ""));

        // Provenance: which definition runs and what started it (empty when provenance is off / unknown)
        context.setVariable("workflow_definition_hash", seed.definitionHash() != null ? seed.definitionHash() : "");
        context.setVariable("trigger_source", seed.triggerSource() != null ? seed.triggerSource().name() : "");

        // Inject workflow version info for delta-based quality workflows
        int versionCount = workflowVersionService.getVersionCount(workflowId);
        context.setVariable("workflow_version", String.valueOf(versionCount > 0 ? versionCount : 0));
        try {
            var latestVersion = workflowVersionService.findLatest(workflowId);
            var lastSuccessfulRun = runRepository
                    .findTopByWorkflowIdAndStatusOrderByStartedAtDesc(workflowId, com.automation.entity.RunStatus.COMPLETED);
            String lastHash = lastSuccessfulRun.map(WorkflowRun::getDefinitionHash).orElse(null);
            if (seed.definitionHash() != null && lastHash != null) {
                // Exact: compare what ran last time with what runs now
                context.setVariable("workflow_changed_since_last_run", String.valueOf(!seed.definitionHash().equals(lastHash)));
            } else if (latestVersion.isPresent()) {
                boolean changedSinceLastRun = lastSuccessfulRun
                        .map(r -> r.getCompletedAt() != null && latestVersion.get().getCreatedAt().isAfter(r.getCompletedAt()))
                        .orElse(true);
                context.setVariable("workflow_changed_since_last_run", String.valueOf(changedSinceLastRun));
            } else {
                context.setVariable("workflow_changed_since_last_run", "false");
            }
        } catch (Exception e) {
            log.warn("Failed to inject workflow version info: {}", e.getMessage());
            context.setVariable("workflow_changed_since_last_run", "false");
        }

        // Stamp run metadata with system identity
        context.setRunMetadata("system", systemIdentity.getName());
        context.setRunMetadata("systemVersion", systemIdentity.getVersion());

        // Inject platform data as context variables (so workflows don't need API/DB access)
        contextDataService.injectPlatformData(context, workflowId, project.getId(), seed.workflowMeta());

        // Set instruction prefix and model from workflow
        // Der Standardweg ruft weiter die einparametrige Methode: Ein Workflow ohne Opt-in soll
        // nicht nur denselben Prompt bekommen, sondern denselben AUFRUF ausloesen. Sonst haengt
        // jeder vorhandene Test und Aufrufer an einer Signatur, die sich seinetwegen geaendert hat
        // -- genau daran sind beim ersten Versuch 74 Tests in WorkflowExecutorTest gescheitert.
        String instructionPrefix = seed.userPreferences()
                ? agentInstructionsService.buildInstructionPrefix(instructions, true)
                : agentInstructionsService.buildInstructionPrefix(instructions);
        if (!instructionPrefix.isBlank()) {
            context.setInstructionPrefix(instructionPrefix);
        }
        if (model != null && !model.isBlank()) {
            context.setModel(model);
        }
        if (provider != null && !provider.isBlank()) {
            context.setProvider(provider);
        }
        if (effort != null && !effort.isBlank()) {
            context.setEffort(effort);
        }

        return context;
    }

    /**
     * Writes skill context, active workflow memory and project AI context into the project
     * (.claude/rules/) so every CLI call spawned for this run picks them up.
     */
    public void writeProjectContextFiles(Project project, String workflowId, List<Skill> skills) {
        // Write skill context to .claude/rules/ (file-based, picked up by all Claude CLI calls)
        commandExecutorService.writeSkillContextToProject(project.getPath(), skills);

        // Write active workflow memory to project for executing agent
        workflowMemoryService.writeActiveMemoryToProject(workflowId, project.getPath());

        // Write project AI context to .claude/rules/ for executing agent
        projectContextService.writeProjectContextToProject(project.getAiContext(), project.getPath());
    }
    /**
     * Names behind a reserved taint key of the input context — {@link ExecutionContext#UNTRUSTED_KEYS_VARIABLE}
     * (untrusted variables, flagged by the trigger) or {@link ExecutionContext#UNTRUSTED_OUTPUTS_VARIABLE}
     * (outputs tainted earlier in the run). Anything but a list of names means "none".
     */
    static Set<String> reservedNames(Map<String, Object> inputContext, String key) {
        Object raw = inputContext.get(key);
        if (raw instanceof Collection<?> names) {
            Set<String> keys = new HashSet<>();
            for (Object name : names) {
                if (name != null) keys.add(String.valueOf(name));
            }
            return keys;
        }
        return Set.of();
    }
}
