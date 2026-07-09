package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.service.ai.ReasoningEffort;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Getter
@Builder
@Slf4j
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

    @Setter
    private String provider;

    /** Workflow-level reasoning-effort (low/medium/high/xhigh); null = provider default. */
    @Setter
    private String effort;

    @Builder.Default
    private final Map<String, Object> variables = new HashMap<>();

    /** Named block outputs keyed by block name. Resolved via {{ block.<name>.output }}. */
    @Builder.Default
    private final Map<String, String> blockOutputs = new ConcurrentHashMap<>();

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

    @Builder.Default
    private final Map<String, Object> runMetadata = new ConcurrentHashMap<>();

    @Builder.Default
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

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

    public void setRunMetadata(String key, Object value) {
        runMetadata.put(key, value);
    }

    public void setVariable(String name, Object value) {
        variables.put(name, value);
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    /**
     * Effort level for a block: block config {@code "effort"} wins over the workflow-level
     * effort. Invalid block values are ignored (with a warning), not escalated — the run
     * proceeds with the workflow effort or provider default.
     */
    public String effortFor(BlockDefinition block) {
        if (block != null && block.getConfig() != null) {
            Object raw = block.getConfig().get("effort");
            if (raw instanceof String s && !s.isBlank()) {
                var parsed = ReasoningEffort.parse(s);
                if (parsed.isPresent()) {
                    return parsed.get().cliValue();
                }
                log.warn("Ignoring invalid block effort '{}' (valid: low, medium, high, xhigh)", s);
            }
        }
        return effort;
    }

    /** Stores a block's output under its declared name for {{ block.<name>.output }} resolution. */
    public void setBlockOutput(String blockName, String output) {
        if (blockName == null || blockName.isBlank()) return;
        blockOutputs.put(blockName, output == null ? "" : output);
    }

    public String getBlockOutput(String blockName) {
        return blockOutputs.get(blockName);
    }

    public String resolveTemplate(String template) {
        if (template == null) return null;

        String resolved = template;
        resolved = resolved.replace("{{ previous_output }}", previousOutput != null ? previousOutput : "");
        resolved = resolved.replace("{{previous_output}}", previousOutput != null ? previousOutput : "");

        for (Map.Entry<String, String> entry : blockOutputs.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue();
            resolved = resolved.replace("{{ block." + entry.getKey() + ".output }}", value);
            resolved = resolved.replace("{{block." + entry.getKey() + ".output}}", value);
        }

        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            resolved = resolved.replace("{{ " + entry.getKey() + " }}", String.valueOf(entry.getValue()));
            resolved = resolved.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }

        return resolved;
    }

    /**
     * Resolves template placeholders with shell-safe escaping.
     * Each variable value is wrapped in POSIX single quotes with internal
     * single quotes escaped as '\'' to prevent shell injection.
     */
    public String resolveTemplateShellSafe(String template) {
        if (template == null) return null;

        String resolved = template;
        String safePreviousOutput = shellEscape(previousOutput != null ? previousOutput : "");
        resolved = resolved.replace("{{ previous_output }}", safePreviousOutput);
        resolved = resolved.replace("{{previous_output}}", safePreviousOutput);

        for (Map.Entry<String, String> entry : blockOutputs.entrySet()) {
            String safeValue = shellEscape(entry.getValue() == null ? "" : entry.getValue());
            resolved = resolved.replace("{{ block." + entry.getKey() + ".output }}", safeValue);
            resolved = resolved.replace("{{block." + entry.getKey() + ".output}}", safeValue);
        }

        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String safeValue = shellEscape(String.valueOf(entry.getValue()));
            resolved = resolved.replace("{{ " + entry.getKey() + " }}", safeValue);
            resolved = resolved.replace("{{" + entry.getKey() + "}}", safeValue);
        }

        return resolved;
    }

    static String shellEscape(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\''") + "'";
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
        // Kill any running process (shell block, Claude CLI, etc.)
        Process proc = activeProcess.getAndSet(null);
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
        }
        // Cancel any pending approval
        CompletableFuture<String> future = pendingApproval.getAndSet(null);
        if (future != null) {
            future.completeExceptionally(new java.util.concurrent.CancellationException("Execution cancelled"));
        }
        currentApprovalRequest.set(null);
    }

    public void registerProcess(Process process) {
        activeProcess.set(process);
    }

    public void clearProcess() {
        activeProcess.set(null);
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
