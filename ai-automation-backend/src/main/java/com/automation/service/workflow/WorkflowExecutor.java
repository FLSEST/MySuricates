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
import com.automation.service.project.ContainerService;
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
    private final ExecutionContextFactory executionContextFactory;
    private final ActivityLogService activityLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final RunCompletionHandler completionHandler;
    private final WorkflowExecutionRegistry executionRegistry;
    private final BlockOutputValidator blockOutputValidator;

    // Active execution tracking is delegated to WorkflowExecutionRegistry

    /**
     * Starts workflow execution asynchronously.
     * Creates a WorkflowRun with RUNNING status and returns it immediately.
     * The actual block execution happens in a background virtual thread.
     */
    /** Detached copy of everything the async loop needs — taken while the JPA session is open. */
    private record WorkflowSnapshot(Project project, List<BlockDefinition> blocks, List<Skill> skills,
                                    String instructions, String model, String provider, String effort,
                                    Map<String, Object> workflowMeta, String templateId,
                                    Map<String, Skill> blockSkillLookup) {}

    /**
     * Eagerly copies the lazy workflow graph and pre-resolves per-block skill IDs to entities
     * (single DB query). Must run while the JPA session is open; callers wrap it in try/catch
     * so a LazyInitializationException fails the run instead of leaving a ghost.
     */
    private WorkflowSnapshot snapshot(Workflow workflow) {
        List<BlockDefinition> blocks = List.copyOf(workflow.getBlocks());
        List<Skill> skills = List.copyOf(workflow.getActivatedSkills());
        Set<String> workflowSkillIds = skills.stream().map(Skill::getId).collect(Collectors.toSet());
        Set<String> blockSkillIds = blocks.stream()
                .filter(b -> b.getSkillIds() != null)
                .flatMap(b -> b.getSkillIds().stream())
                .filter(id -> !workflowSkillIds.contains(id))
                .collect(Collectors.toSet());
        Map<String, Skill> blockSkillLookup = blockSkillIds.isEmpty()
                ? Map.of()
                : skillRepository.findAllById(blockSkillIds).stream()
                        .collect(Collectors.toMap(Skill::getId, s -> s));
        if (!blockSkillIds.isEmpty() && blockSkillLookup.size() < blockSkillIds.size()) {
            Set<String> missing = new java.util.HashSet<>(blockSkillIds);
            missing.removeAll(blockSkillLookup.keySet());
            log.warn("Per-block skill IDs not found (deleted?): {}", missing);
        }
        return new WorkflowSnapshot(workflow.getProject(), blocks, skills, workflow.getInstructions(),
                workflow.getModel(), workflow.getProvider(), workflow.getEffort(),
                Map.copyOf(workflow.getMetadata()), workflow.getTemplateId(), blockSkillLookup);
    }

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
        final WorkflowSnapshot wf;
        final Map<String, Object> contextVars;
        try {
            wf = snapshot(workflow);

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
        registerProvisionalContext(runId);
        submitExecution(runId, () -> executeBlocks(runId, workflowId, workflowName, wf, contextVars, 0, null));

        return run;
    }

    /**
     * Core execution loop.
     * @param skipToStepIndex for resume: skip blocks with index < this value (0 = no skip)
     * @param resumePreviousOutput for resume: pre-seed previousOutput from the last completed step
     */
    private void executeBlocks(String runId, String workflowId, String workflowName, WorkflowSnapshot wf,
                               Map<String, Object> inputContext, int skipToStepIndex, String resumePreviousOutput) {
        Project project = wf.project();
        List<BlockDefinition> blocks = wf.blocks();
        List<Skill> skills = wf.skills();
        Map<String, Skill> blockSkillLookup = wf.blockSkillLookup();
        String instructions = wf.instructions();
        String model = wf.model();
        String provider = wf.provider();
        String effort = wf.effort();
        Map<String, Object> workflowMeta = wf.workflowMeta();
        String templateId = wf.templateId();
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

            // Cancelled while the run was still queued (see registerProvisionalContext): stop before
            // anything expensive happens. The finally block saves CANCELLED, broadcasts, publishes
            // the completion event and unregisters — no container, no steps, no completion handler.
            ExecutionContext queued = executionRegistry.get(runId);
            if (queued != null && queued.shouldStop()) {
                run.setStatus(RunStatus.CANCELLED);
                return;
            }

            String containerId = ensureContainerRunning(project, runId);

            context = executionContextFactory.create(new ExecutionContextFactory.ContextSeed(
                    runId, workflowId, workflowName, project, containerId, inputContext,
                    instructions, model, provider, effort, resumePreviousOutput));

            // Swap the provisional context for the real one and carry over any control flag that
            // arrived in between. WorkflowExecutionRegistry.cancel() double-checks from the other
            // side, so a cancel racing this put lands on the real context either way.
            ExecutionContext previous = executionRegistry.register(runId, context);
            if (previous != null) {
                if (previous.shouldStop()) {
                    context.cancel();
                }
                if (previous.isPaused()) {
                    context.pause();
                }
            }

            executionContextFactory.writeProjectContextFiles(project, workflowId, skills);

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
                    step.setContextDelta(ContextDeltaCalculator.compute(context, run.getInputContext()));
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

            completionHandler.complete(run, context, workflowId, workflowName, templateId, project.getId());

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
            // Unregister last: between unregister and the final save the watchdog would see
            // DB=RUNNING with an empty registry and flag a false-positive ghost run.
            try {
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
            } finally {
                executionRegistry.unregister(runId);
            }
        }
    }

    /**
     * Registers a placeholder context for a run that is about to be submitted. Without it the run
     * would be observable (DB status RUNNING) but not controllable for as long as the execution
     * thread needs to get through the semaphore and the container start — a cancel arriving in
     * that window took the ghost branch and was then overrun by the thread that kept going.
     * The execution thread swaps this context for the real one and carries any flag over.
     *
     * <p>Side effect (intended): {@link WorkflowExecutionRegistry#getActiveRunCount()} now counts
     * queued runs too, which is what {@code GhostRunWatchdogService.reconcilePermits} needs — a run
     * parked in {@code executionSemaphore.acquire()} or in the container start holds (or is about to
     * hold) a permit and must not be mistaken for a leak.
     */
    private void registerProvisionalContext(String runId) {
        executionRegistry.register(runId, ExecutionContext.builder().workflowRunId(runId).build());
    }

    /**
     * Hands the run to the executor. If the pool rejects it (shutdown in progress), the run would
     * otherwise stay RUNNING in the DB with no thread ever finishing it — so fail it right away.
     */
    private void submitExecution(String runId, Runnable execution) {
        try {
            virtualThreadExecutor.submit(execution);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // No thread will ever reach executeBlocks' finally block, so drop the provisional
            // context here — otherwise the run stays "active" in the registry forever.
            executionRegistry.unregister(runId);
            failRun(runId, "Executor rejected the run (shutting down?): " + e.getMessage());
            throw new WorkflowExecutionException("Executor rejected workflow run " + runId, e);
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
        final WorkflowSnapshot wf;
        try {
            wf = snapshot(workflow);
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

        registerProvisionalContext(runId);
        submitExecution(runId, () -> executeBlocks(runId, wfId, wfName, wf, contextForResume, resumeIdx, previousOutput));

        return run;
    }

    // ── Run control (pause/resume/cancel/approval) — status queries such as active run count,
    // container ids or pending approvals go straight to WorkflowExecutionRegistry.

    public void pauseExecution(String runId) {
        executionRegistry.pauseExecution(runId);
    }

    public void resumeExecution(String runId) {
        executionRegistry.resumeExecution(runId);
    }

    public void cancelExecution(String runId) {
        // Registered covers both the running and the still-queued case (provisional context), so
        // only a run with no context at all falls through to the ghost branch.
        if (executionRegistry.cancel(runId)) {
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
