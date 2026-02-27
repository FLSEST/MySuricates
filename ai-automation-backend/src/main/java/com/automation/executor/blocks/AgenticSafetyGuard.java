package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Enforces safety limits for agentic block execution:
 * total/iteration timeouts, repeated-action detection, and pause/cancel handling.
 */
public class AgenticSafetyGuard {

    private final Instant blockStart;
    private final int totalTimeoutSeconds;
    private final int iterationTimeoutSeconds;
    private final int maxRepeatedActions;
    private final List<String> recentActions = new ArrayList<>();

    public AgenticSafetyGuard(int totalTimeoutSeconds, int iterationTimeoutSeconds,
                               int maxRepeatedActions) {
        this.blockStart = Instant.now();
        this.totalTimeoutSeconds = totalTimeoutSeconds;
        this.iterationTimeoutSeconds = iterationTimeoutSeconds;
        this.maxRepeatedActions = maxRepeatedActions;
    }

    /**
     * Checks if the total block timeout has been exceeded.
     * @return stop reason string if timed out, null otherwise
     */
    public String checkTotalTimeout() {
        long elapsedSeconds = elapsedSeconds();
        if (elapsedSeconds > totalTimeoutSeconds) {
            return "TOTAL_TIMEOUT";
        }
        return null;
    }

    /**
     * Returns elapsed seconds since block start.
     */
    public long elapsedSeconds() {
        return (Instant.now().toEpochMilli() - blockStart.toEpochMilli()) / 1000;
    }

    /**
     * Checks if a single iteration exceeded its timeout.
     * @return stop reason string if timed out, null otherwise
     */
    public String checkIterationTimeout(Instant iterStart) {
        long iterMs = Instant.now().toEpochMilli() - iterStart.toEpochMilli();
        if (iterMs > (long) iterationTimeoutSeconds * 1000) {
            return "ITERATION_TIMEOUT";
        }
        return null;
    }

    /**
     * Tracks an action and checks if the same action has been repeated too many times.
     * @return stop reason string if repeated too many times, null otherwise
     */
    public String trackAndCheckRepeatedAction(String action) {
        String actionNormalized = action.strip();
        recentActions.add(actionNormalized);

        if (recentActions.size() >= maxRepeatedActions) {
            List<String> lastN = recentActions.subList(
                    recentActions.size() - maxRepeatedActions, recentActions.size());
            if (lastN.stream().allMatch(a -> a.equals(lastN.getFirst()))) {
                return "REPEATED_ACTIONS";
            }
        }
        return null;
    }

    /**
     * Waits while the execution context is paused.
     * @return true if execution should continue, false if cancelled during pause
     */
    public boolean waitIfPaused(ExecutionContext context) {
        while (context.isPaused() && !context.shouldStop()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !context.shouldStop();
    }
}
