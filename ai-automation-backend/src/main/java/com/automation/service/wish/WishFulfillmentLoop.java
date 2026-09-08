package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.WishRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The asynchronous wish meta-loop (decompose → execute → evaluate, until terminal or paused)
 * plus its cancellation flags and total-timeout guard. Runs on the virtual-thread executor.
 *
 * <p>Extracted from {@code WishService} (Audit-TODO fc137a98). Owns no CRUD and no user
 * responses — {@code WishService.respond} decides state transitions and re-enters the loop via
 * {@link #fulfillAsync(String)}.
 */
@Service
@Slf4j
public class WishFulfillmentLoop {

    private final WishConfig wishConfig;
    private final WishRepository wishRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService virtualThreadExecutor;
    private final WishDecomposeService decomposeService;
    private final WishExecutionService executionService;
    private final WishEvaluationService evaluationService;

    private final Map<String, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();

    public WishFulfillmentLoop(
            WishConfig wishConfig,
            WishRepository wishRepository,
            SimpMessagingTemplate messagingTemplate,
            ExecutorService virtualThreadExecutor,
            WishDecomposeService decomposeService,
            WishExecutionService executionService,
            WishEvaluationService evaluationService
    ) {
        this.wishConfig = wishConfig;
        this.wishRepository = wishRepository;
        this.messagingTemplate = messagingTemplate;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.decomposeService = decomposeService;
        this.executionService = executionService;
        this.evaluationService = evaluationService;
    }

    // ── Async Orchestration ──────────────────────────────────────

    public void fulfillAsync(String wishId) {
        activeCancellations.put(wishId, new AtomicBoolean(false));
        virtualThreadExecutor.submit(() -> fulfillWish(wishId));
    }

    public void cancel(String wishId) {
        AtomicBoolean flag = activeCancellations.get(wishId);
        if (flag != null) {
            flag.set(true);
        }
        Wish wish = wishRepository.findById(wishId).orElse(null);
        if (wish != null && !WishStatusHelper.isTerminal(wish.getStatus())) {
            wish.setStatus(WishStatus.CANCELLED);
            wishRepository.save(wish);
            broadcastStatus(wishId, wish);
        }
    }

    // ── Meta-Loop ────────────────────────────────────────────────

    private void fulfillWish(String wishId) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(wishConfig.getTotalTimeoutMinutes()));

        try {
            while (true) {
                if (isCancelled(wishId)) {
                    Wish w = findById(wishId);
                    w.setStatus(WishStatus.CANCELLED);
                    wishRepository.save(w);
                    broadcastStatus(wishId, w);
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    Wish w = findById(wishId);
                    w.setStatus(WishStatus.FAILED);
                    w.setError("Total timeout exceeded (" + wishConfig.getTotalTimeoutMinutes() + " minutes)");
                    wishRepository.save(w);
                    broadcastStatus(wishId, w);
                    return;
                }

                Wish wish = findById(wishId);

                if (WishStatusHelper.isTerminal(wish.getStatus()) || WishStatusHelper.isPaused(wish.getStatus())) return;

                // Phase 1: Decompose
                if (wish.getStatus() == WishStatus.RECEIVED || wish.getStatus() == WishStatus.REFINING) {
                    WishStatusHelper.updateStatus(wishRepository, messagingTemplate, wish, WishStatus.DECOMPOSING);
                    decomposeService.decompose(wish);
                    wish = findById(wishId);
                    if (WishStatusHelper.isTerminal(wish.getStatus()) || WishStatusHelper.isPaused(wish.getStatus())) return;
                }

                // Phase 2: Execute
                wish = findById(wishId);
                if (wish.getStatus() == WishStatus.EXECUTING) {
                    executionService.execute(wish, () -> isCancelled(wishId));
                    wish = findById(wishId);
                    if (WishStatusHelper.isTerminal(wish.getStatus()) || WishStatusHelper.isPaused(wish.getStatus())) return;
                }

                // Phase 3: Evaluate
                wish = findById(wishId);
                if (wish.getStatus() == WishStatus.EVALUATING) {
                    evaluationService.evaluate(wish);
                    wish = findById(wishId);
                    if (WishStatusHelper.isTerminal(wish.getStatus()) || WishStatusHelper.isPaused(wish.getStatus())) return;
                    // If REFINING, loop continues
                }
            }
        } catch (Exception e) {
            log.error("Wish {} failed unexpectedly", wishId, e);
            try {
                Wish wish = findById(wishId);
                wish.setStatus(WishStatus.FAILED);
                wish.setError(e.getMessage());
                wishRepository.save(wish);
                broadcastStatus(wishId, wish);
            } catch (Exception ex) {
                log.error("Failed to update wish status after error", ex);
            }
        } finally {
            activeCancellations.remove(wishId);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private Wish findById(String id) {
        return wishRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Wish not found: " + id));
    }

    private boolean isCancelled(String wishId) {
        AtomicBoolean flag = activeCancellations.get(wishId);
        return flag != null && flag.get();
    }

    private void broadcastStatus(String wishId, Wish wish) {
        WishStatusHelper.broadcastStatus(messagingTemplate, wishId, wish);
    }
}
