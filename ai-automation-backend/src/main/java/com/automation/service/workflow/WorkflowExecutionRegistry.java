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
     * Registers the context for a run and returns the context that was registered before, or
     * {@code null} when this is the first one. {@link WorkflowExecutor} uses the return value to
     * carry a cancel/pause flag over from the provisional context it registers before the
     * execution thread starts.
     */
    public ExecutionContext register(String runId, ExecutionContext context) {
        return activeExecutions.put(runId, context);
    }

    public void unregister(String runId) {
        activeExecutions.remove(runId);
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
