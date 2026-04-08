package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStatusTest {

    @Test
    void should_haveAllRunStatusValuesInDeclaredOrder() {
        assertThat(RunStatus.values())
                .containsExactly(
                        RunStatus.PENDING,
                        RunStatus.RUNNING,
                        RunStatus.PAUSED,
                        RunStatus.COMPLETED,
                        RunStatus.COMPLETED_WITH_ERRORS,
                        RunStatus.FAILED,
                        RunStatus.CANCELLED,
                        RunStatus.INTERRUPTED
                );
    }

    @Test
    void should_resolvePendingViaValueOf() {
        assertThat(RunStatus.valueOf("PENDING")).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void should_resolveRunningViaValueOf() {
        assertThat(RunStatus.valueOf("RUNNING")).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void should_resolvePausedViaValueOf() {
        assertThat(RunStatus.valueOf("PAUSED")).isEqualTo(RunStatus.PAUSED);
    }

    @Test
    void should_resolveCompletedViaValueOf() {
        assertThat(RunStatus.valueOf("COMPLETED")).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void should_resolveCompletedWithErrorsViaValueOf() {
        assertThat(RunStatus.valueOf("COMPLETED_WITH_ERRORS")).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
    }

    @Test
    void should_resolveFailedViaValueOf() {
        assertThat(RunStatus.valueOf("FAILED")).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void should_resolveCancelledViaValueOf() {
        assertThat(RunStatus.valueOf("CANCELLED")).isEqualTo(RunStatus.CANCELLED);
    }

    @Test
    void should_resolveInterruptedViaValueOf() {
        assertThat(RunStatus.valueOf("INTERRUPTED")).isEqualTo(RunStatus.INTERRUPTED);
    }

    @Test
    void should_throwException_when_valueOfReceivesInvalidName() {
        assertThatThrownBy(() -> RunStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
