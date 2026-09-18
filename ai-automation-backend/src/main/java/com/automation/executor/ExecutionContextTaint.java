package com.automation.executor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The taint bookkeeping of one run, in one place: the trust label per variable
 * ({@link VariableTrust}), the taint over block outputs ({@link OutputTaint}) and the moment
 * platform data first reaches a shell command line. {@link ExecutionContext} only delegates —
 * its own class so {@link ExecutionContext} stays inside the source-size ratchet.
 *
 * <p><b>Platform data becomes a taint source on use, not on injection.</b> {@code previous_report}
 * and {@code open_todos} are injected into <em>every</em> run; treating them as untrusted input from
 * the start would quote every block output of every run and change the meaning of long-standing
 * shell commands. They are therefore always shell-escaped, but they only taint the run once
 * {@link TemplateResolver} reports back that such a placeholder was really substituted into a shell
 * command line ({@link #substitutedIntoShell}). From that moment the run behaves exactly like one
 * carrying external untrusted input: block outputs and derived variables (foreach items,
 * {@code step_N_output}) are escaped too, so platform bytes cannot re-enter a command line raw.
 *
 * <p><b>Platform data in an AI prompt colours that block's output, not the run.</b> The laundering
 * path platform data → AI prompt → shell block used to bypass the quoting entirely: the prompt path
 * reported nothing and {@link ExecutionContext#recordOutput} filed the answer as untrusted-free.
 * Since TODO 22e6bec8 {@link TemplateResolver#resolvePrompt} reports its substitutions too
 * ({@link #substitutedIntoPrompt}); the flag is consumed by the next recorded output
 * ({@link #consumePlatformPrompt}), which is therefore escaped in later shell command lines. The run
 * itself stays clean until an output is really recorded — a prompt alone changes nothing.
 *
 * <p>The flag itself is exported as {@link ExecutionContext#PLATFORM_IN_SHELL_VARIABLE} (TODO
 * cadfc423), so a segment resumed before any output was recorded starts tainted instead of clean —
 * {@code ExecutionContextFactory} re-raises it via {@link ExecutionContext#markPlatformUsedInShell()}.
 */
final class ExecutionContextTaint {

    private final VariableTrust variables = new VariableTrust();
    private final OutputTaint outputs = new OutputTaint();
    private final AtomicBoolean platformUsedInShell = new AtomicBoolean(false);
    private final AtomicBoolean platformInPrompt = new AtomicBoolean(false);

    /** Back to trusted — the value now comes from the platform itself (workflow default, system value). */
    void markTrusted(String name) {
        variables.clear(name);
    }

    void markUntrusted(String name) {
        variables.markUntrusted(name);
    }

    void markPlatform(String name) {
        variables.markPlatform(name);
    }

    boolean isUntrusted(String name) {
        return variables.isUntrusted(name);
    }

    /** Names persisted as run taint ({@code __untrusted_keys}) — external input only. */
    Set<String> untrustedVariables() {
        return variables.externalNames();
    }

    /** True as soon as the run carries untrusted external input, used platform data in a shell path, or holds a tainted output. */
    boolean hasUntrustedInput() {
        return variables.hasExternalInput() || platformUsedInShell.get() || !outputs.isEmpty();
    }

    void markOutput(String blockName) {
        outputs.mark(blockName);
    }

    void markPreviousOutput() {
        outputs.markPreviousOutput();
    }

    void markBlockOutput(String blockName) {
        outputs.markBlockOutput(blockName);
    }

    /** Snapshot for {@link TemplateResolver#resolveShell} — escaped variable names plus tainted outputs. */
    TemplateResolver.Taint snapshot() {
        return outputs.snapshot(variables.escapedNames());
    }

    /** Taint as reserved input-context keys for persistence and hand-over; empty for a clean run. */
    Map<String, Object> export() {
        Map<String, Object> exported = outputs.export(variables.externalNames());
        if (!platformUsedInShell.get()) return exported;
        Map<String, Object> withPlatformFlag = new HashMap<>(exported);
        withPlatformFlag.put(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true);
        return withPlatformFlag;
    }

    /** Re-applies an exported {@link ExecutionContext#PLATFORM_IN_SHELL_VARIABLE} flag (resume, sub-workflow hand-over). */
    void markPlatformUsedInShell() {
        platformUsedInShell.set(true);
    }

    /**
     * Callback from {@link TemplateResolver}: this placeholder key was substituted into a shell
     * command line. Only platform data changes state here — external input already taints the run
     * from the moment it is set, and trusted values never do.
     */
    void substitutedIntoShell(String key) {
        if (variables.isPlatform(key)) platformUsedInShell.set(true);
    }

    /**
     * Callback from {@link TemplateResolver#resolvePrompt}: this placeholder key was substituted into
     * an AI prompt. Platform data here does <em>not</em> taint the run — a prompt is no command line —
     * it only marks the output of the block that consumed it (TODO 22e6bec8).
     */
    void substitutedIntoPrompt(String key) {
        if (variables.isPlatform(key)) platformInPrompt.set(true);
    }

    /**
     * Reads and clears the prompt flag: the block that is recording its result now is the one whose
     * prompt carried platform data, so its output is recorded as untrusted. Clearing keeps the flag
     * scoped to that block — a later block with a clean prompt records a clean output again (unless
     * the run is tainted by then, which is sticky by design).
     */
    boolean consumePlatformPrompt() {
        return platformInPrompt.getAndSet(false);
    }
}
