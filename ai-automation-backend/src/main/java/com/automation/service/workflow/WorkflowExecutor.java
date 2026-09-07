package com.automation.service.workflow;

import com.automation.entity.*;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.repository.ContainerRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.memory.WorkflowMemoryService;
import com.automation.service.todo.GateStatusOutputParser;
import com.automation.service.todo.TodoOutputParser;
import com.automation.service.project.ContainerService;
import com.automation.service.project.ProjectContextService;
import com.automation.service.system.SystemIdentityService;
import com.automation.util.TruncationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.automation.concurrency.DynamicSemaphore;

import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutor {

    private final List<BlockExecutor> blockExecutors;
    private final ContainerService containerService;
    private final CommandExecutorService commandExecutorService;
    private final WorkflowRunRepository runRepository;
    private final StepResultRepository stepResultRepository;
    private final ContainerRepository containerRepository;
    private final SkillRepository skillRepository;
    private final ExecutionBroadcaster broadcaster;
    private final ExecutorService virtualThreadExecutor;
    private final DynamicSemaphore executionSemaphore;
    private final AgentInstructionsService agentInstructionsService;
    private final ActivityLogService activityLogService;
    private final WorkflowMemoryService workflowMemoryService;
    private final ProjectContextService projectContextService;
    private final ApplicationEventPublisher eventPublisher;
    private final TemplateVerificationService templateVerificationService;
    private final SystemIdentityService systemIdentity;
    private final WorkflowContextDataService contextDataService;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowExecutionRegistry executionRegistry;
    private final BlockOutputValidator blockOutputValidator;
    private final com.automation.service.dlq.DeadLetterService deadLetterService;

    private static final java.util.Set<String> SYSTEM_VAR_KEYS = java.util.Set.of(
            "workflow_run_id", "workflow_id", "workflow_name", "project_id", "project_path",
            "model", "provider", "effort", "started_at", "run_timestamp",
            "previous_report", "open_todos",
            "workflow_version", "workflow_changed_since_last_run"
    );

    // Active execution tracking is delegated to WorkflowExecutionRegistry

    /**
     * Starts workflow execution asynchronously.
     * Creates a WorkflowRun with RUNNING status and returns it immediately.
     * The actual block execution happens in a background virtual thread.
     */
    public WorkflowRun execute(Workflow workflow, Map<String, Object> inputContext) {
        return execute(workflow, inputContext, null, null);
    }

    public WorkflowRun execute(Workflow workflow, Map<String, Object> inputContext,
                               String triggeredByScheduleId, Map<String, Object> initialRunMetadata) {
        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .workflowName(workflow.getName())
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .triggeredByScheduleId(triggeredByScheduleId)
                .build();
        if (initialRunMetadata != null && !initialRunMetadata.isEmpty()) {
            run.setMetadata(new HashMap<>(initialRunMetadata));
        }
        run = runRepository.save(run);

        // Eagerly initialize lazy collections before leaving the transaction/session.
        // Wrapped in try-catch: if a LazyInitializationException or similar occurs here
        // (e.g. when called from a scheduled executor without an active JPA session),
        // the run is immediately marked as FAILED instead of being stuck as a ghost run.
        final Project project;
        final List<BlockDefinition> blocks;
        final List<Skill> skills;
        final String instructions;
        final String model;
        final String provider;
        final String effort;
        final Map<String, Object> workflowMeta;
        final String templateId;
        final Map<String, Object> contextVars;
        Map<String, Skill> blockSkillLookup;
        try {
            project = workflow.getProject();
            blocks = List.copyOf(workflow.getBlocks());
            skills = List.copyOf(workflow.getActivatedSkills());
            instructions = workflow.getInstructions();
            model = workflow.getModel();
            provider = workflow.getProvider();
            effort = workflow.getEffort();
            workflowMeta = Map.copyOf(workflow.getMetadata());
            templateId = workflow.getTemplateId();

            Map<String, Object> mergedContext = new HashMap<>(workflow.getDefaultContext());
            if (inputContext != null) {
                mergedContext.putAll(inputContext);
            }
            contextVars = Map.copyOf(mergedContext);

            // Persist input context so it's visible in execution details
            if (!contextVars.isEmpty()) {
                run.setInputContext(new HashMap<>(contextVars));
                runRepository.save(run);
            }

            // Pre-resolve per-block skill IDs to Skill entities (single DB query)
            Set<String> workflowSkillIds = skills.stream().map(Skill::getId).collect(Collectors.toSet());
            Set<String> blockSkillIds = blocks.stream()
                    .filter(b -> b.getSkillIds() != null)
                    .flatMap(b -> b.getSkillIds().stream())
                    .filter(id -> !workflowSkillIds.contains(id))
                    .collect(Collectors.toSet());
            blockSkillLookup = blockSkillIds.isEmpty()
                    ? Map.of()
                    : skillRepository.findAllById(blockSkillIds).stream()
                            .collect(Collectors.toMap(Skill::getId, s -> s));
            if (!blockSkillIds.isEmpty() && blockSkillLookup.size() < blockSkillIds.size()) {
                Set<String> missing = new java.util.HashSet<>(blockSkillIds);
                missing.removeAll(blockSkillLookup.keySet());
                log.warn("Per-block skill IDs not found (deleted?): {}", missing);
            }
        } catch (Exception e) {
            log.error("Failed to initialize workflow data for run {}: {}", run.getId(), e.getMessage(), e);
            run.setStatus(RunStatus.FAILED);
            run.setCompletedAt(Instant.now());
            run.setError(Map.of("message", "Failed to initialize workflow data: " + e.getMessage()));
            runRepository.save(run);
            throw new WorkflowExecutionException("Failed to initialize workflow '" + workflow.getName() + "' for execution", e);
        }

        // Execute asynchronously — return the run immediately
        final String runId = run.getId();
        final String workflowId = workflow.getId();
        final String workflowName = workflow.getName();
        final Map<String, Skill> finalBlockSkillLookup = blockSkillLookup;
        activityLogService.info(LogCategory.EXECUTION,
                "Workflow execution started: " + workflowName,
                "WorkflowExecutor", runId, workflowId);
        virtualThreadExecutor.submit(() -> executeBlocks(runId, workflowId, workflowName, project, blocks, skills, finalBlockSkillLookup, contextVars, instructions, model, provider, effort, workflowMeta, templateId, 0, null));

        return run;
    }

    /**
     * Core execution loop.
     * @param skipToStepIndex for resume: skip blocks with index < this value (0 = no skip)
     * @param resumePreviousOutput for resume: pre-seed previousOutput from the last completed step
     */
    private void executeBlocks(String runId, String workflowId, String workflowName, Project project, List<BlockDefinition> blocks,
                               List<Skill> skills, Map<String, Skill> blockSkillLookup, Map<String, Object> inputContext,
                               String instructions, String model, String provider, String effort, Map<String, Object> workflowMeta,
                               String templateId, int skipToStepIndex, String resumePreviousOutput) {
        // Single try-finally ensures semaphore is always released, even if the thread
        // dies between acquire() and the execution body (eliminates the leak window).
        WorkflowRun run = null;
        ExecutionContext context = null;
        boolean semaphoreAcquired = false;
        try {
            executionSemaphore.acquire();
            semaphoreAcquired = true;
            run = runRepository.findById(runId).orElseThrow();
            if (skipToStepIndex == 0) {
                // Fresh run: start with empty step list
                run.setSteps(new ArrayList<>());
            } else {
                // Resuming: keep existing completed steps from DB
                run.setSteps(new ArrayList<>(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(runId)));
            }

            String containerId = ensureContainerRunning(project, runId);

            context = ExecutionContext.builder()
                    .containerId(containerId)
                    .workflowRunId(runId)
                    .build();
            context.setProjectPath(project.getPath());
            inputContext.forEach(context::setVariable);
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

            // Inject workflow version info for delta-based quality workflows
            int versionCount = workflowVersionService.getVersionCount(workflowId);
            context.setVariable("workflow_version", String.valueOf(versionCount > 0 ? versionCount : 0));
            try {
                var latestVersion = workflowVersionService.findLatest(workflowId);
                if (latestVersion.isPresent()) {
                    var lastSuccessfulRun = runRepository
                            .findTopByWorkflowIdAndStatusOrderByStartedAtDesc(workflowId, com.automation.entity.RunStatus.COMPLETED);
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
            contextDataService.injectPlatformData(context, workflowId, project.getId());

            // Set instruction prefix and model from workflow
            String instructionPrefix = agentInstructionsService.buildInstructionPrefix(instructions);
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

            executionRegistry.register(runId, context);

            // Write skill context to .claude/rules/ (file-based, picked up by all Claude CLI calls)
            commandExecutorService.writeSkillContextToProject(project.getPath(), skills);

            // Write active workflow memory to project for executing agent
            workflowMemoryService.writeActiveMemoryToProject(workflowId, project.getPath());

            // Write project AI context to .claude/rules/ for executing agent
            projectContextService.writeProjectContextToProject(project.getAiContext(), project.getPath());

            for (BlockDefinition block : blocks) {
                if (context.shouldStop()) {
                    run.setStatus(RunStatus.CANCELLED);
                    break;
                }

                if (context.isPaused() && !context.shouldStop()) {
                    run.setStatus(RunStatus.PAUSED);
                    runRepository.save(run);
                    broadcaster.broadcastRunStatus(run.getId(), RunStatus.PAUSED);
                    while (context.isPaused() && !context.shouldStop()) {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    if (!context.shouldStop()) {
                        run.setStatus(RunStatus.RUNNING);
                        runRepository.save(run);
                        broadcaster.broadcastRunStatus(run.getId(), RunStatus.RUNNING);
                    }
                }

                // Per-block skill context: merge block-level skills with workflow-level skills
                boolean hasBlockSkills = block.getSkillIds() != null && !block.getSkillIds().isEmpty();
                if (hasBlockSkills) {
                    List<Skill> merged = new ArrayList<>(skills);
                    Set<String> existingIds = skills.stream().map(Skill::getId).collect(Collectors.toSet());
                    for (String id : block.getSkillIds()) {
                        if (!existingIds.contains(id)) {
                            Skill extra = blockSkillLookup.get(id);
                            if (extra != null) {
                                merged.add(extra);
                            }
                        }
                    }
                    commandExecutorService.writeSkillContextToProject(project.getPath(), merged);
                }

                BlockExecutor executor = findExecutor(block.getType());

                // Pre-create RUNNING placeholder so UI shows the step immediately
                int stepIndex = context.preAllocateStepIndex();

                // For resumed runs: skip blocks that already completed in the previous execution
                if (stepIndex < skipToStepIndex) {
                    // Restore baseline skill context if this block had extra skills
                    if (hasBlockSkills) {
                        commandExecutorService.writeSkillContextToProject(project.getPath(), skills);
                    }
                    continue;
                }

                StepResult step = new StepResult();
                step.setStepIndex(stepIndex);
                step.setStartedAt(Instant.now());
                step.setStatus(StepStatus.RUNNING);
                step.setMetadata(Map.of("blockType", block.getType()));
                step.setWorkflowRun(run);
                run.getSteps().add(step);
                stepResultRepository.save(step);
                broadcaster.broadcastStepResult(run.getId(), step);

                // Execute the block — with optional output validation + retry
                StepResult result = executor.execute(block, context);
                int validationAttempts = 0;
                int maxValidationAttempts = blockOutputValidator.maxRetries(block);
                while (result.getStatus() == StepStatus.COMPLETED && validationAttempts < maxValidationAttempts) {
                    var validation = blockOutputValidator.validate(block, result.getOutput());
                    if (validation.passed()) break;
                    if (!blockOutputValidator.shouldRetry(block)) {
                        log.warn("Block {} output validation failed without retry: {}", step.getStepIndex(), validation.reason());
                        result.setStatus(StepStatus.FAILED);
                        String existing = result.getOutput() == null ? "" : result.getOutput();
                        result.setOutput(existing + "\n[VALIDATION] " + validation.reason());
                        break;
                    }
                    validationAttempts++;
                    log.info("Block {} output validation failed (attempt {}/{}): {}",
                            step.getStepIndex(), validationAttempts, maxValidationAttempts, validation.reason());
                    activityLogService.warn(LogCategory.EXECUTION,
                            "Block " + step.getStepIndex() + " validation failed (attempt " + validationAttempts + "/" + maxValidationAttempts + "): " + validation.reason(),
                            "WorkflowExecutor", run.getId());
                    result = executor.execute(block, context);
                }
                if (result.getStatus() == StepStatus.COMPLETED) {
                    var finalCheck = blockOutputValidator.validate(block, result.getOutput());
                    if (!finalCheck.passed()) {
                        log.warn("Block {} output validation failed after {} retries: {}",
                                step.getStepIndex(), validationAttempts, finalCheck.reason());
                        result.setStatus(StepStatus.FAILED);
                        String existing = result.getOutput() == null ? "" : result.getOutput();
                        result.setOutput(existing + "\n[VALIDATION] failed after " + validationAttempts + " retries: " + finalCheck.reason());
                    }
                }

                // Update the persisted step with the actual results
                step.setInput(result.getInput());
                step.setOutput(result.getOutput());
                step.setStatus(result.getStatus());
                step.setCompletedAt(result.getCompletedAt());
                step.setDurationMs(result.getDurationMs());
                Map<String, Object> stepMetadata = result.getMetadata() != null
                        ? new HashMap<>(result.getMetadata())
                        : new HashMap<>();
                stepMetadata.put("blockType", block.getType());
                if (block.getName() != null && !block.getName().isBlank()) {
                    stepMetadata.put("blockName", block.getName());
                }
                step.setMetadata(stepMetadata);
                context.setPreviousOutput(step.getOutput());
                context.setVariable("step_" + step.getStepIndex() + "_output",
                        step.getOutput() != null ? step.getOutput() : "");
                if (block.getName() != null && !block.getName().isBlank()) {
                    context.setBlockOutput(block.getName(), step.getOutput());
                }

                // Persist context delta for potential future resume
                if (step.getStatus() == StepStatus.COMPLETED) {
                    step.setContextDelta(computeContextDelta(context, run.getInputContext()));
                }

                // Update container last activity (skip if no container)
                if (containerId != null) {
                    updateContainerActivity(containerId);
                }

                broadcaster.broadcastStepResult(run.getId(), step);
                stepResultRepository.save(step);

                // Restore baseline skill context after a block with extra skills
                if (hasBlockSkills) {
                    commandExecutorService.writeSkillContextToProject(project.getPath(), skills);
                }

                if (step.getStatus() == StepStatus.FAILED && block.shouldStopOnFailure()) {
                    run.setStatus(RunStatus.FAILED);
                    String output = step.getOutput() != null ? step.getOutput() : "";
                    // Tail-biased: the actual error (HTTP status, exception) usually sits at the END of the output.
                    String message = "Step " + step.getStepIndex() + " (" + block.getType() + ") failed: "
                            + TruncationUtil.smart(output, 100, 400);
                    run.setError(Map.of("step", step.getStepIndex(), "blockType", block.getType(), "message", message));
                    break;
                }
            }

            // Parse gate_status markers from ALL blocks — highest severity wins.
            // GateStatus ordinal: GREEN(0) < YELLOW(1) < RED(2)
            GateStatus highestGate = null;
            String gateCategory = null;
            String gateDescription = null;
            // Aggregate every parsed gate so trend tooling (e.g. public-readiness chains) can
            // diff runs without re-parsing step outputs.
            List<Map<String, Object>> gateItems = new ArrayList<>();
            Map<String, Integer> gateCountsByStatus = new HashMap<>();
            for (StepResult step : run.getSteps()) {
                if (step.getOutput() != null) {
                    var parsedGate = GateStatusOutputParser.parse(step.getOutput());
                    if (parsedGate.isPresent()) {
                        var gate = parsedGate.get();
                        if (highestGate == null || gate.status().ordinal() > highestGate.ordinal()) {
                            highestGate = gate.status();
                            gateCategory = gate.category();
                            gateDescription = gate.description();
                        }
                        Map<String, Object> item = new HashMap<>();
                        item.put("status", gate.status().name());
                        if (gate.category() != null) item.put("category", gate.category());
                        if (gate.description() != null) item.put("description", gate.description());
                        item.put("step_index", step.getStepIndex());
                        gateItems.add(item);
                        gateCountsByStatus.merge(gate.status().name(), 1, Integer::sum);
                    }
                }
            }
            if (highestGate != null) {
                context.setRunMetadata("gate_status", highestGate.name());
                if (gateCategory != null) context.setRunMetadata("gate_category", gateCategory);
                if (gateDescription != null) context.setRunMetadata("gate_description", gateDescription);

                // gate_summary aggregates ALL parsed gates so a follow-up run can be diffed
                // against a previous one without re-walking step outputs.
                Map<String, Object> gateSummary = new HashMap<>();
                gateSummary.put("highest", highestGate.name());
                gateSummary.put("counts_by_status", gateCountsByStatus);
                gateSummary.put("items", gateItems);
                context.setRunMetadata("gate_summary", gateSummary);
            }

            // Parse todo_item markers from ALL blocks — collect granular TODO items
            List<TodoOutputParser.ParsedTodoItem> allTodoItems = new ArrayList<>();
            for (StepResult step : run.getSteps()) {
                if (step.getOutput() != null) {
                    allTodoItems.addAll(TodoOutputParser.parse(step.getOutput()));
                }
            }
            if (!allTodoItems.isEmpty()) {
                context.setRunMetadata("todo_items", allTodoItems.stream()
                        .map(t -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("title", t.title());
                            m.put("priority", t.priority());
                            m.put("tags", t.tags());
                            if (t.description() != null) m.put("description", t.description());
                            return m;
                        })
                        .toList());
                log.info("Parsed {} todo_item markers from workflow {}", allTodoItems.size(), workflowName);
            }

            if (run.getStatus() == RunStatus.RUNNING) {
                run.setStatus(RunStatus.COMPLETED);
            }

            if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.COMPLETED_WITH_ERRORS) {
                // Record template verification if workflow was created from a template
                if (templateId != null && !templateId.isBlank()) {
                    try {
                        templateVerificationService.recordSuccessfulRun(workflowId, templateId, project.getId(), run.getId());
                    } catch (Exception e) {
                        log.warn("Failed to record template verification: {}", e.getMessage());
                    }
                }
                if (highestGate == GateStatus.RED) {
                    activityLogService.warn(LogCategory.EXECUTION,
                            "Workflow completed with critical findings: " + runId,
                            "WorkflowExecutor", runId, workflowId);
                } else if (highestGate == GateStatus.YELLOW) {
                    activityLogService.warn(LogCategory.EXECUTION,
                            "Workflow completed with warnings: " + runId,
                            "WorkflowExecutor", runId, workflowId);
                } else {
                    activityLogService.info(LogCategory.EXECUTION,
                            "Workflow execution completed: " + runId,
                            "WorkflowExecutor", runId, workflowId);
                }
            } else if (run.getStatus() == RunStatus.FAILED) {
                activityLogService.error(LogCategory.EXECUTION,
                        "Workflow execution failed: " + runId,
                        "WorkflowExecutor", runId, workflowId);
                try {
                    deadLetterService.recordFailure(run);
                } catch (Exception dlqError) {
                    log.warn("Failed to record DLQ entry for run {}: {}", runId, dlqError.getMessage());
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failRun(runId, "Interrupted while waiting for execution slot");
            return;
        } catch (Throwable t) {
            // Throwable, not Exception: an Error (e.g. NoClassDefFoundError when a build
            // rewrites target/classes under the running JVM) would otherwise slip past every
            // handler, leave the run at RUNNING and only surface as an anonymous ghost run
            // 30 minutes later. See docs/plans/2026-07-27-ghost-run-root-cause-fixes.md
            String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            String message = t.getClass().getSimpleName() + ": " + detail;
            log.error("Workflow execution failed: {}", runId, t);
            if (run != null) {
                run.setStatus(RunStatus.FAILED);
                run.setError(Map.of("message", message));
            }
            activityLogService.error(LogCategory.EXECUTION,
                    "Workflow execution error: " + message,
                    "WorkflowExecutor", runId, workflowId);
        } finally {
            if (semaphoreAcquired) {
                executionSemaphore.release();
            }
            executionRegistry.unregister(runId);

            if (run != null) {
                run.setCompletedAt(Instant.now());
                run.setDurationMs(
                        run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli()
                );

                // Merge context runMetadata into WorkflowRun.metadata
                if (context != null && !context.getRunMetadata().isEmpty()) {
                    Map<String, Object> merged = new HashMap<>(run.getMetadata());
                    merged.putAll(context.getRunMetadata());
                    run.setMetadata(merged);
                }

                runRepository.save(run);

                // Broadcast final status
                broadcaster.broadcastRunStatus(run.getId(), run.getStatus());

                // Publish domain event for listeners (e.g. AutoTodoService)
                try {
                    eventPublisher.publishEvent(new WorkflowRunCompletedEvent(
                            this, run.getId(), workflowId, workflowName,
                            project != null ? project.getId() : null,
                            project != null ? project.getPath() : null,
                            run.getStatus(),
                            run.getMetadata(), workflowMeta, run.getError()));
                } catch (Exception e) {
                    log.error("Failed to publish WorkflowRunCompletedEvent for run {}: {}", run.getId(), e.getMessage(), e);
                }
            } else {
                // run could not be loaded — mark as failed directly
                failRun(runId, "Failed to load workflow run from database");
            }
        }
    }

    /**
     * Marks a run as FAILED directly. Used when the run object couldn't be loaded
     * in the execution thread, or for cleanup of zombie runs.
     */
    private void failRun(String runId, String message) {
        try {
            WorkflowRun r = runRepository.findById(runId).orElse(null);
            if (r != null && r.getStatus() == RunStatus.RUNNING) {
                r.setStatus(RunStatus.FAILED);
                r.setCompletedAt(Instant.now());
                r.setDurationMs(r.getCompletedAt().toEpochMilli() - r.getStartedAt().toEpochMilli());
                r.setError(Map.of("message", message));
                runRepository.save(r);
                broadcaster.broadcastRunStatus(runId, RunStatus.FAILED);
                log.warn("Force-failed zombie run {}: {}", runId, message);
            }
        } catch (Exception e) {
            log.error("Failed to mark run {} as failed: {}", runId, e.getMessage());
        }
    }

    /**
     * Cleans up zombie runs on startup — any runs stuck in RUNNING status
     * from a previous application lifecycle.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanupZombieRuns() {
        // Collect all runs that were in-flight when the application was last shut down
        List<WorkflowRun> zombies = new java.util.ArrayList<>();
        zombies.addAll(runRepository.findByStatus(RunStatus.RUNNING));
        zombies.addAll(runRepository.findByStatus(RunStatus.PAUSED));
        if (zombies.isEmpty()) return;
        log.warn("Found {} run(s) stuck in RUNNING/PAUSED status — marking as INTERRUPTED", zombies.size());
        for (WorkflowRun zombie : zombies) {
            zombie.setStatus(RunStatus.INTERRUPTED);
            zombie.setCompletedAt(Instant.now());
            zombie.setDurationMs(zombie.getCompletedAt().toEpochMilli() - zombie.getStartedAt().toEpochMilli());
            zombie.setError(Map.of("message", "Workflow was interrupted by an application restart"));
            runRepository.save(zombie);
            log.info("Marked interrupted run: {}", zombie.getId());
        }
        activityLogService.warn(LogCategory.EXECUTION,
                "Startup cleanup: marked " + zombies.size() + " run(s) as INTERRUPTED",
                "WorkflowExecutor");
    }

    /**
     * Resumes an INTERRUPTED workflow run from the last successfully completed step.
     * If no steps completed, falls back to a full retry.
     */
    public WorkflowRun resumeFromStep(String runId) {
        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRun", runId));

        if (run.getStatus() != RunStatus.INTERRUPTED) {
            throw new WorkflowExecutionException("Run " + runId + " cannot be resumed: status is " + run.getStatus());
        }

        List<StepResult> completedSteps = stepResultRepository
                .findByWorkflowRunIdOrderByStepIndexAsc(runId)
                .stream()
                .filter(s -> s.getStatus() == StepStatus.COMPLETED)
                .toList();

        if (completedSteps.isEmpty()) {
            log.info("Resume requested for run {} but no completed steps found — falling back to full retry", runId);
            return execute(run.getWorkflow(), run.getInputContext());
        }

        int resumeFromIndex = completedSteps.get(completedSteps.size() - 1).getStepIndex() + 1;

        // Reconstruct context: inputContext + step outputs from DB + accumulated user vars (contextDelta)
        Map<String, Object> reconstructed = new HashMap<>(run.getInputContext() != null ? run.getInputContext() : Map.of());
        String lastOutput = "";
        for (StepResult step : completedSteps) {
            String out = step.getOutput() != null ? step.getOutput() : "";
            reconstructed.put("step_" + step.getStepIndex() + "_output", out);
            lastOutput = out;
            if (step.getContextDelta() != null) {
                reconstructed.putAll(step.getContextDelta());
            }
        }
        final String previousOutput = lastOutput;
        final Map<String, Object> contextForResume = Map.copyOf(reconstructed);

        // Load workflow data eagerly before updating run status
        final Workflow workflow = run.getWorkflow();
        final Project project;
        final List<BlockDefinition> blocks;
        final List<Skill> skills;
        final String instructions;
        final String model;
        final String provider;
        final String effort;
        final Map<String, Object> workflowMeta;
        final String templateId;
        Map<String, Skill> resumeBlockSkillLookup;
        try {
            project = workflow.getProject();
            blocks = List.copyOf(workflow.getBlocks());
            skills = List.copyOf(workflow.getActivatedSkills());
            instructions = workflow.getInstructions();
            model = workflow.getModel();
            provider = workflow.getProvider();
            effort = workflow.getEffort();
            workflowMeta = Map.copyOf(workflow.getMetadata());
            templateId = workflow.getTemplateId();

            // Pre-resolve per-block skill IDs (same logic as execute())
            Set<String> wfSkillIds = skills.stream().map(Skill::getId).collect(Collectors.toSet());
            Set<String> blkSkillIds = blocks.stream()
                    .filter(b -> b.getSkillIds() != null)
                    .flatMap(b -> b.getSkillIds().stream())
                    .filter(id -> !wfSkillIds.contains(id))
                    .collect(Collectors.toSet());
            resumeBlockSkillLookup = blkSkillIds.isEmpty()
                    ? Map.of()
                    : skillRepository.findAllById(blkSkillIds).stream()
                            .collect(Collectors.toMap(Skill::getId, s -> s));
        } catch (Exception e) {
            throw new WorkflowExecutionException("Failed to load workflow data for resume: " + e.getMessage(), e);
        }

        // Reset run to RUNNING
        run.setStatus(RunStatus.RUNNING);
        run.setError(null);
        run.setCompletedAt(null);
        runRepository.save(run);
        broadcaster.broadcastRunStatus(runId, RunStatus.RUNNING);

        final String wfId = workflow.getId();
        final String wfName = workflow.getName();
        final int resumeIdx = resumeFromIndex;
        final Map<String, Skill> finalResumeBlockSkillLookup = resumeBlockSkillLookup;
        activityLogService.info(LogCategory.EXECUTION,
                "Resuming workflow from step " + resumeIdx + ": " + wfName,
                "WorkflowExecutor", runId, wfId);

        virtualThreadExecutor.submit(() -> executeBlocks(
                runId, wfId, wfName, project, blocks, skills, finalResumeBlockSkillLookup,
                contextForResume, instructions, model, provider, effort, workflowMeta, templateId,
                resumeIdx, previousOutput));

        return run;
    }

    /**
     * Computes the context delta: user-defined variables that are not derivable
     * from step outputs or system-injected keys.
     */
    private Map<String, Object> computeContextDelta(ExecutionContext context, Map<String, Object> inputContext) {
        Map<String, Object> delta = new HashMap<>(context.getVariables());
        // Remove auto-set step_N_output variables — derivable from StepResult.output
        delta.entrySet().removeIf(e -> e.getKey().matches("step_\\d+_output"));
        // Remove system-injected constants
        SYSTEM_VAR_KEYS.forEach(delta::remove);
        // Remove inputContext vars — already stored in WorkflowRun.inputContext
        if (inputContext != null) {
            inputContext.keySet().forEach(delta::remove);
        }
        return delta.isEmpty() ? null : delta;
    }

    // ── Delegation to WorkflowExecutionRegistry ──────────────────────────

    public void pauseExecution(String runId) {
        executionRegistry.pauseExecution(runId);
    }

    public void resumeExecution(String runId) {
        executionRegistry.resumeExecution(runId);
    }

    public Set<String> getActiveContainerIds() {
        return executionRegistry.getActiveContainerIds();
    }

    public String getContainerIdForRun(String runId) {
        return executionRegistry.getContainerIdForRun(runId);
    }

    public void cancelExecution(String runId) {
        ExecutionContext context = executionRegistry.get(runId);
        if (context != null) {
            context.cancel();
            log.info("Cancelled execution: {}", runId);
        } else {
            // Ghost run: no active thread, but possibly still RUNNING in the DB
            try {
                runRepository.findById(runId).ifPresent(run -> {
                    if (run.getStatus() == RunStatus.RUNNING) {
                        log.warn("Force-cancelling ghost run {} — not in activeExecutions but status=RUNNING", runId);
                        run.setStatus(RunStatus.CANCELLED);
                        run.setCompletedAt(Instant.now());
                        run.setDurationMs(run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli());
                        run.setError(Map.of("message", "Force-cancelled: no active execution thread found (ghost run)"));
                        runRepository.save(run);
                        broadcaster.broadcastRunStatus(runId, RunStatus.CANCELLED);
                        activityLogService.warn(LogCategory.EXECUTION,
                                "Ghost run force-cancelled: " + runId, "WorkflowExecutor", runId, null);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to force-cancel ghost run {}: {}", runId, e.getMessage());
            }
        }
    }

    public void resolveApproval(String runId, String answer) {
        executionRegistry.resolveApproval(runId, answer);
    }

    public int getActiveRunCount() {
        return executionRegistry.getActiveRunCount();
    }

    public boolean isRunActive(String runId) {
        return executionRegistry.isRunActive(runId);
    }

    public int getPendingApprovalCount() {
        return executionRegistry.getPendingApprovalCount();
    }

    public ApprovalRequest getPendingApproval(String runId) {
        return executionRegistry.getPendingApproval(runId);
    }

    public String executeAdHocCommand(String runId, String command) {
        ExecutionContext context = executionRegistry.get(runId);
        if (context == null) {
            throw new WorkflowExecutionException("No active execution found for run: " + runId);
        }
        log.info("Executing ad-hoc command in run {}: {}", runId, command);
        var result = commandExecutorService.executeCommand(context.getContainerId(), "claude", command);

        // Broadcast as a log message
        broadcaster.broadcastLogMessage(runId, "command", Map.of(
                "input", command,
                "output", result.output() != null ? result.output() : ""
        ));

        return result.output() != null ? result.output() : "";
    }

    private BlockExecutor findExecutor(String blockType) {
        return blockExecutors.stream()
                .filter(e -> e.supports(blockType))
                .findFirst()
                .orElseThrow(() -> new WorkflowExecutionException(
                        "No executor found for block type: " + blockType));
    }

    private String ensureContainerRunning(Project project, String runId) {
        // Try to get existing running container
        try {
            return containerService.getDockerContainerId(project);
        } catch (Exception e) {
            log.warn("No running container for project {} ({}), attempting to start one...",
                    project.getName(), e.getMessage());
        }

        // Try to start a new container
        try {
            broadcaster.broadcastLogMessage(runId, "container_starting",
                    Map.of("message", "Container wird gestartet für Projekt: " + project.getName()));

            Container container = containerService.createAndStartContainer(project);

            broadcaster.broadcastLogMessage(runId, "container_started",
                    Map.of("message", "Container gestartet", "containerId", container.getDockerContainerId()));

            return container.getDockerContainerId();
        } catch (Exception e) {
            // Container creation failed — continue without container (host-based execution still works)
            log.warn("Failed to start container for project {} — continuing with host-based execution: {}",
                    project.getName(), e.getMessage());
            broadcaster.broadcastLogMessage(runId, "container_skipped",
                    Map.of("message", "Container konnte nicht gestartet werden, Ausführung erfolgt auf dem Host"));
            return null;
        }
    }

    private void updateContainerActivity(String containerId) {
        try {
            containerRepository.findByDockerContainerId(containerId).ifPresent(container -> {
                container.setLastActivityAt(Instant.now());
                containerRepository.save(container);
            });
        } catch (Exception e) {
            log.debug("Failed to update container activity: {}", e.getMessage());
        }
    }
}
