package com.automation.executor;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Getter
@Builder
public class ExecutionContext {

    private final String containerId;
    private final String workflowRunId;

    @Setter
    private String projectPath;

    @Setter
    private String previousOutput;

    @Setter
    private String instructionPrefix;

    @Setter
    private String model;

    @Builder.Default
    private final Map<String, Object> variables = new HashMap<>();

    @Builder.Default
    private final AtomicInteger stepCounter = new AtomicInteger(0);

    @Builder.Default
    private final AtomicInteger preAllocatedIndex = new AtomicInteger(-1);

    @Builder.Default
    private final AtomicBoolean paused = new AtomicBoolean(false);

    @Builder.Default
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    @Builder.Default
    private final AtomicReference<CompletableFuture<String>> pendingApproval = new AtomicReference<>();

    @Builder.Default
    private final AtomicReference<ApprovalRequest> currentApprovalRequest = new AtomicReference<>();

    @Builder.Default
    private final Map<String, String> approvalMemory = new ConcurrentHashMap<>();

    /**
     * Reserve the next step index for a RUNNING placeholder.
     * The subsequent call to nextStepIndex() will return this same value.
     */
    public int preAllocateStepIndex() {
        int idx = stepCounter.getAndIncrement();
        preAllocatedIndex.set(idx);
        return idx;
    }

    public int nextStepIndex() {
        int pre = preAllocatedIndex.getAndSet(-1);
        return pre >= 0 ? pre : stepCounter.getAndIncrement();
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    public String resolveTemplate(String template) {
        if (template == null) return null;

        String resolved = template;
        resolved = resolved.replace("{{ previous_output }}", previousOutput != null ? previousOutput : "");
        resolved = resolved.replace("{{previous_output}}", previousOutput != null ? previousOutput : "");

        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            resolved = resolved.replace("{{ " + entry.getKey() + " }}", String.valueOf(entry.getValue()));
            resolved = resolved.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }

        return resolved;
    }

    public boolean shouldStop() {
        return cancelled.get();
    }

    public boolean isPaused() {
        return paused.get();
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        paused.set(false);
    }

    public void cancel() {
        cancelled.set(true);
        // Cancel any pending approval
        CompletableFuture<String> future = pendingApproval.getAndSet(null);
        if (future != null) {
            future.completeExceptionally(new java.util.concurrent.CancellationException("Execution cancelled"));
        }
        currentApprovalRequest.set(null);
    }

    public CompletableFuture<String> requestApproval(ApprovalRequest request) {
        CompletableFuture<String> future = new CompletableFuture<>();
        currentApprovalRequest.set(request);
        pendingApproval.set(future);
        return future;
    }

    public void resolveApproval(String answer) {
        CompletableFuture<String> future = pendingApproval.getAndSet(null);
        currentApprovalRequest.set(null);
        if (future != null) {
            future.complete(answer);
        }
    }

    public void rememberApproval(String question, String answer) {
        approvalMemory.put(question, answer);
    }

    public String getRememberedApproval(String question) {
        return approvalMemory.get(question);
    }
}
