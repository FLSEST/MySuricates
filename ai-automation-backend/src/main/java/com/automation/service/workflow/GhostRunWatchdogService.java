package com.automation.service.workflow;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.scheduler.SuspendState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Detects and cleans up ghost runs — workflow runs stuck in RUNNING status
 * without an active execution thread. Also reconciles leaked semaphore permits.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GhostRunWatchdogService {

    @Value("${automation.execution.ghost-watchdog-stale-minutes:30}")
    private long staleThresholdMinutes;

    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutionRegistry executionRegistry;
    private final DynamicSemaphore executionSemaphore;
    private final ExecutionBroadcaster broadcaster;
    private final ActivityLogService activityLogService;
    private final SuspendState suspendState;

    @Scheduled(fixedRate = 120_000, initialDelay = 60_000)
    public void detectGhostRuns() {
        List<WorkflowRun> runningRuns = runRepository.findByStatus(RunStatus.RUNNING);
        if (runningRuns.isEmpty()) {
            reconcilePermits();
            return;
        }

        Instant now = Instant.now();
        Instant staleThreshold = now.minus(staleThresholdMinutes, ChronoUnit.MINUTES);
        // A resume detected within this window strongly implies the orphaning was caused by a
        // system suspend (which kills the run's CLI/Docker subprocesses) rather than a true ghost.
        boolean suspendRelated = suspendState.resumedRecently(now, Duration.ofMinutes(staleThresholdMinutes * 2));
        int failedCount = 0;
        int interruptedCount = 0;

        for (WorkflowRun run : runningRuns) {
            if (executionRegistry.isRunActive(run.getId())) {
                continue; // legitimate active run
            }
            if (run.getStartedAt() != null && run.getStartedAt().isAfter(staleThreshold)) {
                continue; // recently started, may still be initializing
            }

            // RUNNING in DB but not in activeExecutions and older than threshold.
            // Suspend-related orphans are resumable: mark them INTERRUPTED (no error payload) so the UI
            // offers "Resume from Step". Only true ghosts are hard-failed with an error.
            String message = suspendRelated
                    ? "Interrupted by system suspend/resume (paused ~"
                            + (suspendState.getLastGapMillis() / 60_000) + " min) — no active execution thread"
                    : "Ghost run detected by watchdog: no active execution thread found";
            RunStatus targetStatus = suspendRelated ? RunStatus.INTERRUPTED : RunStatus.FAILED;
            run.setStatus(targetStatus);
            run.setCompletedAt(now);
            run.setDurationMs(run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli());
            if (targetStatus == RunStatus.FAILED) {
                run.setError(Map.of("message", message));
            }
            runRepository.save(run);
            broadcaster.broadcastRunStatus(run.getId(), targetStatus);
            if (suspendRelated) {
                interruptedCount++;
            } else {
                failedCount++;
            }
            log.warn("{} run detected: {} (started {}) — marked {}",
                    suspendRelated ? "Suspend-interrupted" : "Ghost", run.getId(), run.getStartedAt(), targetStatus);
        }

        if (failedCount > 0) {
            activityLogService.warn(LogCategory.EXECUTION,
                    "Watchdog: " + failedCount + " ghost run(s) detected and marked as FAILED",
                    "GhostRunWatchdogService");
        }
        if (interruptedCount > 0) {
            activityLogService.warn(LogCategory.EXECUTION,
                    "Watchdog: " + interruptedCount + " run(s) interrupted by system suspend — marked INTERRUPTED (resumable)",
                    "GhostRunWatchdogService");
        }

        reconcilePermits();
    }

    /**
     * Reconciles semaphore permits with actual active executions.
     * If permits were leaked (thread died without releasing), this recovers them.
     */
    private void reconcilePermits() {
        int activeCount = executionRegistry.getActiveRunCount();
        int maxPermits = executionSemaphore.getMaxPermits();
        int availablePermits = executionSemaphore.availablePermits();
        int expectedAvailable = maxPermits - activeCount;

        if (availablePermits < expectedAvailable) {
            int leaked = expectedAvailable - availablePermits;
            executionSemaphore.release(leaked);
            log.warn("Reconciled {} leaked semaphore permit(s) (active={}, max={}, was-available={}, now-available={})",
                    leaked, activeCount, maxPermits, availablePermits, executionSemaphore.availablePermits());
            activityLogService.warn(LogCategory.EXECUTION,
                    "Watchdog: reconciled " + leaked + " leaked semaphore permit(s)",
                    "GhostRunWatchdogService");
        }
    }
}
