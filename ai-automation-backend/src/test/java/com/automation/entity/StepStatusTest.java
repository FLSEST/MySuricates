package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StepStatusTest {

    @Test
    void should_haveAllStepStatusValuesInDeclaredOrder() {
        assertThat(StepStatus.values())
                .containsExactly(
                        StepStatus.PENDING,
                        StepStatus.RUNNING,
                        StepStatus.COMPLETED,
                        StepStatus.FAILED,
                        StepStatus.SKIPPED,
                        StepStatus.WAITING_APPROVAL
                );
    }

    @Test
    void should_resolvePendingViaValueOf() {
        assertThat(StepStatus.valueOf("PENDING")).isEqualTo(StepStatus.PENDING);
    }

    @Test
    void should_resolveRunningViaValueOf() {
        assertThat(StepStatus.valueOf("RUNNING")).isEqualTo(StepStatus.RUNNING);
    }

    @Test
    void should_resolveCompletedViaValueOf() {
        assertThat(StepStatus.valueOf("COMPLETED")).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void should_resolveFailedViaValueOf() {
        assertThat(StepStatus.valueOf("FAILED")).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void should_resolveSkippedViaValueOf() {
        assertThat(StepStatus.valueOf("SKIPPED")).isEqualTo(StepStatus.SKIPPED);
    }

    @Test
    void should_resolveWaitingApprovalViaValueOf() {
        assertThat(StepStatus.valueOf("WAITING_APPROVAL")).isEqualTo(StepStatus.WAITING_APPROVAL);
    }

    @Test
    void should_throwException_when_valueOfReceivesInvalidName() {
        assertThatThrownBy(() -> StepStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
