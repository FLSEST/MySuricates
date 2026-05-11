package com.automation.service.workflow;

import com.automation.concurrency.DynamicSemaphore;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
    private final WorkflowExecutor workflowExecutor;
    private final DynamicSemaphore executionSemaphore;
    private final ExecutionBroadcaster broadcaster;
    private final ActivityLogService activityLogService;

    @Scheduled(fixedRate = 120_000, initialDelay = 60_000)
    public void detectGhostRuns() {
        List<WorkflowRun> runningRuns = runRepository.findByStatus(RunStatus.RUNNING);
        if (runningRuns.isEmpty()) {
            reconcilePermits();
            return;
        }

        Instant staleThreshold = Instant.now().minus(staleThresholdMinutes, ChronoUnit.MINUTES);
        int ghostCount = 0;

        for (WorkflowRun run : runningRuns) {
            if (workflowExecutor.isRunActive(run.getId())) {
                continue; // legitimate active run
            }
            if (run.getStartedAt() != null && run.getStartedAt().isAfter(staleThreshold)) {
                continue; // recently started, may still be initializing
            }

            // Ghost run: RUNNING in DB but not in activeExecutions and older than threshold
            run.setStatus(RunStatus.FAILED);
            run.setCompletedAt(Instant.now());
            run.setDurationMs(run.getCompletedAt().toEpochMilli() - run.getStartedAt().toEpochMilli());
            run.setError(Map.of("message", "Ghost run detected by watchdog: no active execution thread found"));
            runRepository.save(run);
            broadcaster.broadcastRunStatus(run.getId(), RunStatus.FAILED);
            ghostCount++;
            log.warn("Ghost run detected and failed: {} (started {})", run.getId(), run.getStartedAt());
        }

        if (ghostCount > 0) {
            activityLogService.warn(LogCategory.EXECUTION,
                    "Watchdog: " + ghostCount + " ghost run(s) detected and marked as FAILED",
                    "GhostRunWatchdogService");
        }

        reconcilePermits();
    }

    /**
     * Reconciles semaphore permits with actual active executions.
     * If permits were leaked (thread died without releasing), this recovers them.
     */
    private void reconcilePermits() {
        int activeCount = workflowExecutor.getActiveRunCount();
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
