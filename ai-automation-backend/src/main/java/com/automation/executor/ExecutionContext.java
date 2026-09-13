package com.automation.executor;

import com.automation.entity.BlockDefinition;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

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
public class ExecutionContext {

    private final String containerId;
    private final String workflowRunId;

    @Setter
    private String projectPath;

    /** taint-neutral — für Block-Ergebnisse {@link #recordOutput}, für aus Output abgeleitete Variablen {@link #setDerivedVariable} verwenden; nur für Resume-Seeding/Tests. */
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

    /**
     * Reserved input-context key: names of block outputs derived from untrusted input, plus the
     * pseudo-name {@link #PREVIOUS_OUTPUT_NAME}. Persisted with every step's context delta, so the
     * taint survives resume and sub-workflow hand-over.
     */
    public static final String UNTRUSTED_OUTPUTS_VARIABLE = "__untrusted_outputs";

    /** Pseudo block name for the run's {@code previous_output} inside {@link #UNTRUSTED_OUTPUTS_VARIABLE}. */
    public static final String PREVIOUS_OUTPUT_NAME = "previous_output";

    /** Variables from untrusted sources — always shell-escaped when they land in a shell command. */
    private final Set<String> untrustedVariables = ConcurrentHashMap.newKeySet();

    /** Block outputs derived from untrusted input — shell-escaped like untrusted variables. */
    private final OutputTaint outputTaint = new OutputTaint();

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

    /** Sets a variable derived from block output ({@code step_N_output}, foreach items): untrusted iff tainted. */
    public void setDerivedVariable(String name, Object value) {
        if (hasUntrustedInput()) setUntrustedVariable(name, value);
        else setVariable(name, value);
    }

    public boolean isUntrusted(String name) {
        return untrustedVariables.contains(name);
    }

    /** True as soon as the run carries untrusted input — an untrusted variable or a tainted output. */
    public boolean hasUntrustedInput() {
        return !untrustedVariables.isEmpty() || !outputTaint.isEmpty();
    }

    /**
     * The single write path for a block result: {@code previous_output} plus, for a named block,
     * {{ block.&lt;name&gt;.output }}. Untrusted iff the run already carries untrusted input — taint
     * is sticky for the rest of the run.
     */
    public void recordOutput(String blockName, String output) {
        boolean tainted = hasUntrustedInput();
        setPreviousOutput(output);
        setBlockOutput(blockName, output);
        if (tainted) outputTaint.mark(blockName);
    }

    /** Re-applies one exported taint name ({@link #PREVIOUS_OUTPUT_NAME} or a block name). */
    public void markOutputUntrusted(String name) {
        if (PREVIOUS_OUTPUT_NAME.equals(name)) outputTaint.markPreviousOutput();
        else outputTaint.markBlockOutput(name);
    }

    /** Taint as reserved input-context keys for persistence and hand-over; empty for a clean run. */
    public Map<String, Object> exportTaint() {
        return outputTaint.export(untrustedVariables);
    }

    public Set<String> getUntrustedVariables() {
        return Set.copyOf(untrustedVariables);
    }

    public Object getVariable(String name) {
        return variables.get(name);
    }

    /** AI provider for a block: block config {@code "provider"} > workflow provider > null (system default). See {@link BlockAiSettings}. */
    public String providerFor(BlockDefinition block) {
        return BlockAiSettings.providerFor(block, provider);
    }

    /** Model for a block: block config {@code "model"} > workflow model (same provider only) > null. See {@link BlockAiSettings}. */
    public String modelFor(BlockDefinition block) {
        return BlockAiSettings.modelFor(block, provider, model);
    }

    /** Effort for a block: block config {@code "effort"} > workflow effort > null; invalid block values are ignored. See {@link BlockAiSettings}. */
    public String effortFor(BlockDefinition block) {
        return BlockAiSettings.effortFor(block, effort);
    }

    /** Stores a block's output under its declared name for {{ block.<name>.output }} resolution. Taint-neutral — für Block-Ergebnisse {@link #recordOutput}, für aus Output abgeleitete Variablen {@link #setDerivedVariable} verwenden; nur für Resume-Seeding/Tests. */
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
     * placeholder is single-quoted; otherwise only untrusted variables and tainted block outputs
     * are, trusted values stay raw. See {@link TemplateResolver#resolveShell}.
     */
    public String resolveShellTemplate(String template, boolean escapeAll) {
        if (escapeAll) return resolveTemplateShellSafe(template);
        return TemplateResolver.resolveShell(template, previousOutput, blockOutputs, variables,
                outputTaint.snapshot(untrustedVariables));
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
