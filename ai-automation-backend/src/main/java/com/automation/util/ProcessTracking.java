package com.automation.util;

import java.util.function.Supplier;

/**
 * Binds a tracker for the processes started on the run's thread, so cancelling a run can kill a
 * running Claude/pi/Codex CLI instead of waiting for its timeout. Same pattern as the live-log
 * sink: {@link ProcessDrain} and the Codex runner report start and end without every request
 * record carrying the execution context. Unbound threads (assistant chat, tagging, search) get
 * {@link Tracker#NONE}.
 *
 * <p>Deliberately a plain, non-inheritable {@link ThreadLocal}: a sub-workflow runs its own
 * context and must not register its processes with the parent run.
 */
public final class ProcessTracking {

    public interface Tracker {
        Tracker NONE = new Tracker() {
            @Override
            public void started(Process process) {
                // not inside a workflow step
            }

            @Override
            public void finished(Process process) {
                // not inside a workflow step
            }
        };

        void started(Process process);

        void finished(Process process);
    }

    private static final ThreadLocal<Tracker> CURRENT = new ThreadLocal<>();

    private ProcessTracking() {}

    public static Tracker current() {
        Tracker tracker = CURRENT.get();
        return tracker != null ? tracker : Tracker.NONE;
    }

    /** Runs {@code body} with {@code tracker} bound and restores the previous binding afterwards. */
    public static <T> T bind(Tracker tracker, Supplier<T> body) {
        Tracker previous = CURRENT.get();
        CURRENT.set(tracker);
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
