package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The shared wait loop of sub-workflow, foreach and chain entries (TODO 802a516e). */
@ExtendWith(MockitoExtension.class)
class RunCompletionWaiterTest {

    private static final Duration POLL = Duration.ofMillis(1);
    private static final Duration LONG = Duration.ofMinutes(5);

    @Mock private WorkflowRunRepository runs;

    private static Optional<WorkflowRun> run(RunStatus status) {
        return Optional.of(WorkflowRun.builder().id("r1").status(status).build());
    }

    /** Counts the hook calls so a test can see what the loop did around its polls. */
    private static class CountingListener implements RunCompletionWaiter.Listener {
        final AtomicInteger polls = new AtomicInteger();
        final AtomicInteger giveUps = new AtomicInteger();

        @Override
        public void beforePoll() {
            polls.incrementAndGet();
        }

        @Override
        public void onGiveUp() {
            giveUps.incrementAndGet();
        }
    }

    @Test
    void pendingRunningAndPaused_keepTheWaitGoing_untilTheRunEnds() {
        when(runs.findById("r1")).thenReturn(run(RunStatus.PENDING), run(RunStatus.RUNNING),
                run(RunStatus.PAUSED), run(RunStatus.RUNNING), run(RunStatus.COMPLETED));
        CountingListener listener = new CountingListener();

        WorkflowRun ended = RunCompletionWaiter.await(runs, "r1", "Workflow", LONG, POLL, listener);

        assertThat(ended.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(runs, times(5)).findById("r1");
        assertThat(listener.polls).hasValue(5);
        assertThat(listener.giveUps).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = RunStatus.class, names = {"PENDING", "RUNNING", "PAUSED"}, mode = EnumSource.Mode.EXCLUDE)
    void everyStatusOutsideInMotion_endsTheWaitAtOnce(RunStatus status) {
        when(runs.findById("r1")).thenReturn(run(status));

        WorkflowRun ended = RunCompletionWaiter.await(runs, "r1", "Workflow", LONG, POLL,
                new RunCompletionWaiter.Listener() { });

        assertThat(ended.getStatus()).isEqualTo(status);
        verify(runs, times(1)).findById("r1");
    }

    @Test
    void inMotion_isPendingRunningPaused_andShares_noStatusWithTerminalOrInterrupted() {
        assertThat(RunCompletionWaiter.IN_MOTION).doesNotContainAnyElementsOf(RunCleanupService.TERMINAL_STATUSES)
                .doesNotContain(RunStatus.INTERRUPTED)
                .containsExactlyInAnyOrder(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.PAUSED);
    }

    @Test
    void anExceptionFromBeforePoll_abortsTheWait_withoutGivingUpOnTheRun() {
        CountingListener listener = new CountingListener() {
            @Override
            public void beforePoll() {
                throw new IllegalStateException("caller stopped");
            }
        };

        assertThatThrownBy(() -> RunCompletionWaiter.await(runs, "r1", "Workflow", LONG, POLL, listener))
                .isInstanceOf(IllegalStateException.class).hasMessage("caller stopped");
        verify(runs, never()).findById("r1");
        assertThat(listener.giveUps).hasValue(0);
    }

    @Test
    void timeout_givesUpOnce_andNamesTheLabel() {
        when(runs.findById("r1")).thenReturn(run(RunStatus.PAUSED));
        CountingListener listener = new CountingListener();

        assertThatThrownBy(() -> RunCompletionWaiter.await(runs, "r1", "Sub-workflow", Duration.ofMillis(20), POLL, listener))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("Sub-workflow timed out after 0 minutes");
        assertThat(listener.giveUps).hasValue(1);
    }

    @Test
    void interrupt_givesUpOnce_keepsTheFlag_andNamesTheLabel() {
        when(runs.findById("r1")).thenAnswer(inv -> {
            Thread.currentThread().interrupt();     // the next sleep throws
            return run(RunStatus.RUNNING);
        });
        CountingListener listener = new CountingListener();
        try {
            assertThatThrownBy(() -> RunCompletionWaiter.await(runs, "r1", "Sub-workflow", LONG, POLL, listener))
                    .isInstanceOf(WorkflowExecutionException.class)
                    .hasMessage("Interrupted while waiting for sub-workflow")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(listener.giveUps).hasValue(1);
        } finally {
            Thread.interrupted();                   // leave no flag for the next test
        }
    }

    @Test
    void aVanishedRun_failsWithItsId() {
        when(runs.findById("r1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> RunCompletionWaiter.await(runs, "r1", "Sub-workflow", LONG, POLL,
                new RunCompletionWaiter.Listener() { }))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessage("Sub-workflow run r1 disappeared while waiting");
    }
}
