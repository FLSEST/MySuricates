package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/** Post-action stop rules of the agentic loop, in the original order: iteration timeout, token budget, repeated action. */
@Component
@RequiredArgsConstructor
public class AgenticStopRules {

    private final AgenticEventBroadcaster events;

    /** @return the stop reason, or {@code null} to keep iterating; appends the user-visible line to {@code fullOutput}. */
    public String afterAct(ExecutionContext context, int stepIndex, int iteration, Instant iterStart, String action,
                           AgenticBlockConfig cfg, AgenticTokenTracker tokenTracker, AgenticSafetyGuard safetyGuard,
                           StringBuilder fullOutput) {
        String runId = context.getWorkflowRunId();

        String iterTimeoutReason = safetyGuard.checkIterationTimeout(iterStart);
        if (iterTimeoutReason != null) {
            events.broadcast(runId, stepIndex, "agentic_iteration_timeout", Map.of("iteration", iteration));
            fullOutput.append("[iteration ").append(iteration).append(" - TIMEOUT]\n");
            return iterTimeoutReason;
        }

        String budgetReason = tokenTracker.checkBudget(cfg.maxTotalTokens());
        if (budgetReason != null) {
            int totalTokens = tokenTracker.getRunningTotalTokens();
            events.broadcast(runId, stepIndex, "agentic_token_budget_exceeded",
                    Map.of("iteration", iteration, "totalTokens", totalTokens, "budget", cfg.maxTotalTokens()));
            fullOutput.append("[TOKEN BUDGET EXCEEDED: ").append(totalTokens)
                    .append("/").append(cfg.maxTotalTokens()).append(" tokens]\n");
            return budgetReason;
        }

        String repeatReason = safetyGuard.trackAndCheckRepeatedAction(action);
        if (repeatReason != null) {
            events.broadcast(runId, stepIndex, "agentic_repeated_actions",
                    Map.of("iteration", iteration, "repeatedCount", cfg.maxRepeatedActions()));
            fullOutput.append("[ABORTED: Same action repeated ").append(cfg.maxRepeatedActions()).append(" times]\n");
            return repeatReason;
        }
        return null;
    }
}
