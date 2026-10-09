package com.automation.service.chain;

import com.automation.entity.*;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a workflow chain: one workflow after the other on a virtual thread, passing the previous
 * output along, honouring stop-on-failure and cancellation. What a run carries from entry to entry
 * lives in {@link ChainRunProgress}, waiting for an entry's run in {@link ChainRunWaiter}, the startup
 * zombie cleanup in {@link ChainZombieRunCleaner}.
 */
@Service
@Slf4j
public class ChainExecutor {

    private final ChainRunRepository chainRunRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService virtualThreadExecutor;
    private final ActivityLogService activityLogService;
    private final ChainRunWaiter runWaiter;
    private final Clock clock;

    private final Map<String, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();

    public ChainExecutor(
            ChainRunRepository chainRunRepository,
            WorkflowRunRepository workflowRunRepository,
            WorkflowService workflowService,
            @Lazy WorkflowExecutor workflowExecutor,
            SimpMessagingTemplate messagingTemplate,
            ExecutorService virtualThreadExecutor,
            ActivityLogService activityLogService,
            ChainRunWaiter runWaiter,
            Clock clock
    ) {
        this.chainRunRepository = chainRunRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowService = workflowService;
        this.workflowExecutor = workflowExecutor;
        this.messagingTemplate = messagingTemplate;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.activityLogService = activityLogService;
        this.runWaiter = runWaiter;
        this.clock = clock;
    }

    public ChainRun execute(WorkflowChain chain, Map<String, Object> inputContext) {
        return execute(chain, inputContext, null);
    }

    public ChainRun execute(WorkflowChain chain, Map<String, Object> inputContext, String triggeredByScheduleId) {
        return execute(chain, inputContext, triggeredByScheduleId,
                triggeredByScheduleId != null ? TriggerSource.SCHEDULE : TriggerSource.MANUAL);
    }

    /** @param startedBy what started the chain; its entry runs inherit it for the unattended-run policy */
    public ChainRun execute(WorkflowChain chain, Map<String, Object> inputContext, String triggeredByScheduleId,
                            TriggerSource startedBy) {
        ChainRun run = ChainRun.builder()
                .chain(chain)
                .status(RunStatus.RUNNING)
                .startedAt(clock.instant())
                .inputContext(inputContext != null ? new HashMap<>(inputContext) : new HashMap<>())
                .entryRuns(new ArrayList<>())
                .triggeredByScheduleId(triggeredByScheduleId)
                .triggerSource(startedBy)
                .build();
        run = chainRunRepository.save(run);

        final String runId = run.getId();
        activeCancellations.put(runId, new AtomicBoolean(false));

        activityLogService.info(LogCategory.EXECUTION, "Chain execution started: " + chain.getName(), "ChainExecutor", runId, null);

        virtualThreadExecutor.submit(() -> executeEntries(runId, chain, inputContext, startedBy));

        return run;
    }

    public void cancel(String runId) {
        AtomicBoolean flag = activeCancellations.get(runId);
        if (flag != null) {
            flag.set(true);
        }
    }

    private void executeEntries(String runId, WorkflowChain chain, Map<String, Object> inputContext, TriggerSource startedBy) {
        ChainRun run = chainRunRepository.findById(runId).orElseThrow();
        ChainRunProgress progress = new ChainRunProgress(runId, run, chain, inputContext, startedBy,
                activeCancellations.get(runId));

        List<ChainEntry> sortedEntries = chain.getEntries().stream()
                .sorted(Comparator.comparingInt(ChainEntry::getSortOrder))
                .toList();

        try {
            boolean stoppedEarly = runEntries(progress, sortedEntries);
            if (!stoppedEarly) {
                run.setStatus(progress.finalStatus());
            }
        } catch (Exception e) {
            log.error("Chain {} execution failed unexpectedly", chain.getName(), e);
            run.setStatus(RunStatus.FAILED);
            run.setError(e.getMessage());
        } finally {
            run.setCompletedAt(clock.instant());
            if (run.getStartedAt() != null) {
                run.setDurationMs(Duration.between(run.getStartedAt(), run.getCompletedAt()).toMillis());
            }
            saveAndBroadcast(progress);
            activeCancellations.remove(runId);
            logOutcome(chain, runId, run.getStatus());
        }
    }

