package com.automation.mapper;

import com.automation.entity.StepStatus;
import org.junit.jupiter.api.Test;

import static com.automation.dto.response.WorkflowRunListItemResponse.PREVIEW_LENGTH;
import static org.assertj.core.api.Assertions.assertThat;

class RunStepSummaryTest {

    @Test
    void noneHatKeineSchritteUndKeineVorschau() {
        assertThat(RunStepSummary.NONE.count()).isZero();
        assertThat(RunStepSummary.NONE.lastStatus()).isNull();
        assertThat(RunStepSummary.NONE.preview()).isNull();
    }

    @Test
    void kopfIstEinZeichenLaengerAlsDieVorschau() {
        assertThat(RunStepSummary.HEAD_LENGTH).isEqualTo(PREVIEW_LENGTH + 1);
    }

    @Test
    void leererOutputBleibtLeerStattNull() {
        assertThat(summary("").preview()).isEmpty();
    }

    @Test
    void outputBisZurVorschaulaengeBleibtUngekuerzt() {
        String exact = "x".repeat(PREVIEW_LENGTH);

        assertThat(summary(exact).preview()).isEqualTo(exact);
        assertThat(summary("kurz").preview()).isEqualTo("kurz");
    }

    @Test
    void einZeichenMehrWirdGekuerztUndMarkiert() {
        String head = "a".repeat(PREVIEW_LENGTH) + "b";

        assertThat(summary(head).preview()).isEqualTo("a".repeat(PREVIEW_LENGTH) + " […]");
    }

    @Test
    void auchLaengererKopfWirdAufDieVorschaulaengeGekuerzt() {
        String head = "c".repeat(PREVIEW_LENGTH * 2);

        assertThat(summary(head).preview()).hasSize(PREVIEW_LENGTH + " […]".length()).endsWith(" […]");
    }

    private static RunStepSummary summary(String lastOutputHead) {
        return new RunStepSummary(3, StepStatus.COMPLETED, lastOutputHead);
    }
}
