package com.automation.executor;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trust label per context variable. Two classes of not-to-be-trusted values exist, and they differ
 * only in whether they taint the run:
 *
 * <ul>
 *   <li><b>external</b> ({@link ExecutionContext#setUntrustedVariable}) — a webhook payload and the
 *       like: always shell-escaped <em>and</em> a run-level taint source, so every block output of
 *       the run is escaped too ({@link OutputTaint}).</li>
 *   <li><b>platform</b> ({@link ExecutionContext#setUntrustedPlatformVariable}) — {@code previous_report}
 *       and {@code open_todos}: always shell-escaped, and a taint source only <em>on use</em>. They are
 *       injected into <em>every</em> run, so tainting at injection time would quote every block output
 *       of every run and change the meaning of long-standing shell commands; instead the run is tainted
 *       the moment such a placeholder is really substituted into a shell command line
 *       ({@link ExecutionContextTaint#substitutedIntoShell}).</li>
 * </ul>
 *
 * <p>The classes are exclusive: the last write for a name wins, {@link ExecutionContext#setVariable}
 * clears both. Its own class so {@link ExecutionContext} stays inside the source-size ratchet.
 */
final class VariableTrust {

    private final Set<String> external = ConcurrentHashMap.newKeySet();
    private final Set<String> platform = ConcurrentHashMap.newKeySet();

    void markUntrusted(String name) {
        platform.remove(name);
        external.add(name);
    }

    void markPlatform(String name) {
        external.remove(name);
        platform.add(name);
    }

    /** Back to trusted — the value now comes from the platform itself (workflow default, system value). */
    void clear(String name) {
        external.remove(name);
        platform.remove(name);
    }

    /** True for both classes: the value is shell-escaped wherever it lands in a command line. */
    boolean isUntrusted(String name) {
        return external.contains(name) || platform.contains(name);
    }

    /** True for platform data only — the class that taints the run on use instead of on injection. */
    boolean isPlatform(String name) {
        return platform.contains(name);
    }

    /** Only external input taints a run's outputs from the start; platform data taints it on use. */
    boolean hasExternalInput() {
        return !external.isEmpty();
    }

    /** Names that must be shell-escaped — both classes. */
    Set<String> escapedNames() {
        if (platform.isEmpty()) return Set.copyOf(external);
        Set<String> all = new HashSet<>(external);
        all.addAll(platform);
        return Set.copyOf(all);
    }

    /** Names that are persisted as run taint ({@code __untrusted_keys}) — external only. */
    Set<String> externalNames() {
        return Set.copyOf(external);
    }
}