    /**
     * Runs the entries in order. True when the chain stopped early — cancelled, or a failed entry with
     * stop-on-failure — and then already carries its final status.
     */
    private boolean runEntries(ChainRunProgress progress, List<ChainEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (progress.isCancelled()) {
                progress.run.setStatus(RunStatus.CANCELLED);
                return true;
            }
            if (runEntry(progress, entries.get(i), i)) {
                return true;
            }
        }
        return false;
    }

    /** Runs one entry and records it on the chain run; true when the chain has to stop. */
    private boolean runEntry(ChainRunProgress progress, ChainEntry entry, int index) {
        ChainRunEntry runEntry = ChainRunEntry.builder()
                .entryIndex(index)
                .workflowId(entry.getWorkflowId())
                .status(RunStatus.RUNNING)
                .startedAt(clock.instant())
                .build();

        Workflow workflow;
        try {
            workflow = workflowService.findByIdEager(entry.getWorkflowId());
            runEntry.setWorkflowName(workflow.getName());
            if (workflow.getProject() != null) {
                runEntry.setProjectName(workflow.getProject().getName());
            }
        } catch (Exception e) {
            log.error("Chain {}: Workflow {} not found", progress.chain.getName(), entry.getWorkflowId(), e);
            progress.run.getEntryRuns().add(runEntry);
            return failEntry(progress, entry, runEntry, "Workflow not found: " + entry.getWorkflowId());
        }

        Map<String, Object> context = progress.contextFor(entry);
        progress.run.getEntryRuns().add(runEntry);
        saveAndBroadcast(progress);

        try {
            WorkflowRun workflowRun = workflowExecutor.execute(workflow, context, TriggerSource.CHAIN);
            runEntry.setWorkflowRunId(workflowRun.getId());
            saveAndBroadcast(progress);

            WorkflowRun completed = runWaiter.waitForCompletion(workflowRun.getId(), progress.cancelled, entry.getTimeoutMinutes());
            // Fresh read to avoid stale Hibernate cache
            completed = workflowRunRepository.findById(completed.getId()).orElseThrow();
            return recordOutcome(progress, entry, runEntry, completed);
        } catch (CancellationException e) {
            // The user cancelled the chain while waiting: like a cancelled entry run, not like a failure
            log.info("Chain {}: cancelled while waiting for workflow {}", progress.chain.getName(), workflow.getName());
            markCompleted(runEntry);
            runEntry.setStatus(RunStatus.CANCELLED);
            progress.run.setStatus(RunStatus.CANCELLED);
            saveAndBroadcast(progress);
            return true;
        } catch (Exception e) {
            log.error("Chain {}: Error executing workflow {}", progress.chain.getName(), workflow.getName(), e);
            return failEntry(progress, entry, runEntry, e.getMessage());
        }
    }

    /**
     * Records how the entry's run ended and saves the chain run: a completed run (with or without errors)
     * hands its output on, a cancelled one cancels the chain, anything else failed. True when the chain
     * has to stop.
     */
    private boolean recordOutcome(ChainRunProgress progress, ChainEntry entry, ChainRunEntry runEntry,
                                  WorkflowRun completed) {
        markCompleted(runEntry);
        RunStatus entryStatus = entryStatusOf(completed.getStatus());
        runEntry.setStatus(entryStatus);
        boolean stop = false;
        switch (entryStatus) {
            case COMPLETED -> progress.previousOutput = runWaiter.extractOutput(completed);
            case COMPLETED_WITH_ERRORS -> {
                progress.previousOutput = runWaiter.extractOutput(completed);
                progress.hasWarnings = true;
            }
            case CANCELLED -> {
                progress.run.setStatus(RunStatus.CANCELLED);
                stop = true;
            }
            default -> {
                progress.hasFailures = true;
                stop = stopOnFailure(progress, entry, "Workflow '" + runEntry.getWorkflowName() + "' failed");
            }
        }
        saveAndBroadcast(progress);
        return stop;
    }

    /** An entry keeps its run's status when that completed (with or without errors) or was cancelled. */
    private static RunStatus entryStatusOf(RunStatus runStatus) {
        return switch (runStatus) {
            case COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED -> runStatus;
            case null, default -> RunStatus.FAILED;
        };
    }

    /**
     * Marks the entry FAILED, saves and broadcasts the chain run (still running at this point), then
     * applies stop-on-failure with {@code error}. True when the chain has to stop.
     */
    private boolean failEntry(ChainRunProgress progress, ChainEntry entry, ChainRunEntry runEntry, String error) {
        runEntry.setStatus(RunStatus.FAILED);
        markCompleted(runEntry);
        progress.hasFailures = true;
        saveAndBroadcast(progress);
        return stopOnFailure(progress, entry, error);
    }

    /** The entry ends now; its duration is completedAt − startedAt. */
    private void markCompleted(ChainRunEntry runEntry) {
        runEntry.setCompletedAt(clock.instant());
        runEntry.setDurationMs(Duration.between(runEntry.getStartedAt(), runEntry.getCompletedAt()).toMillis());
    }

    /** With stop-on-failure, fails the chain with {@code error} and returns true. */
    private boolean stopOnFailure(ChainRunProgress progress, ChainEntry entry, String error) {
        if (!shouldStop(progress.chain, entry)) {
            return false;
        }
        progress.run.setStatus(RunStatus.FAILED);
        progress.run.setError(error);
        return true;
    }

    private void logOutcome(WorkflowChain chain, String runId, RunStatus status) {
        if (status == RunStatus.COMPLETED) {
            activityLogService.info(LogCategory.EXECUTION, "Chain execution completed: " + chain.getName(), "ChainExecutor", runId, null);
        } else if (status == RunStatus.COMPLETED_WITH_ERRORS) {
            activityLogService.warn(LogCategory.EXECUTION, "Chain completed with warnings: " + chain.getName(), "ChainExecutor", runId, null);
        } else if (status == RunStatus.FAILED) {
            activityLogService.error(LogCategory.EXECUTION, "Chain execution failed: " + chain.getName(), "ChainExecutor", runId, null);
        } else if (status == RunStatus.CANCELLED) {
            activityLogService.warn(LogCategory.EXECUTION, "Chain execution cancelled: " + chain.getName(), "ChainExecutor", runId, null);
        }
    }

    private boolean shouldStop(WorkflowChain chain, ChainEntry entry) {
        return entry.isStopOnFailure() && chain.isStopOnFailure();
    }

    private void saveAndBroadcast(ChainRunProgress progress) {
        chainRunRepository.save(progress.run);
        broadcastStatus(progress.runId, progress.run);
    }

    private void broadcastStatus(String runId, ChainRun run) {
        try {
            messagingTemplate.convertAndSend("/topic/chains/" + runId + "/status", run.getStatus().name());
        } catch (Exception e) {
            log.debug("Failed to broadcast chain status", e);
        }
    }
}
