package com.automation.executor;

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
 * <p>Known limit: the "platform data was used in a shell path" flag itself is not exported. It does
 * not need to be — the first {@link ExecutionContext#recordOutput} or
 * {@link ExecutionContext#setDerivedVariable} after the substitution turns it into exported taint
 * ({@link #export()}), and a segment that is resumed before either happened re-resolves the same
 * template and re-raises the flag.
 */
final class ExecutionContextTaint {

    private final VariableTrust variables = new VariableTrust();
    private final OutputTaint outputs = new OutputTaint();
    private final AtomicBoolean platformUsedInShell = new AtomicBoolean(false);

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
        return outputs.export(variables.externalNames());
    }

    /**
     * Callback from {@link TemplateResolver}: this placeholder key was substituted into a shell
     * command line. Only platform data changes state here — external input already taints the run
     * from the moment it is set, and trusted values never do.
     */
    void substitutedIntoShell(String key) {
        if (variables.isPlatform(key)) platformUsedInShell.set(true);
    }
}
