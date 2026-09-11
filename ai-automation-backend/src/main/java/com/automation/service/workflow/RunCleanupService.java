package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.automation.config.RunRetentionConfig;
import org.springframework.data.domain.PageRequest;
import java.util.EnumSet;
import java.util.Set;
import java.util.List;

/**
 * Daily cleanup of INTERRUPTED workflow runs.
 *
 * Retention policy:
 * - INTERRUPTED runs with 0 completed steps: deleted after 24 hours (no resume value)
 * - INTERRUPTED runs with ≥1 completed steps: deleted after N days (default: 30)
 *   — kept for potential resume and learning system access
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunCleanupService {

    private final WorkflowRunRepository runRepository;
    private final ActivityLogService activityLogService;
    private final RunDeletionService runDeletionService;
    private final RunRetentionConfig runRetentionConfig;

    /** Statuses a run can only reach once it is finished — the retention candidates. */
    public static final Set<RunStatus> TERMINAL_STATUSES = EnumSet.of(
            RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS, RunStatus.FAILED, RunStatus.CANCELLED);

    @Value("${automation.run.interrupted-retention-days:30}")
    private int interruptedRetentionDays;

    @Scheduled(cron = "0 0 3 * * *")  // daily at 03:00
    @Transactional
    public void cleanupInterruptedRuns() {
        int deleted = 0;

        // 1. INTERRUPTED runs with no completed steps — clean up after 24h
        Instant cutoff24h = Instant.now().minus(1, ChronoUnit.DAYS);
        List<String> emptyRunIds = runRepository.findInterruptedRunIdsWithNoCompletedSteps(cutoff24h);
        if (!emptyRunIds.isEmpty()) {
            runRepository.deleteAllById(emptyRunIds);
            deleted += emptyRunIds.size();
            log.info("Cleaned up {} INTERRUPTED runs with no completed steps (older than 24h)", emptyRunIds.size());
        }

        // 2. INTERRUPTED runs with completed steps — clean up after retention period
        Instant cutoffRetention = Instant.now().minus(interruptedRetentionDays, ChronoUnit.DAYS);
        List<String> oldRunIds = runRepository.findInterruptedRunIdsOlderThan(cutoffRetention);
        if (!oldRunIds.isEmpty()) {
            runRepository.deleteAllById(oldRunIds);
            deleted += oldRunIds.size();
            log.info("Cleaned up {} INTERRUPTED runs older than {} days", oldRunIds.size(), interruptedRetentionDays);
        }

        if (deleted > 0) {
            activityLogService.info(LogCategory.EXECUTION,
                    "Daily cleanup: deleted " + deleted + " stale INTERRUPTED run(s)",
                    "RunCleanupService");
        }
    }

    /**
     * Retention for finished runs: deletes the oldest terminal runs past {@code run-retention-days},
     * at most {@code run-retention-max-per-night} per pass. Each run goes through
     * {@link RunDeletionService#delete} in its own short transaction (DLQ, TODO and wish references
     * are cleaned there); runs referenced by an audit report are excluded by the query and, as a
     * second line of defence, refused by the deletion service. Runs 15 minutes after the
     * INTERRUPTED sweep and after the 02:00 backup.
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void cleanupTerminalRuns() {
        int retentionDays = runRetentionConfig.getRetentionDays();
        if (retentionDays <= 0) {
            log.debug("Run retention disabled (run-retention-days = 0)");
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        List<String> candidates = runRepository.findTerminalRunIdsOlderThan(
                TERMINAL_STATUSES, cutoff, PageRequest.of(0, Math.max(1, runRetentionConfig.getMaxPerNight())));
        if (candidates.isEmpty()) {
            return;
        }
        int deleted = 0;
        int skipped = 0;
        int failed = 0;
        for (String runId : candidates) {
            try {
                runDeletionService.delete(runId);
                deleted++;
            } catch (IllegalStateException e) {
                // still active or audit evidence — keep it
                skipped++;
                log.debug("Retention skipped run {}: {}", runId, e.getMessage());
            } catch (Exception e) {
                failed++;
                log.warn("Retention could not delete run {}: {}", runId, e.getMessage());
            }
        }
        log.info("Run retention: deleted {} finished run(s) older than {} days ({} skipped, {} failed, batch limit {})",
                deleted, retentionDays, skipped, failed, runRetentionConfig.getMaxPerNight());
        activityLogService.info(LogCategory.EXECUTION,
                "Run-Retention: " + deleted + " abgeschlossene Run(s) älter als " + retentionDays
                        + " Tage gelöscht (" + skipped + " übersprungen, " + failed + " fehlgeschlagen)",
                "RunCleanupService");
    }
}
