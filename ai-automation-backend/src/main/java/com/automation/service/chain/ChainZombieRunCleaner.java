package com.automation.service.chain;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ChainRun;
import com.automation.entity.ChainRunEntry;
import com.automation.entity.RunStatus;
import com.automation.repository.ChainRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup reconciliation for chain runs: anything still RUNNING/PAUSED after a restart cannot
 * be resumed and is marked INTERRUPTED, including its nested entry runs.
 *
 * <p>Extracted from {@code ChainExecutor} (Audit-TODO 050b85d8).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChainZombieRunCleaner {

    private final ChainRunRepository chainRunRepository;
    private final ActivityLogService activityLogService;

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupZombieChainRuns() {
        List<ChainRun> zombies = new ArrayList<>();
        zombies.addAll(chainRunRepository.findByStatus(RunStatus.RUNNING));
        zombies.addAll(chainRunRepository.findByStatus(RunStatus.PAUSED));
        if (zombies.isEmpty()) {
            return;
        }
        log.warn("Found {} chain-run(s) stuck in RUNNING/PAUSED — marking as INTERRUPTED", zombies.size());
        for (ChainRun zombie : zombies) {
            Instant completedAt = Instant.now();
            zombie.setStatus(RunStatus.INTERRUPTED);
            zombie.setCompletedAt(completedAt);
            if (zombie.getStartedAt() != null) {
                zombie.setDurationMs(completedAt.toEpochMilli() - zombie.getStartedAt().toEpochMilli());
            }
            // Also reconcile the nested entryRuns — otherwise the UI shows an INTERRUPTED chain
            // whose entries are still stuck on RUNNING/PAUSED (inconsistent status).
            if (zombie.getEntryRuns() != null) {
                for (ChainRunEntry entryRun : zombie.getEntryRuns()) {
                    if (entryRun.getStatus() == RunStatus.RUNNING || entryRun.getStatus() == RunStatus.PAUSED) {
                        entryRun.setStatus(RunStatus.INTERRUPTED);
                        entryRun.setCompletedAt(completedAt);
                        if (entryRun.getStartedAt() != null) {
                            entryRun.setDurationMs(completedAt.toEpochMilli() - entryRun.getStartedAt().toEpochMilli());
                        }
                    }
                }
            }
            chainRunRepository.save(zombie);
        }
        activityLogService.warn(LogCategory.EXECUTION,
                "Startup cleanup: marked " + zombies.size() + " chain-run(s) as INTERRUPTED",
                "ChainExecutor");
    }
}
