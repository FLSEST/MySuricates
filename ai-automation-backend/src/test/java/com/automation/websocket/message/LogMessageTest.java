package com.automation.websocket.message;

import com.automation.entity.StepStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogMessageTest {

    @Test
    void should_storeAllFields_when_constructedWithValues() {
        LogMessage msg = new LogMessage("run-123", 2, StepStatus.RUNNING, "input-data", "output-data", 1500L);

        assertThat(msg.runId()).isEqualTo("run-123");
        assertThat(msg.stepIndex()).isEqualTo(2);
        assertThat(msg.status()).isEqualTo(StepStatus.RUNNING);
        assertThat(msg.input()).isEqualTo("input-data");
        assertThat(msg.output()).isEqualTo("output-data");
        assertThat(msg.durationMs()).isEqualTo(1500L);
    }

    @Test
    void should_acceptNullStringFields_when_constructed() {
        LogMessage msg = new LogMessage(null, 0, null, null, null, 0L);

        assertThat(msg.runId()).isNull();
        assertThat(msg.stepIndex()).isZero();
        assertThat(msg.status()).isNull();
        assertThat(msg.input()).isNull();
        assertThat(msg.output()).isNull();
        assertThat(msg.durationMs()).isZero();
    }

    @Test
    void should_handleNegativeStepIndex_when_constructed() {
        LogMessage msg = new LogMessage("run-1", -1, StepStatus.PENDING, "in", "out", 0L);

        assertThat(msg.stepIndex()).isEqualTo(-1);
    }

    @Test
    void should_handleLargeDuration_when_constructed() {
        LogMessage msg = new LogMessage("run-1", 0, StepStatus.COMPLETED, "in", "out", Long.MAX_VALUE);

        assertThat(msg.durationMs()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        LogMessage a = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in", "out", 100L);
        LogMessage b = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in", "out", 100L);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_runIdDiffers() {
        LogMessage a = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in", "out", 100L);
        LogMessage b = new LogMessage("run-2", 1, StepStatus.COMPLETED, "in", "out", 100L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_stepIndexDiffers() {
        LogMessage a = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in", "out", 100L);
        LogMessage b = new LogMessage("run-1", 2, StepStatus.COMPLETED, "in", "out", 100L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_statusDiffers() {
        LogMessage a = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in", "out", 100L);
        LogMessage b = new LogMessage("run-1", 1, StepStatus.FAILED, "in", "out", 100L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_inputDiffers() {
        LogMessage a = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in-a", "out", 100L);
        LogMessage b = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in-b", "out", 100L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_outputDiffers() {
        LogMessage a = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in", "out-a", 100L);
        LogMessage b = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in", "out-b", 100L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_durationDiffers() {
        LogMessage a = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in", "out", 100L);
        LogMessage b = new LogMessage("run-1", 1, StepStatus.COMPLETED, "in", "out", 200L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containAllFieldsInToString() {
        LogMessage msg = new LogMessage("run-xyz", 5, StepStatus.RUNNING, "the-input", "the-output", 999L);

        String str = msg.toString();

        assertThat(str)
                .contains("run-xyz")
                .contains("5")
                .contains("RUNNING")
                .contains("the-input")
                .contains("the-output")
                .contains("999");
    }
}
