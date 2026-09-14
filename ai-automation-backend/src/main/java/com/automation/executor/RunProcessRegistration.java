package com.automation.executor;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Resolves cancellation racing with process creation without leaving an unregistered child. */
final class RunProcessRegistration {
    private final AtomicReference<Process> active = new AtomicReference<>();

    void register(Process process, AtomicBoolean cancelled) {
        active.set(process);
        if (cancelled.get() && active.compareAndSet(process, null)) terminate(process);
    }

    void cancel() {
        terminate(active.getAndSet(null));
    }

    void clear() {
        active.set(null);
    }

    private static void terminate(Process process) {
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
