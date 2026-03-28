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
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.memory.WorkflowMemoryService;
import com.automation.service.todo.GateStatusOutputParser;
import com.automation.service.project.ContainerService;
import com.automation.service.project.ProjectContextService;
import com.automation.service.system.SystemIdentityService;
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

import java.util.concurrent.ConcurrentHashMap;
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

    private static final java.util.Set<String> SYSTEM_VAR_KEYS = java.util.Set.of(
            "workflow_run_id", "workflow_id", "workflow_name", "project_path",
            "model", "provider", "started_at"
    );

    private final Map<String, ExecutionContext> activeExecutions = new ConcurrentHashMap<>();

    /**
     * Starts workflow execution asynchronously.
     * Creates a WorkflowRun with RUNNING status and returns it immediately.
     * The actual block execution happens in a background virtual thread.
     */
    public WorkflowRun execute(Workflow workflow, Map<String, Object> inputContext) {
        return execute(workflow, inputContext, null);
    }

    public WorkflowRun execute(Workflow workflow, Map<String, Object> inputContext, String triggeredByScheduleId) {
        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .triggeredByScheduleId(triggeredByScheduleId)
                .build();
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
        final Map<String, Object> workflowMeta;
        final String templateId;
        final Map<String, Object> contextVars;
        try {
            project = workflow.getProject();
            blocks = List.copyOf(workflow.getBlocks());
            skills = List.copyOf(workflow.getActivatedSkills());
            instructions = workflow.getInstructions();
            model = workflow.getModel();
            provider = workflow.getProvider();
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
        activityLogService.info(LogCategory.EXECUTION,
                "Workflow execution started: " + workflowName,
                "WorkflowExecutor", runId, workflowId);
        virtualThreadExecutor.submit(() -> executeBlocks(runId, workflowId, workflowName, project, blocks, skills, contextVars, instructions, model, provider, workflowMeta, templateId, 0, null));

        return run;
    }

    /**
     * Core execution loop.
     * @param skipToStepIndex for resume: skip blocks with index < this value (0 = no skip)
     * @param resumePreviousOutput for resume: pre-seed previousOutput from the last completed step
     */
    private void executeBlocks(String runId, String workflowId, String workflowName, Project project, List<BlockDefinition> blocks,
                               List<Skill> skills, Map<String, Object> inputContext,
                               String instructions, String model, String provider, Map<String, Object> workflowMeta,
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
            }

            // Inject workflow & run metadata as template variables
            context.setVariable("workflow_run_id", runId);
            context.setVariable("workflow_id", workflowId);
            context.setVariable("workflow_name", workflowName != null ? workflowName : "");
            context.setVariable("project_path", project.getPath() != null ? project.getPath() : "");
            context.setVariable("model", model != null ? model : "");
            context.setVariable("provider", provider != null ? provider : "");
            context.setVariable("started_at", java.time.Instant.now().toString());

            // Stamp run metadata with system identity
            context.setRunMetadata("system", systemIdentity.getName());
            context.setRunMetadata("systemVersion", systemIdentity.getVersion());

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

            activeExecutions.put(runId, context);

            commandExecutorService.injectSkillContextOnHost(project.getPath(), skills);

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

                BlockExecutor executor = findExecutor(block.getType());

                // Pre-create RUNNING placeholder so UI shows the step immediately
                int stepIndex = context.preAllocateStepIndex();

                // For resumed runs: skip blocks that already completed in the previous execution
                if (stepIndex < skipToStepIndex) {
                    continue;
                }

                StepResult step = new StepResult();
                step.setStepIndex(stepIndex);
                step.setStartedAt(Instant.now());
                step.setStatus(StepStatus.RUNNING);
                step.setMetadata(Map.of("blockType", block.getType()));
                run.addStep(step);
                runRepository.save(run);
                broadcaster.broadcastStepResult(run.getId(), step);

                // Execute the block — nextStepIndex() returns the pre-allocated index
                StepResult result = executor.execute(block, context);

                // Update the persisted step with the actual results
                step.setInput(result.getInput());
                step.setOutput(result.getOutput());
                step.setStatus(result.getStatus());
                step.setCompletedAt(result.getCompletedAt());
                step.setDurationMs(result.getDurationMs());
                if (result.getMetadata() != null) {
                    step.setMetadata(result.getMetadata());
                }
                context.setPreviousOutput(step.getOutput());
                context.setVariable("step_" + step.getStepIndex() + "_output",
                        step.getOutput() != null ? step.getOutput() : "");

                // Persist context delta for potential future resume
                if (step.getStatus() == StepStatus.COMPLETED) {
                    step.setContextDelta(computeContextDelta(context, run.getInputContext()));
                }

                // Update container last activity (skip if no container)
                if (containerId != null) {
                    updateContainerActivity(containerId);
                }

                broadcaster.broadcastStepResult(run.getId(), step);
                runRepository.save(run);

                if (step.getStatus() == StepStatus.FAILED && block.shouldStopOnFailure()) {
                    run.setStatus(RunStatus.FAILED);
                    String output = step.getOutput() != null ? step.getOutput() : "";
                    String message = "Step " + step.getStepIndex() + " (" + block.getType() + ") failed: "
                            + (output.length() > 500 ? output.substring(0, 500) + "..." : output);
                    run.setError(Map.of("step", step.getStepIndex(), "blockType", block.getType(), "message", message));
                    break;
                }
            }

            // Parse gate_status markers from ALL blocks — highest severity wins
            // GateStatus ordinal: GREEN(0) < YELLOW(1) < RED(2)
            GateStatus highestGate = null;
            String gateCategory = null;
            String gateDescription = null;
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
                    }
                }
            }
            if (highestGate != null) {
                context.setRunMetadata("gate_status", highestGate.name());
                if (gateCategory != null) context.setRunMetadata("gate_category", gateCategory);
                if (gateDescription != null) context.setRunMetadata("gate_description", gateDescription);
            }

            if (run.getStatus() == RunStatus.RUNNING) {
                if (highestGate == GateStatus.YELLOW) {
                    run.setStatus(RunStatus.COMPLETED_WITH_ERRORS);
                } else {
                    run.setStatus(RunStatus.COMPLETED);
                }
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
                if (run.getStatus() == RunStatus.COMPLETED_WITH_ERRORS) {
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
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failRun(runId, "Interrupted while waiting for execution slot");
            return;
        } catch (Exception e) {
            log.error("Workflow execution failed: {}", runId, e);
            if (run != null) {
                run.setStatus(RunStatus.FAILED);
                run.setError(Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            }
            activityLogService.error(LogCategory.EXECUTION,
                    "Workflow execution error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    "WorkflowExecutor", runId, workflowId);
        } finally {
            if (semaphoreAcquired) {
                executionSemaphore.release();
            }
            activeExecutions.remove(runId);

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
                            project.getId(), project.getPath(), run.getStatus(),
                            run.getMetadata(), workflowMeta, run.getError()));
                } catch (Exception e) {
                    log.warn("Failed to publish WorkflowRunCompletedEvent: {}", e.getMessage());
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
        final Map<String, Object> workflowMeta;
        final String templateId;
        try {
            project = workflow.getProject();
            blocks = List.copyOf(workflow.getBlocks());
            skills = List.copyOf(workflow.getActivatedSkills());
            instructions = workflow.getInstructions();
            model = workflow.getModel();
            provider = workflow.getProvider();
            workflowMeta = Map.copyOf(workflow.getMetadata());
            templateId = workflow.getTemplateId();
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
        activityLogService.info(LogCategory.EXECUTION,
                "Resuming workflow from step " + resumeIdx + ": " + wfName,
                "WorkflowExecutor", runId, wfId);

        virtualThreadExecutor.submit(() -> executeBlocks(
                runId, wfId, wfName, project, blocks, skills,
                contextForResume, instructions, model, provider, workflowMeta, templateId,
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

    public void pauseExecution(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.pause();
            log.info("Paused execution: {}", runId);
            // The execution loop will persist the PAUSED status when it enters the pause wait
        } else {
            log.warn("pauseExecution: no active execution found for run {}", runId);
        }
    }

    public void resumeExecution(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.resume();
            log.info("Resumed execution: {}", runId);
            // The execution loop will persist RUNNING status when it exits the pause wait
        } else {
            log.warn("resumeExecution: no active execution found for run {}", runId);
        }
    }

    public Set<String> getActiveContainerIds() {
        return activeExecutions.values().stream()
                .map(ExecutionContext::getContainerId)
                .collect(Collectors.toSet());
    }

    public String getContainerIdForRun(String runId) {
        ExecutionContext ctx = activeExecutions.get(runId);
        return ctx != null ? ctx.getContainerId() : null;
    }

    public void cancelExecution(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
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
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.resolveApproval(answer);
            log.info("Resolved approval for run {}: {}", runId, answer);
        }
    }

    public int getActiveRunCount() {
        return activeExecutions.size();
    }

    public boolean isRunActive(String runId) {
        return activeExecutions.containsKey(runId);
    }

    public int getPendingApprovalCount() {
        return (int) activeExecutions.values().stream()
                .filter(ctx -> ctx.getCurrentApprovalRequest().get() != null)
                .count();
    }

    public ApprovalRequest getPendingApproval(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            return context.getCurrentApprovalRequest().get();
        }
        return null;
    }

    public String executeAdHocCommand(String runId, String command) {
        ExecutionContext context = activeExecutions.get(runId);
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
