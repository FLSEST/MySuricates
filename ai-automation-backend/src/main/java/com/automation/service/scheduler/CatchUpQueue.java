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

    private final List<Pending> pending = new ArrayList<>();
    private Active active;
    private ScheduledRunRef activeRef;

    /** Queue a schedule for catch-up (ignored when already queued or currently running). */
    public synchronized void enqueue(ScheduledWorkflow scheduled, Instant missedAt, String reason) {
        if (contains(scheduled.getId())) return;
        pending.add(new Pending(scheduled.getId(), scheduled.getTargetName(), missedAt));
        pending.sort(Comparator.comparing(Pending::missedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        log.info("Schedule {} queued for catch-up ({}), position {}", scheduled.getId(), reason, pending.size());
        activityLogService.info(LogCategory.SCHEDULE,
                "Schedule queued for catch-up (" + reason + "): " + scheduled.getTargetName(),
                "SchedulerService", scheduled.getId());
    }

    public synchronized boolean contains(String scheduleId) {
        if (active != null && active.scheduleId().equals(scheduleId)) return true;
        return pending.stream().anyMatch(p -> p.scheduleId().equals(scheduleId));
    }

    public synchronized boolean isEmpty() {
        return active == null && pending.isEmpty();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(active, List.copyOf(pending));
    }

    /**
     * Start the next queued schedule if no catch-up run is in progress. Schedules that the
     * executor declines (disabled meanwhile, target already RUNNING, …) are dropped and the next
     * one is tried in the same call, so a declined entry never blocks the queue.
     *
     * @return the schedule started in this call, or {@code null}
     */
    public synchronized Pending drain(ScheduledWorkflowExecutor executor, Instant now) {
        if (active != null) {
            if (!executor.isFinished(activeRef)) {
                return null;
            }
            log.info("Catch-up run {} for schedule {} finished", active.runId(), active.scheduleId());
            active = null;
            activeRef = null;
        }
        while (!pending.isEmpty()) {
            Pending next = pending.remove(0);
            try {
                var ref = executor.executeSingleSchedule(next.scheduleId());
                if (ref.isPresent()) {
                    active = new Active(next.scheduleId(), next.targetName(), ref.get().runId(), now);
                    activeRef = ref.get();
                    activityLogService.info(LogCategory.SCHEDULE,
                            "Catch-up started: " + next.targetName()
                                    + (pending.isEmpty() ? "" : " (" + pending.size() + " waiting)"),
                            "SchedulerService", next.scheduleId());
                    return next;
                }
                log.info("Catch-up for schedule {} declined by executor — trying next", next.scheduleId());
            } catch (Exception e) {
                log.error("Catch-up for schedule {} failed: {}", next.scheduleId(), e.getMessage(), e);
                activityLogService.error(LogCategory.SCHEDULE,
                        "Schedule catch-up failed: " + e.getMessage(), "SchedulerService", next.scheduleId());
            }
        }
        return null;
    }
}
