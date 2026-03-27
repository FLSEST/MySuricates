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
}
