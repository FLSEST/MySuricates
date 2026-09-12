package com.automation.service.workflow;

import com.automation.executor.ApprovalRequest;
import com.automation.executor.ExecutionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for active workflow executions.
 * Owns the activeExecutions map and provides query/control methods.
 * Extracted from WorkflowExecutor to separate execution tracking from execution logic.
 */
@Component
@Slf4j
public class WorkflowExecutionRegistry {

    private final Map<String, ExecutionContext> activeExecutions = new ConcurrentHashMap<>();

    /**
     * Runs whose placeholder context is registered but whose execution thread has not taken over
     * yet. Control operations (cancel, pause) work on such a run by design; anything that needs a
     * container or the project working directory must not — see {@link #getStartedContext}.
     *
     * <p>Kept here rather than as a flag on the context: {@code ExecutionContext.variables} is a
     * plain {@link java.util.HashMap} that block executors mutate from the execution thread, so
     * reading it from a request thread would be a data race.
     */
    private final Set<String> queued = ConcurrentHashMap.newKeySet();

    /**
     * Registers a placeholder context for a run that is about to be submitted. Without it a run is
     * observable (DB status RUNNING) but not controllable for as long as the execution thread needs
     * to get through the semaphore and the container start — a cancel arriving in that window took
     * {@code WorkflowExecutor}'s ghost branch and was then overrun by the thread that kept going.
     *
     * <p>Side effect (intended): {@link #getActiveRunCount()} now counts queued runs too, which is
     * what {@code GhostRunWatchdogService.reconcilePermits} needs — a run parked in
     * {@code executionSemaphore.acquire()} or in the container start holds (or is about to hold) a
     * permit and must not be mistaken for a leak.
     */
    public void registerProvisional(String runId) {
        // Mark before publishing the context: never let getStartedContext hand out the placeholder.
        queued.add(runId);
        register(runId, ExecutionContext.builder().workflowRunId(runId).build());
    }

    /** True when a cancel already arrived — checked by the execution thread before it does any work. */
    public boolean cancelRequested(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        return context != null && context.shouldStop();
    }

    /**
     * Swaps the provisional context for the real one and carries over any control flag that arrived
     * in between. {@link #cancel} double-checks from the other side, so a cancel racing this put
     * lands on the real context either way.
     */
    public void promote(String runId, ExecutionContext context) {
        ExecutionContext previous = register(runId, context);
        if (previous != null) {
            if (previous.shouldStop()) {
                context.cancel();
            }
            if (previous.isPaused()) {
                context.pause();
            }
        }
        // Unmark last: until the flags are carried over, getStartedContext keeps returning null.
        // The overlap can only hide the real context, never expose the placeholder.
        queued.remove(runId);
    }

    /**
     * The context of a run whose execution thread has taken over — {@code null} while the run is
     * still queued (or unknown). Use this wherever a container id or the project working directory
     * is needed; {@link #get} also returns the placeholder.
     */
    public ExecutionContext getStartedContext(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        return context == null || queued.contains(runId) ? null : context;
    }

    /**
     * Registers the context for a run and returns the context that was registered before, or
     * {@code null} when this is the first one. Package-private on purpose: callers go through
     * {@link #registerProvisional} or {@link #promote} so the cancel/pause carry-over and the
     * queued bookkeeping can never be bypassed.
     */
    ExecutionContext register(String runId, ExecutionContext context) {
        return activeExecutions.put(runId, context);
    }

    public void unregister(String runId) {
        activeExecutions.remove(runId);
        queued.remove(runId);
    }

    public ExecutionContext get(String runId) {
        return activeExecutions.get(runId);
    }

    public int getActiveRunCount() {
        return activeExecutions.size();
    }

    public boolean isRunActive(String runId) {
        return activeExecutions.containsKey(runId);
    }

    public Set<String> getActiveContainerIds() {
        return activeExecutions.values().stream()
                .map(ExecutionContext::getContainerId)
                .collect(Collectors.toSet());
    }

    public String getContainerIdForRun(String runId) {
        ExecutionContext ctx = activeExecutions.get(runId);
        return ctx != null ? ctx.getContainerId() : null;
    }

    /**
     * Flags the run for cancellation. Returns {@code false} when no context is registered at all —
     * only then is the run a true ghost (DB says RUNNING, nobody is working on it).
     *
     * <p>Double-checked on purpose: {@link WorkflowExecutor} first registers a provisional context
     * (before the execution thread starts) and later swaps in the real one, so a cancel can race
     * that swap. Both map operations are linearizable, which makes the two orderings exhaustive:
     * <ul>
     *   <li>the second {@code get} is linearized <em>after</em> the executor's {@code register} —
     *       then it returns the real context and this method cancels it directly;</li>
     *   <li>the second {@code get} is linearized <em>before</em> the {@code register} — then the
     *       first {@code get} was too (program order), so {@code first.cancel()}'s volatile write
     *       precedes the {@code register} and therefore precedes the executor's read of
     *       {@code previous.shouldStop()} on the very same context. The executor sees the flag and
     *       cancels the real context itself.</li>
     * </ul>
     * Either way the context the execution thread actually uses ends up cancelled. Cancelling the
     * already-swapped-out provisional context is harmless (nobody reads it any more).
     */
    public boolean cancel(String runId) {
        // Reads via this class's own get() on purpose — the deterministic interleaving test
        // overrides it to swap the context between the two reads.
        ExecutionContext first = get(runId);
        if (first == null) {
            return false;
        }
        first.cancel();
        ExecutionContext second = get(runId);
        if (second != null && second != first) {
            second.cancel();
        }
        return true;
    }

    public void pauseExecution(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.pause();
            log.info("Paused execution: {}", runId);
        } else {
            log.warn("pauseExecution: no active execution found for run {}", runId);
        }
    }

    public void resumeExecution(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.resume();
            log.info("Resumed execution: {}", runId);
        } else {
            log.warn("resumeExecution: no active execution found for run {}", runId);
        }
    }

    public void resolveApproval(String runId, String answer) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            context.resolveApproval(answer);
            log.info("Resolved approval for run {}: {}", runId, answer);
        }
    }

    public int getPendingApprovalCount() {
        return (int) activeExecutions.values().stream()
                .filter(ctx -> ctx.getCurrentApprovalRequest().get() != null)
                .count();
    }

    public ApprovalRequest getPendingApproval(String runId) {
        ExecutionContext context = activeExecutions.get(runId);
        if (context != null) {
            return context.getCurrentApprovalRequest().get();
        }
        return null;
    }
}
