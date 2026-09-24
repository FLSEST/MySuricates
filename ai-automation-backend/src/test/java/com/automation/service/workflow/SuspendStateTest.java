package com.automation.service.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SuspendState}, the in-memory suspend/resume signal shared between
 * SchedulerService (writer) and GhostRunWatchdogService (reader).
 *
 * <p>The class has no injected dependencies, so it is exercised directly with explicit
 * {@link Instant}/{@link Duration} values — this keeps the time-window edge cases deterministic
 * without mocking a clock.
 */
class SuspendStateTest {

    private SuspendState suspendState;

    @BeforeEach
    void setUp() {
        suspendState = new SuspendState();
    }

    // --- initial state ---

    @Test
    void should_haveNoResumeRecorded_when_freshlyConstructed() {
        assertThat(suspendState.getLastResumeAt()).isNull();
        assertThat(suspendState.getLastGapMillis()).isZero();
    }

    @Test
    void should_notBeRecentlyResumed_when_noResumeRecorded() {
        Instant now = Instant.parse("2026-07-03T12:00:00Z");

        assertThat(suspendState.resumedRecently(now, Duration.ofMinutes(5))).isFalse();
    }

    // --- recordResume + getters (happy path) ---

    @Test
    void should_storeResumeInstantAndGap_when_recordResumeCalled() {
        Instant at = Instant.parse("2026-07-03T12:00:00Z");

        suspendState.recordResume(at, 90_000L);

        assertThat(suspendState.getLastResumeAt()).isEqualTo(at);
        assertThat(suspendState.getLastGapMillis()).isEqualTo(90_000L);
    }

    @Test
    void should_overwritePreviousValues_when_recordResumeCalledAgain() {
        suspendState.recordResume(Instant.parse("2026-07-03T10:00:00Z"), 1_000L);

        Instant newAt = Instant.parse("2026-07-03T12:00:00Z");
        suspendState.recordResume(newAt, 5_000L);

        assertThat(suspendState.getLastResumeAt()).isEqualTo(newAt);
        assertThat(suspendState.getLastGapMillis()).isEqualTo(5_000L);
    }

    // --- resumedRecently: time-window transitions ---

    @Test
    void should_beRecentlyResumed_when_resumeWithinWindow() {
        // Edge case from the TODO: shortly after suspend/resume the window has NOT yet elapsed.
        Instant resumeAt = Instant.parse("2026-07-03T12:00:00Z");
        suspendState.recordResume(resumeAt, 120_000L);

        Instant now = resumeAt.plusSeconds(30); // 30s later, window is 5 min

        assertThat(suspendState.resumedRecently(now, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void should_notBeRecentlyResumed_when_windowHasElapsed() {
        Instant resumeAt = Instant.parse("2026-07-03T12:00:00Z");
        suspendState.recordResume(resumeAt, 120_000L);

        Instant now = resumeAt.plus(Duration.ofMinutes(10)); // window is only 5 min

        assertThat(suspendState.resumedRecently(now, Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void should_beRecentlyResumed_when_resumeExactlyAtWindowBoundary() {
        // Condition is `!resume.isBefore(now.minus(window))` -> boundary is inclusive.
        Instant resumeAt = Instant.parse("2026-07-03T12:00:00Z");
        suspendState.recordResume(resumeAt, 120_000L);

        Instant now = resumeAt.plus(Duration.ofMinutes(5)); // exactly at the window edge

        assertThat(suspendState.resumedRecently(now, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void should_beRecentlyResumed_when_resumeIsInFutureRelativeToNow() {
        Instant resumeAt = Instant.parse("2026-07-03T12:00:00Z");
        suspendState.recordResume(resumeAt, 120_000L);

        Instant now = resumeAt.minusSeconds(10); // now is before the recorded resume

        assertThat(suspendState.resumedRecently(now, Duration.ofMinutes(5))).isTrue();
    }
}
