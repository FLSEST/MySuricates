package com.automation.service.workflow;

import com.automation.config.AutoResumeConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.exception.HardeningException;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Resumes runs that a restart or a system standby interrupted — for workflows that opted in with
 * {@code metadata.autoResume.enabled: true} (TODO 90d10ba1). Listens to {@link RunInterruption.Event},
 * so it only ever sees runs marked in this JVM lifetime, never older INTERRUPTED rows.
 *
 * <p>A run is resumed only if it is still INTERRUPTED, was RUNNING before (PAUSED is a person's
 * decision), is no chain entry or sub-workflow (their parent decides), its workflow still exists and
 * has opted in, and it has had no automatic attempt yet. The attempt counter is saved before the
 * start, so a second interruption — or a crash during the resume — leaves the run for a person.
 *
 * <p>Runs are resumed one after another with {@link AutoResumeConfig#getDelaySeconds()} before each,
 * on a thread of its own: neither the startup nor the watchdog tick waits for it. The resume goes
 * through {@link WorkflowExecutor#resumeFromStep(String, TriggerSource)} with
 * {@link TriggerSource#AUTO_RESUME}, i.e. through {@link RunAdmission}; a refusal leaves the run
 * INTERRUPTED (the SECURITY entry is RunAdmission's) and is not retried.
 *
 * <p>What it is done with, and which attempt is under way, it reports to {@link PendingAutoResumes}:
 * the scheduler skips a workflow's due occurrence while a resume of it is ahead (TODO 070ccc3d).
 */
@Service
@Slf4j
public class RunAutoResumer {

    /** Run metadata key: automatic resume attempts so far. */
    public static final String ATTEMPTS_KEY = "autoResumeAttempts";
    /** Metadata key of a run a full retry started: the interrupted run it replaces. */
    public static final String RESUMED_FROM_KEY = "autoResumedFrom";
    /** Metadata key of an interrupted run a full retry replaced: the new run. */
    public static final String RESTARTED_AS_KEY = "autoRestartedAs";
    static final int MAX_ATTEMPTS = 1;
    private static final String SOURCE = "RunAutoResumer";

    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutor workflowExecutor;
    private final ActivityLogService activityLog;
    private final AutoResumeConfig config;
    private final TransactionTemplate tx;
    private final ScheduledExecutorService scheduler;
    private final PendingAutoResumes pending;

    private final Deque<String> queue = new ArrayDeque<>();
    private boolean draining;

    @Autowired
    public RunAutoResumer(WorkflowRunRepository runRepository, WorkflowRepository workflowRepository,
                          WorkflowExecutor workflowExecutor, ActivityLogService activityLog,
                          AutoResumeConfig config, PlatformTransactionManager transactionManager,
                          PendingAutoResumes pending) {
        this(runRepository, workflowRepository, workflowExecutor, activityLog, config, transactionManager,
                Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("auto-resume").factory()),
                pending);
    }

    /** Test constructor: the caller owns the scheduler. */
    RunAutoResumer(WorkflowRunRepository runRepository, WorkflowRepository workflowRepository,
                   WorkflowExecutor workflowExecutor, ActivityLogService activityLog, AutoResumeConfig config,
                   PlatformTransactionManager transactionManager, ScheduledExecutorService scheduler,
                   PendingAutoResumes pending) {
        this.runRepository = runRepository;
        this.workflowRepository = workflowRepository;
        this.workflowExecutor = workflowExecutor;
        this.activityLog = activityLog;
        this.config = config;
        this.tx = new TransactionTemplate(transactionManager);
        this.scheduler = scheduler;
        this.pending = pending;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    /** Whether the workflow metadata opts in to automatic resume. */
    public static boolean optedIn(Map<String, Object> workflowMetadata) {
        return workflowMetadata != null && workflowMetadata.get("autoResume") instanceof Map<?, ?> autoResume
                && Boolean.TRUE.equals(autoResume.get("enabled"));
    }

    @EventListener
    public void onRunsInterrupted(RunInterruption.Event event) {
        if (!config.isEnabled()) {
            log.debug("Auto-resume is switched off — {} interrupted run(s) stay for a person", event.runIds().size());
            pending.dropped(event.runIds());
            return;
        }
        enqueue(event.runIds());
    }

    /** True while no resume is queued or waiting for its turn. */
    public synchronized boolean isIdle() {
        return !draining;
    }

    synchronized void enqueue(Collection<String> runIds) {
        pending.reopen(runIds);
        runIds.stream().filter(id -> !queue.contains(id)).forEach(queue::add);
        if (!draining && !queue.isEmpty()) {
            draining = true;
            scheduleNext();
        }
    }

    private synchronized void scheduleNext() {
        try {
            scheduler.schedule(this::resumeNext, Math.max(0, config.getDelaySeconds()), TimeUnit.SECONDS);
        } catch (RuntimeException e) {   // shutting down: the runs stay INTERRUPTED for a person
            log.warn("Auto-resume could not be scheduled: {}", e.getMessage());
            pending.dropped(queue);
            queue.clear();
            draining = false;
        }
    }

    private synchronized String poll() {
        return queue.poll();
    }

    private synchronized void afterStep() {
        if (queue.isEmpty()) {
            draining = false;
        } else {
            scheduleNext();
        }
    }

    /** Resumes the next eligible run; runs that are not eligible are skipped without waiting. */
    void resumeNext() {
        try {
            String runId;
            while ((runId = poll()) != null) {
                if (attempt(runId)) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.error("Auto-resume failed unexpectedly: {}", e.getMessage(), e);
        } finally {
            afterStep();
        }
    }

    private record Claim(String runId, String workflowId, String workflowName) {}

    /** @return whether an attempt was made (started, refused or failed) */
    private boolean attempt(String runId) {
        pending.attemptStarted(runId);
        try {
            return attemptClaimed(runId);
        } finally {
            pending.attemptEnded(runId);
        }
    }

    private boolean attemptClaimed(String runId) {
        Claim claim = tx.execute(status -> claim(runId));
        if (claim == null) {
            return false;
        }
        try {
            // no surrounding transaction: resumeFromStep commits its own before the run thread starts (fe06ebf9)
            WorkflowRun started = workflowExecutor.resumeFromStep(runId, TriggerSource.AUTO_RESUME);
            boolean restarted = started != null && !runId.equals(started.getId());   // no finished block: full retry
            if (restarted) {
                tx.executeWithoutResult(status -> markRestarted(runId, started.getId()));
            }
            String newRun = restarted ? " as new run " + started.getId() : "";
            activityLog.info(LogCategory.EXECUTION,
                    "Auto-resume: continued interrupted run of " + claim.workflowName() + newRun,
                    SOURCE, runId, claim.workflowId());
        } catch (HardeningException e) {
            // RunAdmission has written the SECURITY entry (after the rollback); the run stays INTERRUPTED.
            activityLog.warn(LogCategory.EXECUTION,
                    "Auto-resume refused for " + claim.workflowName() + ": " + e.getMessage(),
                    SOURCE, runId, claim.workflowId());
        } catch (RuntimeException e) {
            log.error("Auto-resume of run {} failed: {}", runId, e.getMessage(), e);
            activityLog.error(LogCategory.EXECUTION,
                    "Auto-resume failed for " + claim.workflowName() + ": " + e.getMessage(),
                    SOURCE, runId, claim.workflowId());
        }
        return true;
    }

    /**
     * Metadata for the new run of a full retry ({@link WorkflowExecutor#resumeFromStep}, no finished
     * block): an automatic one carries the attempt count and its origin over, so an interruption of
     * the new run is not resumed automatically again. {@code null} for any other source.
     */
    static Map<String, Object> carryOver(WorkflowRun interrupted, TriggerSource source) {
        if (source != TriggerSource.AUTO_RESUME) {
            return null;
        }
        return Map.of(ATTEMPTS_KEY, Math.max(attempts(interrupted), MAX_ATTEMPTS), RESUMED_FROM_KEY, interrupted.getId());
    }

    /** The interrupted run stays INTERRUPTED; its metadata names the run that replaced it. */
    private void markRestarted(String runId, String newRunId) {
        runRepository.findById(runId).ifPresent(run -> {
            Map<String, Object> metadata = new HashMap<>(run.getMetadata() != null ? run.getMetadata() : Map.of());
            metadata.put(RESTARTED_AS_KEY, newRunId);
            run.setMetadata(metadata);
            runRepository.save(run);
        });
    }

    /** Checks the run and counts the attempt (committed before the start); {@code null} = not eligible. */
    private Claim claim(String runId) {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        String skip = skipReason(run);
        Workflow workflow = null;
        if (skip == null) {
            workflow = workflowRepository.findById(run.getWorkflow().getId()).orElse(null); // soft-deleted ⇒ empty
            skip = workflow == null ? "workflow deleted" : optedIn(workflow.getMetadata()) ? null : "workflow did not opt in";
        }
        if (skip != null) {
            log.debug("Run {} is not resumed automatically: {}", runId, skip);
            return null;
        }
        Map<String, Object> metadata = new HashMap<>(run.getMetadata() != null ? run.getMetadata() : Map.of());
        metadata.put(ATTEMPTS_KEY, attempts(run) + 1);
        run.setMetadata(metadata);
        runRepository.save(run);
        return new Claim(runId, workflow.getId(), workflow.getName());
    }

    /** Why this run is not resumed automatically, or {@code null} if the run itself qualifies. */
    static String skipReason(WorkflowRun run) {
        if (run == null) {
            return "run not found";
        }
        if (run.getStatus() != RunStatus.INTERRUPTED) {
            return "status is " + run.getStatus();
        }
        Map<String, Object> metadata = run.getMetadata() != null ? run.getMetadata() : Map.of();
        if (!RunStatus.RUNNING.name().equals(metadata.get(RunInterruption.FROM_KEY))) {
            return "was not running (" + metadata.get(RunInterruption.FROM_KEY) + ")";
        }
        if (attempts(run) >= MAX_ATTEMPTS) {
            return "already resumed automatically";
        }
        TriggerSource source = run.getTriggerSource();
        boolean nested = source == TriggerSource.CHAIN || source == TriggerSource.SUB_WORKFLOW
                || run.getChainRunId() != null
                || (run.getInputContext() != null && run.getInputContext().containsKey(TriggerSource.PARENT_CONTEXT_KEY));
        return nested ? "chain entry or sub-workflow — its parent decides" : null;
    }

    private static int attempts(WorkflowRun run) {
        Object value = run.getMetadata() != null ? run.getMetadata().get(ATTEMPTS_KEY) : null;
        return value instanceof Number n ? n.intValue() : 0;
    }
}
