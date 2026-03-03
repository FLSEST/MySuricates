package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Watchdog service that detects and recovers stuck wishes.
 *
 * <p>Wishes can get stuck in active states (DECOMPOSING, EXECUTING, EVALUATING, REFINING)
 * when the underlying process fails silently (e.g., thread death, CLI timeout without
 * state transition, polling loop hanging).
 *
 * <p>Two recovery mechanisms:
 * <ul>
 *   <li><strong>Periodic watchdog</strong>: Runs every N minutes, checks for stale wishes</li>
 *   <li><strong>Startup recovery</strong>: On boot, recovers wishes that were active when the app shut down</li>
 * </ul>
 */
@Service
@Slf4j
public class WishWatchdogService {

    private static final List<WishStatus> ACTIVE_STATES = List.of(
            WishStatus.DECOMPOSING,
            WishStatus.EXECUTING,
            WishStatus.EVALUATING,
            WishStatus.REFINING
    );

    private final WishConfig wishConfig;
    private final WishRepository wishRepository;
    private final WishService wishService;
    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;

    public WishWatchdogService(
            WishConfig wishConfig,
            WishRepository wishRepository,
            WishService wishService,
            WorkflowRunRepository workflowRunRepository,
            ChainRunRepository chainRunRepository
    ) {
        this.wishConfig = wishConfig;
        this.wishRepository = wishRepository;
        this.wishService = wishService;
        this.workflowRunRepository = workflowRunRepository;
        this.chainRunRepository = chainRunRepository;
    }

    /**
     * Startup recovery: find wishes that were active when the app shut down
     * and transition them to FAILED (they can't continue from where they left off).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!wishConfig.isWatchdogEnabled()) return;

        List<Wish> stuckWishes = wishRepository.findByStatusInAndUpdatedAtBefore(
                ACTIVE_STATES, Instant.now());

        if (stuckWishes.isEmpty()) return;

        log.info("Wish watchdog: Found {} wishes in active states on startup — recovering", stuckWishes.size());

        for (Wish wish : stuckWishes) {
            try {
                recoverStuckWish(wish, "Application restart recovery");
            } catch (Exception e) {
                log.error("Wish watchdog: Failed to recover wish {} on startup", wish.getId(), e);
            }
        }
    }

    /**
     * Periodic check for wishes stuck in active states beyond the stale threshold.
     */
    @Scheduled(fixedDelayString = "${automation.wish.watchdog-interval-ms:120000}")
    public void checkForStaleWishes() {
        if (!wishConfig.isWatchdogEnabled()) return;

        Instant staleThreshold = Instant.now().minus(
                wishConfig.getWatchdogStaleMinutes(), ChronoUnit.MINUTES);

        List<Wish> staleWishes = wishRepository.findByStatusInAndUpdatedAtBefore(
                ACTIVE_STATES, staleThreshold);

        for (Wish wish : staleWishes) {
            try {
                log.warn("Wish watchdog: Wish {} stuck in {} since {} (>{} min) — recovering",
                        wish.getId(), wish.getStatus(), wish.getUpdatedAt(),
                        wishConfig.getWatchdogStaleMinutes());
                recoverStuckWish(wish, "Stale state detected by watchdog (>" +
                        wishConfig.getWatchdogStaleMinutes() + " min without update)");
            } catch (Exception e) {
                log.error("Wish watchdog: Failed to recover stale wish {}", wish.getId(), e);
            }
        }
    }

    private void recoverStuckWish(Wish wish, String reason) {
        if (wish.getStatus() == WishStatus.EXECUTING) {
            // Special case: check if the underlying run has finished
            if (wish.getCurrentRunId() != null && isRunTerminal(wish)) {
                log.info("Wish watchdog: Wish {} EXECUTING but run is terminal — moving to EVALUATING",
                        wish.getId());
                wish.setStatus(WishStatus.EVALUATING);
                wishRepository.save(wish);
                wishService.broadcastStatus(wish.getId(), wish);
                // Re-trigger fulfillment to continue from EVALUATING
                wishService.fulfillAsync(wish.getId());
                return;
            }
        }

        // Default: mark as FAILED
        wish.setStatus(WishStatus.FAILED);
        wish.setError("Watchdog recovery: " + reason);
        wishRepository.save(wish);
        wishService.broadcastStatus(wish.getId(), wish);
        log.info("Wish watchdog: Wish {} → FAILED ({})", wish.getId(), reason);
    }

    private boolean isRunTerminal(Wish wish) {
        String runId = wish.getCurrentRunId();
        if (runId == null) return false;

        // Check workflow run
        var workflowRun = workflowRunRepository.findById(runId).orElse(null);
        if (workflowRun != null) {
            return isTerminalRunStatus(workflowRun.getStatus());
        }

        // Check chain run
        var chainRun = chainRunRepository.findById(runId).orElse(null);
        if (chainRun != null) {
            return isTerminalRunStatus(chainRun.getStatus());
        }

        // Run not found — treat as terminal
        return true;
    }

    private boolean isTerminalRunStatus(RunStatus status) {
        return status == RunStatus.COMPLETED
                || status == RunStatus.COMPLETED_WITH_ERRORS
                || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED;
    }
}
