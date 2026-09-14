package com.automation.service.live;

import java.util.function.Supplier;

/**
 * Binds the live-log sink of the running block to the run's thread, so process runners deep in the
 * call chain (HostClaudeService, ShellCommandService, …) can stream output without every request
 * record and executor carrying a listener. Unbound threads — assistant chat, tagging, search —
 * get {@link LiveOutputSink#NOOP}.
 *
 * <p>Deliberately a plain, non-inheritable {@link ThreadLocal}: a sub-workflow is submitted from the
 * parent run's thread and must not stream into the parent's log. Process readers run on their own
 * threads, so runners read {@link #current()} on the calling thread before spawning them.
 */
public final class LiveOutput {

    private static final ThreadLocal<LiveOutputSink> CURRENT = new ThreadLocal<>();

    private LiveOutput() {}

    public static LiveOutputSink current() {
        LiveOutputSink sink = CURRENT.get();
        return sink != null ? sink : LiveOutputSink.NOOP;
    }

    /** Runs {@code body} with {@code sink} bound and restores the previous binding afterwards. */
    public static <T> T bind(LiveOutputSink sink, Supplier<T> body) {
        LiveOutputSink previous = CURRENT.get();
        CURRENT.set(sink);
        try {
            return body.get();
        } finally {
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        }
    }
}
