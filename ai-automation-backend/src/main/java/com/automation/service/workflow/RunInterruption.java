package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a run was doing when the platform marked it INTERRUPTED, and why — written into the run
 * metadata by the startup cleanup ({@link RunControlService#cleanupZombieRuns}) and by the standby
 * branch of {@link GhostRunWatchdogService}. {@link RunAutoResumer} reads it: only a run that was
 * RUNNING is ever resumed automatically; PAUSED is set only by a person and stays with that person.
 */
public final class RunInterruption {

    /** Run metadata key: the status before INTERRUPTED ({@code RUNNING} or {@code PAUSED}). */
    public static final String FROM_KEY = "interruptedFrom";
    /** Run metadata key: the {@link Cause} as its name. */
    public static final String BY_KEY = "interruptedBy";

    /** What interrupted the run. */
    public enum Cause {
        /** The backend was restarted while the run was in flight. */
        RESTART,
        /** A system standby killed the run's processes; the watchdog found it without a thread. */
        STANDBY
    }

    /**
     * Published after runs were marked INTERRUPTED in this JVM lifetime — after the marking, so a
     * listener always sees the saved state. Carries every marked run; listeners pick their own.
     */
    public record Event(List<String> runIds, Cause cause) {
        public Event {
            runIds = List.copyOf(runIds);
        }
    }

    private RunInterruption() {
    }

    /** Records {@code from} and {@code cause} in the run metadata (a copy — the map may be immutable). */
    static void record(WorkflowRun run, RunStatus from, Cause cause) {
        Map<String, Object> metadata = run.getMetadata() != null ? new HashMap<>(run.getMetadata()) : new HashMap<>();
        metadata.put(FROM_KEY, from.name());
        metadata.put(BY_KEY, cause.name());
        run.setMetadata(metadata);
    }
}
