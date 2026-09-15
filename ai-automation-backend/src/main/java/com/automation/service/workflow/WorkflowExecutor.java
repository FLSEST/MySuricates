package com.automation.service.workflow;

import com.automation.entity.*;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
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
    private final SkillRepository skillRepository;
    private final ExecutionBroadcaster broadcaster;
    private final ExecutorService virtualThreadExecutor;
    private final DynamicSemaphore executionSemaphore;
    private final ExecutionContextFactory executionContextFactory;
    private final ActivityLogService activityLogService;
    private final RunCompletionHandler completionHandler;
    private final WorkflowExecutionRegistry executionRegistry;
    private final BlockSkillContext skillContext;
    private final BlockStepRunner stepRunner;
    private final RunFinalizer finalizer;
    private final RunWorkspaceService workspaces;
    private final RunAdmission admission;

    // Active execution tracking is delegated to WorkflowExecutionRegistry

    /** Detached copy of everything the async loop needs — taken while the JPA session is open. */
    private record WorkflowSnapshot(Project project, List<BlockDefinition> blocks, List<Skill> skills,
                                    String instructions, String model, String provider, String effort,
                                    Map<String, Object> workflowMeta, String templateId,
                                    Map<String, Skill> blockSkillLookup) {}

    /**
     * Eagerly copies the lazy workflow graph and pre-resolves per-block skill IDs to entities (one
     * DB query). Must run while the JPA session is open; callers catch so a failure fails the run.
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
                RunWorkspaceService.policy(workflow.getMetadata(), blocks), workflow.getTemplateId(), blockSkillLookup);
    }

    public WorkflowRun execute(Workflow workflow, Map<String, Object> inputContext, TriggerSource triggerSource) {
        return execute(workflow, inputContext, null, null, triggerSource);
    }

    /** Asynchronous: returns the RUNNING run immediately; the blocks execute on a virtual thread. */
    public WorkflowRun execute(Workflow workflow, Map<String, Object> inputContext, String triggeredByScheduleId,
                               Map<String, Object> initialRunMetadata, TriggerSource triggerSource) {
        RunAdmission.Ticket ticket = admission.admit(workflow, triggerSource, inputContext); // before any run row exists
        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .workflowName(workflow.getName())
                .status(RunStatus.RUNNING)
                .startedAt(Instant.now())
                .triggeredByScheduleId(triggeredByScheduleId)
                .triggerSource(triggerSource).definitionHash(ticket.definitionHash()).definitionVersionId(ticket.definitionVersionId())
                .build();
        if (initialRunMetadata != null && !initialRunMetadata.isEmpty()) {
            run.setMetadata(new HashMap<>(initialRunMetadata));
        }
        run = runRepository.save(run);
        executionRegistry.registerProvisional(run.getId()); // listable ⇒ cancellable, no ghost branch

        // Eagerly initialize lazy collections before leaving the transaction/session. A
        // LazyInitializationException here fails the run instead of leaving a ghost behind.
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
            activityLogService.info(LogCategory.EXECUTION, "Workflow execution started: " + workflow.getName(),
                    "WorkflowExecutor", run.getId(), workflow.getId());
        } catch (Throwable t) {
            // Throwable, not Exception (same reason as the block loop below): an Error slipping
            // past here would leave the provisional registration behind, and the watchdog protects
            // registered runs on purpose — the run would hang RUNNING until the next restart.
            executionRegistry.unregister(run.getId()); // nothing will ever run for this id
            log.error("Failed to initialize workflow data for run {}: {}", run.getId(), t.getMessage(), t);
            run.setStatus(RunStatus.FAILED);
            run.setCompletedAt(Instant.now());
            run.setError(Map.of("message", "Failed to initialize workflow data: " + t.getMessage()));
            runRepository.save(run);
            if (t instanceof Error error) throw error; // never swallow an Error
            throw new WorkflowExecutionException("Failed to initialize workflow '" + workflow.getName() + "' for execution", t);
        }

        // Execute asynchronously — return the run immediately
        final String runId = run.getId();
        final String workflowId = workflow.getId();
        final String workflowName = workflow.getName();
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
            run = runRepository.findById(runId).orElseThrow();
            workflowMeta = RunWorkspaceService.policyForRun(run, workflowMeta);
            if (!executionRegistry.cancelRequested(runId)) workspaces.pinSource(run, project, workflowMeta, inputContext);
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

            if (executionRegistry.cancelRequested(runId)) {   // cancel arrived while still queued
                run.setStatus(RunStatus.CANCELLED);           // finally: save, broadcast, unregister
                return;
            }

            boolean isolated = RunWorkspaceService.enabled(workflowMeta);
            String containerId = isolated ? null : RunContainerPreparation.ensureRunning(project, runId, containerService, broadcaster);

            context = executionContextFactory.create(new ExecutionContextFactory.ContextSeed(
                    runId, workflowId, workflowName, project, containerId, inputContext,
                    instructions, model, provider, effort, resumePreviousOutput, run.getDefinitionHash(), run.getTriggerSource()));

            executionRegistry.promote(runId, context);
            if (skipToStepIndex > 0) ResumeOutputSeeder.seed(context, run.getSteps(), skipToStepIndex); // f9384e48
            Project executionProject = isolated
                    ? workspaces.prepare(run, project, workflowMeta, context, run.getMetadata().containsKey("workspace")) : project;
            executionContextFactory.writeProjectContextFiles(executionProject, workflowId, skills);

            for (BlockDefinition block : blocks) {
                if (context.shouldStop()) {
                    run.setStatus(RunStatus.CANCELLED);
                    break;
                }

                waitWhilePaused(run, context);

                // Per-block skill context: merge block-level skills with workflow-level skills
                boolean hasBlockSkills = skillContext.applyForBlock(block, executionProject, skills, blockSkillLookup);

                BlockExecutor executor = findExecutor(block.getType());

                // Pre-create RUNNING placeholder so UI shows the step immediately
                int stepIndex = context.preAllocateStepIndex();

                // For resumed runs: skip blocks that already completed in the previous execution
                if (stepIndex < skipToStepIndex) {
                    // Restore baseline skill context if this block had extra skills
                    if (hasBlockSkills) {
                        skillContext.restore(executionProject, skills);
                    }
                    continue;
                }

                StepResult step = stepRunner.startStep(run, stepIndex, block);
                stepRunner.runStep(step, block, executor, context, run, containerId);

                // Restore baseline skill context after a block with extra skills
                if (hasBlockSkills) {
                    skillContext.restore(executionProject, skills);
                }

                if (context.shouldStop()) {
                    run.setStatus(RunStatus.CANCELLED);
                    break;
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

        } catch (java.util.concurrent.CancellationException e) {
            if (run != null) run.setStatus(RunStatus.CANCELLED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (run != null) {
                run.setStatus(executionRegistry.cancelRequested(runId) ? RunStatus.CANCELLED : RunStatus.FAILED);
                run.setError(Map.of("message", "Interrupted while waiting for execution slot"));
            } else {
                failRun(runId, "Interrupted while waiting for execution slot");
            }
            return;
        } catch (Throwable t) {
            // Throwable, not Exception: an Error (NoClassDefFoundError from a build rewriting
            // target/classes) would leave the run at RUNNING and surface as an anonymous ghost run
            // 30 min later. See docs/plans/2026-07-27-ghost-run-root-cause-fixes.md
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
            // Order (TODO 7bb17b13): terminal save -> unregister -> release permit. Releasing first let a
            // queued run start while this one still wrote its final row on the single SQLite connection;
            // unregistering before the save let the watchdog flag a false-positive ghost run.
            try {
                if (run != null) {
                    finalizer.finish(run, context, workflowId, workflowName, project, workflowMeta);
                } else {
                    failRun(runId, "Failed to load workflow run from database");   // run could not be loaded
                }
            } finally {
                try { executionRegistry.unregister(runId); }
                finally { if (semaphoreAcquired) executionSemaphore.release(); }
            }
        }
    }

    /**
     * Parks the block loop while the run is paused: PAUSED is persisted and broadcast once, then the
     * thread polls until the run is resumed or cancelled and announces RUNNING again.
     */
    private void waitWhilePaused(WorkflowRun run, ExecutionContext context) {
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
    }

    /**
     * Hands the run to the executor. If the pool rejects it (shutdown in progress), the run would
     * otherwise stay RUNNING in the DB with no thread ever finishing it — so fail it right away.
     */
    private void submitExecution(String runId, Runnable execution) {
        try {
            virtualThreadExecutor.submit(execution);
        } catch (Throwable t) {
            executionRegistry.unregister(runId); // no thread will reach executeBlocks' finally
            boolean rejected = t instanceof java.util.concurrent.RejectedExecutionException;
            failRun(runId, (rejected ? "Executor rejected the run (shutting down?): " : "Could not submit the run: ")
                    + t.getMessage());
            if (rejected) {
                throw new WorkflowExecutionException("Executor rejected workflow run " + runId, t);
            }
            throw t;
        }
    }

    /** FAILED directly — the run object could not be loaded, or a zombie run is being cleaned up. */
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

    /** Cleans up runs stuck in RUNNING/PAUSED from a previous application lifecycle. */
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

    /** Resumes an INTERRUPTED run after its last completed step; no completed step ⇒ full retry. */
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

        if (completedSteps.isEmpty() && !run.getMetadata().containsKey("workspace")) {
            log.info("Resume requested for run {} but no completed steps found — falling back to full retry", runId);
            return execute(run.getWorkflow(), run.getInputContext(), TriggerSource.RESUME);
        }

        int resumeFromIndex = completedSteps.isEmpty() ? 0 : completedSteps.getLast().getStepIndex() + 1;

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

        // Announce first: a failing log write leaves the run INTERRUPTED, not RUNNING-with-nobody.
        activityLogService.info(LogCategory.EXECUTION,
                "Resuming workflow from step " + resumeFromIndex + ": " + workflow.getName(),
                "WorkflowExecutor", runId, workflow.getId());

        // Reset run to RUNNING — the resumed segment runs today's definition, so provenance is re-taken
        RunAdmission.Ticket ticket = admission.admit(workflow, TriggerSource.RESUME, contextForResume);
        run.setTriggerSource(TriggerSource.RESUME); run.setDefinitionHash(ticket.definitionHash()); run.setDefinitionVersionId(ticket.definitionVersionId());
        run.setStatus(RunStatus.RUNNING);
        run.setError(null);
        run.setCompletedAt(null);
        runRepository.save(run);

        final String wfId = workflow.getId();
        final String wfName = workflow.getName();
        final int resumeIdx = resumeFromIndex;
        executionRegistry.registerProvisional(runId); // controllable as soon as it is RUNNING
        try {
            broadcaster.broadcastRunStatus(runId, RunStatus.RUNNING);
            submitExecution(runId, () -> executeBlocks(runId, wfId, wfName, wf, contextForResume, resumeIdx, previousOutput));
        } catch (Throwable t) {
            executionRegistry.unregister(runId); // no thread took over — do not leave it registered
            throw t;
        }

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
        // Covers the running and the still-queued case; only a run with no context is a ghost.
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
        ExecutionContext context = executionRegistry.getStartedContext(runId); // null while queued
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

}
