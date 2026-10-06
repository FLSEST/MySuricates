package com.automation.service.workflow;

import com.automation.entity.*;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.BlockTypeRegistry;
import com.automation.executor.ExecutionContext;
import com.automation.executor.StepFailureSummary;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.project.ContainerService;
import com.automation.service.workflow.WorkflowSnapshotLoader.WorkflowSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.automation.concurrency.DynamicSemaphore;

import java.util.concurrent.ExecutorService;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowExecutor {

    private final BlockTypeRegistry blockTypes;
    private final ContainerService containerService;
    private final WorkflowRunRepository runRepository;
    private final StepResultRepository stepResultRepository;
    private final WorkflowSnapshotLoader snapshotLoader;
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
    private final PlatformTransactionManager transactionManager;

    // Active execution tracking is delegated to WorkflowExecutionRegistry; controlling a run that
    // already exists (pause/resume/cancel/approval, ad-hoc commands) to RunControlService.

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
                .triggeredByScheduleId(triggeredByScheduleId).chainRunId(TriggerSource.chainRunIdOf(triggerSource, inputContext))
                .triggerSource(triggerSource).definitionHash(ticket.definitionHash()).definitionVersionId(ticket.definitionVersionId())
                .sealed(ticket.sealed()).sealedSkillPins(ticket.sealedSkillPins())
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
            wf = snapshotLoader.load(workflow);

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
        submitExecution(runId, () -> executeBlocks(runId, workflowId, workflowName, wf, contextVars, ResumePlan.FRESH, null));

        return run;
    }

    /**
     * Core execution loop.
     * @param resume for resume: which blocks already ran and where the new segment's step indices start
     * @param resumePreviousOutput for resume: pre-seed previousOutput from the last completed step
     */
    private void executeBlocks(String runId, String workflowId, String workflowName, WorkflowSnapshot wf,
                               Map<String, Object> inputContext, ResumePlan resume, String resumePreviousOutput) {
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
            if (resume.resuming()) {
                // Resuming: keep existing completed steps from DB
                run.setSteps(new ArrayList<>(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(runId)));
            } else {
                // Fresh run: start with empty step list
                run.setSteps(new ArrayList<>());
            }

            if (executionRegistry.cancelRequested(runId)) {   // cancel arrived while still queued
                run.setStatus(RunStatus.CANCELLED);           // finally: save, broadcast, unregister
                return;
            }

            boolean isolated = RunWorkspaceService.enabled(workflowMeta);
            String containerId = isolated ? null : RunContainerPreparation.ensureRunning(project, runId, containerService, broadcaster);

            context = executionContextFactory.create(new ExecutionContextFactory.ContextSeed(
                    runId, workflowId, workflowName, project, containerId, inputContext,
                    instructions, model, provider, effort, resumePreviousOutput, run.getDefinitionHash(),
                    run.getTriggerSource(), UserPreferencesService.requestedBy(workflowMeta), workflowMeta));

            context.startStepIndicesAt(resume.firstStepIndex());
            executionRegistry.promote(runId, context);
            if (resume.resuming()) ResumeOutputSeeder.seed(context, run.getSteps(), resume.blocksToSkip()); // f9384e48
            Project executionProject = isolated
                    ? workspaces.prepare(run, project, workflowMeta, context, run.getMetadata().containsKey("workspace")) : project;
            executionContextFactory.writeProjectContextFiles(executionProject, workflowId, skills);

            int blockPosition = 0;
            for (BlockDefinition block : blocks) {
                // Resume: blocks of the earlier segment are skipped by position. Step indices are not
                // comparable across segments — wrapper blocks consumed extra ones (TODO 1be423b4).
                int position = blockPosition++;
                if (position < resume.blocksToSkip()) continue;

                if (context.shouldStop()) {
                    run.setStatus(RunStatus.CANCELLED);
                    break;
                }

                waitWhilePaused(run, context);

                // Per-block skill context: merge block-level skills with workflow-level skills
                boolean hasBlockSkills = skillContext.applyForBlock(block, executionProject, skills, blockSkillLookup);

                BlockExecutor executor = blockTypes.executorFor(block.getType());

                // Pre-create RUNNING placeholder so UI shows the step immediately
                int stepIndex = context.preAllocateStepIndex();
                StepResult step = stepRunner.startStep(run, stepIndex, block, position);
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
                    // Where the step failed first: a foreach names its failed items (4a8cabc4), else the output tail
                    String message = StepFailureSummary.runError(step, block.getType());
                    run.setError(Map.of("step", step.getStepIndex(), "blockType", block.getType(), "message", message));
                    break;
                }
            }

            completionHandler.complete(run, context, workflowId, workflowName, templateId, project.getId(), blocks);

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

    /** What the resume transaction hands over: the run and either a detached segment or a full retry. */
    private record ResumeStart(WorkflowRun run, WorkflowSnapshot wf, ResumePlan plan, ResumeContext context,
                               Workflow retryWorkflow, Map<String, Object> retryMetadata) {}

    /**
     * Resumes an INTERRUPTED run after its last completed step (none ⇒ full retry) — RESUME by a person,
     * AUTO_RESUME by {@link RunAutoResumer}. Loads, admits and marks the run in a short transaction of its
     * own and starts the run thread only after that commit; callers must not hold one (TODO fe06ebf9).
     */
    public WorkflowRun resumeFromStep(String runId, TriggerSource source) {
        ResumeStart start;
        try {
            start = new TransactionTemplate(transactionManager).execute(status -> prepareResume(runId, source));
        } catch (RuntimeException | Error e) {
            executionRegistry.unregister(runId); // registered in the transaction, which did not commit
            throw e;
        }
        if (start.retryWorkflow() != null) {
            return execute(start.retryWorkflow(), start.run().getInputContext(), null, start.retryMetadata(), source);
        }
        try {
            broadcaster.broadcastRunStatus(runId, RunStatus.RUNNING);
            submitExecution(runId, () -> executeBlocks(runId, start.run().getWorkflow().getId(), start.run().getWorkflow().getName(),
                    start.wf(), start.context().variables(), start.plan(), start.context().previousOutput()));
        } catch (Throwable t) {
            executionRegistry.unregister(runId); // no thread took over — do not leave it registered
            throw t;
        }
        return start.run();
    }

    private ResumeStart prepareResume(String runId, TriggerSource source) {
        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRun", runId));
        if (run.getStatus() != RunStatus.INTERRUPTED) {
            throw new WorkflowExecutionException("Run " + runId + " cannot be resumed: status is " + run.getStatus());
        }
        List<StepResult> persistedSteps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(runId);
        List<StepResult> completedSteps = persistedSteps.stream().filter(s -> s.getStatus() == StepStatus.COMPLETED).toList();
        final Workflow workflow = run.getWorkflow();
        if (completedSteps.isEmpty() && !run.getMetadata().containsKey("workspace")) {
            log.info("Resume requested for run {} but no completed steps found — falling back to full retry", runId);
            workflow.getBlocks().size(); workflow.getActivatedSkills().size(); // execute() reads them after this transaction
            return new ResumeStart(run, null, null, null, workflow, RunAutoResumer.carryOver(run, source));
        }
        ResumePlan plan = ResumePlan.after(persistedSteps);
        ResumeContext context = ResumeContext.rebuild(run.getInputContext(), completedSteps);
        final WorkflowSnapshot wf;
        try {
            wf = snapshotLoader.load(workflow);
        } catch (Exception e) {
            throw new WorkflowExecutionException("Failed to load workflow data for resume: " + e.getMessage(), e);
        }
        // Announce first: a failing log write leaves the run INTERRUPTED, not RUNNING-with-nobody.
        activityLogService.info(LogCategory.EXECUTION,
                "Resuming workflow after " + plan.blocksToSkip() + " completed block(s): " + workflow.getName(),
                "WorkflowExecutor", runId, workflow.getId());
        // Reset run to RUNNING — the resumed segment runs today's definition, so provenance is re-taken
        RunAdmission.Ticket ticket = admission.admit(workflow, source, context.variables());
        run.setTriggerSource(source); run.setDefinitionHash(ticket.definitionHash()); run.setDefinitionVersionId(ticket.definitionVersionId());
        run.setSealed(ticket.sealed()); run.setSealedSkillPins(ticket.sealedSkillPins());
        run.setStatus(RunStatus.RUNNING);
        run.setError(null); run.setCompletedAt(null);
        runRepository.save(run);
        executionRegistry.registerProvisional(runId); // controllable as soon as it is RUNNING
        return new ResumeStart(run, wf, plan, context, null, null);
    }

}
