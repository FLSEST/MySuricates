package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgenticSafetyGuardTest {

    @Test
    void checkTotalTimeout_withinLimit_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        assertThat(guard.checkTotalTimeout()).isNull();
    }

    @Test
    void checkTotalTimeout_exceeded_returnsStopReason() throws Exception {
        // 1s timeout, wait 2.1s to ensure elapsedSeconds() > 1
        AgenticSafetyGuard guard = new AgenticSafetyGuard(1, 300, 3);
        Thread.sleep(2100);
        assertThat(guard.checkTotalTimeout()).isEqualTo("TOTAL_TIMEOUT");
    }

    @Test
    void checkIterationTimeout_withinLimit_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        Instant iterStart = Instant.now();
        assertThat(guard.checkIterationTimeout(iterStart)).isNull();
    }

    @Test
    void checkIterationTimeout_exceeded_returnsStopReason() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 1, 3);
        // Iteration started 2 seconds ago
        Instant iterStart = Instant.now().minusSeconds(2);
        assertThat(guard.checkIterationTimeout(iterStart)).isEqualTo("ITERATION_TIMEOUT");
    }

    @Test
    void trackAndCheckRepeatedAction_differentActions_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);

        assertThat(guard.trackAndCheckRepeatedAction("Action A")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Action B")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Action C")).isNull();
    }

    @Test
    void trackAndCheckRepeatedAction_sameActionRepeated_returnsStopReason() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);

        assertThat(guard.trackAndCheckRepeatedAction("Same action")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Same action")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Same action")).isEqualTo("REPEATED_ACTIONS");
    }

    @Test
    void trackAndCheckRepeatedAction_interruptedRepetition_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);

        assertThat(guard.trackAndCheckRepeatedAction("Action A")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Action A")).isNull();
        assertThat(guard.trackAndCheckRepeatedAction("Action B")).isNull(); // breaks the streak
        assertThat(guard.trackAndCheckRepeatedAction("Action A")).isNull(); // starts new streak
    }

    @Test
    void waitIfPaused_notPaused_returnsTrue() {
        ExecutionContext context = ExecutionContext.builder()
                .containerId("test")
                .workflowRunId("run-1")
                .build();

        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        assertThat(guard.waitIfPaused(context)).isTrue();
    }

    @Test
    void waitIfPaused_cancelled_returnsFalse() {
        ExecutionContext context = ExecutionContext.builder()
                .containerId("test")
                .workflowRunId("run-1")
                .build();
        context.cancel();

        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        assertThat(guard.waitIfPaused(context)).isFalse();
    }

    @Test
    void elapsedSeconds_returnsNonNegative() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 300, 3);
        assertThat(guard.elapsedSeconds()).isGreaterThanOrEqualTo(0);
    }
}
