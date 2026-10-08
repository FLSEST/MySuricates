package com.automation.service.scheduler;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ScheduledWorkflow;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.scheduler.ScheduledWorkflowExecutor.ScheduledRunRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * In-memory queue of schedules whose missed occurrence is to be caught up <em>one at a time</em>.
 *
 * <p>Filled by {@link SchedulerTick} when it detects a system resume or a cold start; drained on
 * every tick: the next schedule starts only once the previous catch-up run reached a terminal
 * state. Entries are ordered by their original due time. Queued schedules keep their (past)
 * {@code nextRunAt} until they actually run, so the tick must exclude them from the regular
 * due list ({@link #contains}). Not persisted on purpose: after a restart the same schedules are
 * due again and are re-queued by the cold-start policy — nothing is lost, nothing runs twice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CatchUpQueue {

    /** A schedule waiting for its catch-up run. */
    public record Pending(String scheduleId, String targetName, Instant missedAt) {}

    /** The catch-up run currently in progress. */
    public record Active(String scheduleId, String targetName, String runId, Instant startedAt) {}

    /** Read-only view for the API. */
    public record Snapshot(Active active, List<Pending> pending) {}

    private final ActivityLogService activityLogService;

    /**
     * Guards the fields below and is held only for in-memory work: run starts, run checks and
     * activity-log writes reach the database (pool size 1) and stay outside, so the API's
     * {@link #snapshot} and the tick's {@link #contains} never wait for them (TODO 30cd8235).
     */
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Pending> pending = new ArrayList<>();
    private Active active;
    private ScheduledRunRef activeRef;
    /** Taken off {@link #pending}, start in progress; still reported as waiting so it cannot be queued twice. */
    private Pending starting;
    /** A {@link #drain} call is in progress; a second one returns at once, so only one run starts at a time. */
    private boolean draining;

    /** Queue a schedule for catch-up (ignored when already queued or currently running). */
    public void enqueue(ScheduledWorkflow scheduled, Instant missedAt, String reason) {
        Integer position = withLock(() -> {
            if (containsLocked(scheduled.getId())) return null;
            Pending entry = new Pending(scheduled.getId(), scheduled.getTargetName(), missedAt);
            pending.add(entry);
            pending.sort(Comparator.comparing(Pending::missedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            // 1-based place in the waiting list as snapshot() shows it: the entry being started comes first.
            return pending.indexOf(entry) + 1 + (starting == null ? 0 : 1);
        });
        if (position == null) return;
        log.info("Schedule {} queued for catch-up ({}), position {}", scheduled.getId(), reason, position);
        activityLogService.info(LogCategory.SCHEDULE,
                "Schedule queued for catch-up (" + reason + "): " + scheduled.getTargetName(),
                "SchedulerService", scheduled.getId());
    }

    public boolean contains(String scheduleId) {
        return withLock(() -> containsLocked(scheduleId));
    }

    public boolean isEmpty() {
        return withLock(() -> active == null && starting == null && pending.isEmpty());
    }

    public Snapshot snapshot() {
        return withLock(() -> {
            List<Pending> waiting = new ArrayList<>();
            if (starting != null) waiting.add(starting);
            waiting.addAll(pending);
            return new Snapshot(active, List.copyOf(waiting));
        });
    }

    /**
     * Start the next queued schedule if no catch-up run is in progress. Schedules that the
     * executor declines (disabled meanwhile, target already RUNNING, …) are dropped and the next
     * one is tried in the same call, so a declined entry never blocks the queue.
     *
     * @return the schedule started in this call, or {@code null}
     */
    public Pending drain(ScheduledWorkflowExecutor executor, Instant now) {
        Active current;
        ScheduledRunRef currentRef;
        lock.lock();
        try {
            if (draining) return null;
            draining = true;
            current = active;
            currentRef = activeRef;
        } finally {
            lock.unlock();
        }
        try {
            if (current != null) {
                if (!executor.isFinished(currentRef)) return null;
                log.info("Catch-up run {} for schedule {} finished", current.runId(), current.scheduleId());
                runLocked(() -> {
                    active = null;
                    activeRef = null;
                });
            }
            return startNext(executor, now);
        } finally {
            runLocked(() -> draining = false);
        }
    }

    /** Only called by the one {@link #drain} in progress, which is the only writer of {@code active}. */
    private Pending startNext(ScheduledWorkflowExecutor executor, Instant now) {
        while (true) {
            Pending next = withLock(() -> {
                starting = pending.isEmpty() ? null : pending.remove(0);
                return starting;
            });
            if (next == null) return null;
            ScheduledRunRef ref = null;
            int waiting;
            try {
                ref = start(executor, next);
            } finally {
                waiting = settle(next, ref, now);
            }
            if (ref != null) {
                activityLogService.info(LogCategory.SCHEDULE,
                        "Catch-up started: " + next.targetName() + (waiting == 0 ? "" : " (" + waiting + " waiting)"),
                        "SchedulerService", next.scheduleId());
                return next;
            }
        }
    }

    /** Starts one schedule outside the lock; {@code null} when the executor declined or failed. */
    private ScheduledRunRef start(ScheduledWorkflowExecutor executor, Pending next) {
        try {
            var ref = executor.executeSingleSchedule(next.scheduleId());
            if (ref.isEmpty()) {
                log.info("Catch-up for schedule {} declined by executor — trying next", next.scheduleId());
            }
            return ref.orElse(null);
        } catch (Exception e) {
            log.error("Catch-up for schedule {} failed: {}", next.scheduleId(), e.getMessage(), e);
            activityLogService.error(LogCategory.SCHEDULE,
                    "Schedule catch-up failed: " + e.getMessage(), "SchedulerService", next.scheduleId());
            return null;
        }
    }

    /** The started entry leaves {@code starting} and, if a run was started, becomes the active one. */
    private int settle(Pending next, ScheduledRunRef ref, Instant now) {
        return withLock(() -> {
            starting = null;
            if (ref != null) {
                active = new Active(next.scheduleId(), next.targetName(), ref.runId(), now);
                activeRef = ref;
            }
            return pending.size();
        });
    }

    private boolean containsLocked(String scheduleId) {
        if (active != null && active.scheduleId().equals(scheduleId)) return true;
        if (starting != null && starting.scheduleId().equals(scheduleId)) return true;
        return pending.stream().anyMatch(p -> p.scheduleId().equals(scheduleId));
    }

    private <T> T withLock(Supplier<T> action) {
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void runLocked(Runnable action) {
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }
}
