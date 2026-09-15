package com.automation.executor;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Processes a run currently waits on — a shell block's process and a Claude/pi/Codex CLI can both be
 * registered. Resolves cancellation racing with process creation without leaving an unregistered child.
 */
final class RunProcessRegistration {
    private final Set<Process> active = ConcurrentHashMap.newKeySet();

    void register(Process process, AtomicBoolean cancelled) {
        active.add(process);
        if (cancelled.get() && active.remove(process)) terminate(process);
    }

    void unregister(Process process) {
        active.remove(process);
    }

    void cancel() {
        for (Process process : List.copyOf(active)) {
            if (active.remove(process)) terminate(process);
        }
    }

    void clear() {
        active.clear();
    }

    private static void terminate(Process process) {
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
