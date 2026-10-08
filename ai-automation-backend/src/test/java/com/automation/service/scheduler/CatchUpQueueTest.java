package com.automation.service.scheduler;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.entity.Workflow;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.scheduler.ScheduledWorkflowExecutor.ScheduledRunRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CatchUpQueueTest {

    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");

    @Mock private ActivityLogService activityLogService;
    @Mock private ScheduledWorkflowExecutor executor;

    private CatchUpQueue queue;

    @BeforeEach
    void setUp() {
        queue = new CatchUpQueue(activityLogService);
    }

    private static ScheduledWorkflow schedule(String id, String name) {
        return ScheduledWorkflow.builder()
                .id(id)
                .workflow(Workflow.builder().id("wf-" + id).name(name).build())
                .cronExpression("0 0 * * * *")
                .enabled(true)
                .build();
    }

    @Test
    void enqueue_ordersByMissedTime_andIgnoresDuplicates() {
        queue.enqueue(schedule("late", "Late"), NOW.minusSeconds(60), "cold-start");
        queue.enqueue(schedule("early", "Early"), NOW.minusSeconds(3600), "cold-start");
        queue.enqueue(schedule("late", "Late"), NOW.minusSeconds(60), "cold-start");

        assertThat(queue.snapshot().pending()).extracting(CatchUpQueue.Pending::scheduleId)
                .containsExactly("early", "late");
        assertThat(queue.contains("early")).isTrue();
        assertThat(queue.contains("other")).isFalse();
        verify(activityLogService, times(2)).info(eq(LogCategory.SCHEDULE),
                contains("queued for catch-up"), eq("SchedulerService"), any());
    }

    @Test
    void drain_startsOnlyOne_andWaitsForItToFinish() {
        queue.enqueue(schedule("a", "A"), NOW.minusSeconds(7200), "suspend-resume");
        queue.enqueue(schedule("b", "B"), NOW.minusSeconds(3600), "suspend-resume");
        ScheduledRunRef runA = new ScheduledRunRef("run-a", TargetType.WORKFLOW);
        when(executor.executeSingleSchedule("a")).thenReturn(Optional.of(runA));

        CatchUpQueue.Pending started = queue.drain(executor, NOW);

        assertThat(started.scheduleId()).isEqualTo("a");
        assertThat(queue.snapshot().active().runId()).isEqualTo("run-a");
        assertThat(queue.snapshot().pending()).extracting(CatchUpQueue.Pending::scheduleId).containsExactly("b");
        assertThat(queue.contains("a")).isTrue();

        // Second tick: run A still in progress → nothing starts.
        when(executor.isFinished(runA)).thenReturn(false);
        assertThat(queue.drain(executor, NOW.plusSeconds(60))).isNull();
        verify(executor, never()).executeSingleSchedule("b");

        // Third tick: A finished → B starts.
        when(executor.isFinished(runA)).thenReturn(true);
        when(executor.executeSingleSchedule("b")).thenReturn(Optional.of(new ScheduledRunRef("run-b", TargetType.WORKFLOW)));
        assertThat(queue.drain(executor, NOW.plusSeconds(120)).scheduleId()).isEqualTo("b");
        assertThat(queue.contains("a")).isFalse();
        assertThat(queue.snapshot().pending()).isEmpty();
    }

    @Test
    void drain_declinedEntryDoesNotBlockTheQueue() {
        queue.enqueue(schedule("skipped", "Skipped"), NOW.minusSeconds(7200), "cold-start");
        queue.enqueue(schedule("next", "Next"), NOW.minusSeconds(3600), "cold-start");
        when(executor.executeSingleSchedule("skipped")).thenReturn(Optional.empty());
        when(executor.executeSingleSchedule("next")).thenReturn(Optional.of(new ScheduledRunRef("run-n", TargetType.CHAIN)));

        CatchUpQueue.Pending started = queue.drain(executor, NOW);

        assertThat(started.scheduleId()).isEqualTo("next");
        assertThat(queue.contains("skipped")).isFalse();
    }

    @Test
    void drain_executorExceptionIsLoggedAndQueueContinues() {
        queue.enqueue(schedule("boom", "Boom"), NOW.minusSeconds(7200), "cold-start");
        queue.enqueue(schedule("ok", "Ok"), NOW.minusSeconds(3600), "cold-start");
        when(executor.executeSingleSchedule("boom")).thenThrow(new RuntimeException("fail"));
        when(executor.executeSingleSchedule("ok")).thenReturn(Optional.of(new ScheduledRunRef("run-ok", TargetType.WORKFLOW)));

        assertThat(queue.drain(executor, NOW).scheduleId()).isEqualTo("ok");
        verify(activityLogService).error(eq(LogCategory.SCHEDULE), contains("catch-up failed"),
                eq("SchedulerService"), eq("boom"));
    }

    @Test
    void drain_emptyQueue_isNoop() {
        assertThat(queue.isEmpty()).isTrue();
        assertThat(queue.drain(executor, NOW)).isNull();
        verifyNoInteractions(executor);
    }

    // ── Lock scope: starts, run checks and activity logs run outside the queue's lock (TODO 30cd8235) ──

    /** Bound for the reads below: they must return at once, the start they overlap with is held open. */
    private static final Duration NON_BLOCKING = Duration.ofSeconds(5);

    @Test
    void readsDoNotBlockWhileARunIsStarting_andASecondDrainStartsNothing() throws Exception {
        queue.enqueue(schedule("a", "A"), NOW.minusSeconds(7200), "cold-start");
        queue.enqueue(schedule("b", "B"), NOW.minusSeconds(3600), "cold-start");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(executor.executeSingleSchedule("a")).thenAnswer(inv -> {
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            return Optional.of(new ScheduledRunRef("run-a", TargetType.WORKFLOW));
        });

        try (ExecutorService background = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CatchUpQueue.Pending> draining = background.submit(() -> queue.drain(executor, NOW));
            try {
                assertThat(entered.await(30, TimeUnit.SECONDS)).as("start reached the executor").isTrue();
                assertTimeoutPreemptively(NON_BLOCKING, () -> {
                    // The entry being started is neither lost nor startable twice: it heads the waiting list.
                    assertThat(queue.contains("a")).isTrue();
                    assertThat(queue.isEmpty()).isFalse();
                    assertThat(queue.snapshot().active()).isNull();
                    assertThat(queue.snapshot().pending()).extracting(CatchUpQueue.Pending::scheduleId)
                            .containsExactly("a", "b");
                    assertThat(queue.drain(executor, NOW)).isNull();
                });
            } finally {
                release.countDown();
            }
            assertThat(draining.get(30, TimeUnit.SECONDS).scheduleId()).isEqualTo("a");
        }
        verify(executor, never()).executeSingleSchedule("b");
        assertThat(queue.snapshot().active().runId()).isEqualTo("run-a");
        assertThat(queue.snapshot().pending()).extracting(CatchUpQueue.Pending::scheduleId).containsExactly("b");
    }

    @Test
    void readsDoNotBlockWhileTheActiveRunIsChecked() throws Exception {
        queue.enqueue(schedule("a", "A"), NOW.minusSeconds(7200), "cold-start");
        ScheduledRunRef runA = new ScheduledRunRef("run-a", TargetType.WORKFLOW);
        when(executor.executeSingleSchedule("a")).thenReturn(Optional.of(runA));
        queue.drain(executor, NOW);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(executor.isFinished(runA)).thenAnswer(inv -> {
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            return false;
        });

        try (ExecutorService background = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<CatchUpQueue.Pending> draining = background.submit(() -> queue.drain(executor, NOW.plusSeconds(60)));
            try {
                assertThat(entered.await(30, TimeUnit.SECONDS)).as("drain reached the run check").isTrue();
                assertTimeoutPreemptively(NON_BLOCKING, () -> {
                    assertThat(queue.snapshot().active().runId()).isEqualTo("run-a");
                    assertThat(queue.contains("a")).isTrue();
                });
            } finally {
                release.countDown();
            }
            assertThat(draining.get(30, TimeUnit.SECONDS)).isNull();
        }
        assertThat(queue.snapshot().active().runId()).isEqualTo("run-a");
    }

    @Test
    void enqueueWritesItsActivityLogOutsideTheLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(activityLogService.info(eq(LogCategory.SCHEDULE), contains("queued for catch-up"),
                eq("SchedulerService"), any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        });

        try (ExecutorService background = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> enqueueing = background.submit(
                    () -> queue.enqueue(schedule("a", "A"), NOW.minusSeconds(60), "cold-start"));
            try {
                assertThat(entered.await(30, TimeUnit.SECONDS)).as("enqueue reached the activity log").isTrue();
                assertTimeoutPreemptively(NON_BLOCKING, () -> {
                    assertThat(queue.contains("a")).isTrue();
                    assertThat(queue.snapshot().pending()).extracting(CatchUpQueue.Pending::scheduleId)
                            .containsExactly("a");
                });
            } finally {
                release.countDown();
            }
            enqueueing.get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void failingActivityLogDuringAStartLeavesNoEntryBehind() {
        queue.enqueue(schedule("boom", "Boom"), NOW.minusSeconds(7200), "cold-start");
        when(executor.executeSingleSchedule("boom")).thenThrow(new RuntimeException("fail"));
        when(activityLogService.error(any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("activity log unavailable"));

        assertThatThrownBy(() -> queue.drain(executor, NOW)).isInstanceOf(IllegalStateException.class);

        assertThat(queue.contains("boom")).isFalse();
        assertThat(queue.isEmpty()).isTrue();
        queue.enqueue(schedule("ok", "Ok"), NOW.minusSeconds(3600), "cold-start");
        when(executor.executeSingleSchedule("ok")).thenReturn(Optional.of(new ScheduledRunRef("run-ok", TargetType.WORKFLOW)));
        assertThat(queue.drain(executor, NOW).scheduleId()).isEqualTo("ok");
    }
}
