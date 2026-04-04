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

    public void register(String runId, ExecutionContext context) {
        activeExecutions.put(runId, context);
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
