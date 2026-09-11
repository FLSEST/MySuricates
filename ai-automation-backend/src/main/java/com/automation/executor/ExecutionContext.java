package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.service.ai.ReasoningEffort;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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

    /**
     * Reserved input-context key: a list of variable names that were supplied by an untrusted
     * external source (e.g. a webhook payload). Set by the trigger, read by ExecutionContextFactory,
     * persisted with the run's input context so it survives resume and sub-workflow hand-over.
     */
    public static final String UNTRUSTED_KEYS_VARIABLE = "__untrusted_keys";

    /** Variables from untrusted sources — always shell-escaped when they land in a shell command. */
    private final Set<String> untrustedVariables = ConcurrentHashMap.newKeySet();

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

    /** Sets a trusted variable (workflow defaults, system values, block-internal values). Clears any taint. */
    public void setVariable(String name, Object value) {
        variables.put(name, value);
        untrustedVariables.remove(name);
    }

    /** Sets a variable that came from an untrusted external source (webhook payload etc.). */
    public void setUntrustedVariable(String name, Object value) {
        variables.put(name, value);
        untrustedVariables.add(name);
    }

    public boolean isUntrusted(String name) {
        return untrustedVariables.contains(name);
    }

    public Set<String> getUntrustedVariables() {
        return Set.copyOf(untrustedVariables);
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
        return TemplateResolver.resolve(template, previousOutput, blockOutputs, variables);
    }

    /**
     * Resolves template placeholders with shell-safe escaping: every value is wrapped in POSIX
     * single quotes (internal quotes escaped as '\''). See {@link TemplateResolver#resolveShellSafe}.
     */
    public String resolveTemplateShellSafe(String template) {
        return TemplateResolver.resolveShellSafe(template, previousOutput, blockOutputs, variables);
    }

    /**
     * Resolves a template that becomes a shell command line. With {@code escapeAll} every
     * placeholder is single-quoted; otherwise only variables marked untrusted are, trusted values
     * stay raw. See {@link TemplateResolver#resolveShell}.
     */
    public String resolveShellTemplate(String template, boolean escapeAll) {
        if (escapeAll) return resolveTemplateShellSafe(template);
        return TemplateResolver.resolveShell(template, previousOutput, blockOutputs, variables, untrustedVariables);
    }

    static String shellEscape(String value) {
        return TemplateResolver.shellEscape(value);
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
