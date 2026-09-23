package com.automation.executor;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepFailureSummaryTest {

    @Test
    void runError_startsWithTheSummaryTheBlockWrote() {
        StepResult step = failed(3, "[a]: GATE PASS\n[b]: API Error\n[c]: GATE PASS");
        StepFailureSummary.put(step, "1/3 items failed: b\n[b]: API Error");

        assertThat(StepFailureSummary.runError(step, "foreach"))
                .isEqualTo("Step 3 (foreach) failed: 1/3 items failed: b\n[b]: API Error");
    }

    @Test
    void runError_withoutSummary_keepsTheOutputTail() {
        StepResult step = failed(0, "x".repeat(970) + "HTTP 500 Internal Server Error");

        String message = StepFailureSummary.runError(step, "simple");

        assertThat(message).startsWith("Step 0 (simple) failed: xxx")
                .contains("[... skipped 500 of 1000 characters ...]")
                .endsWith("HTTP 500 Internal Server Error");
    }

    @Test
    void runError_blankSummaryOrNoOutput_fallsBackWithoutNull() {
        StepResult blank = failed(1, "boom");
        blank.setMetadata(Map.of(StepFailureSummary.KEY, "  "));
        StepResult empty = failed(2, null);
        empty.setMetadata(null);

        assertThat(StepFailureSummary.runError(blank, "shell")).isEqualTo("Step 1 (shell) failed: boom");
        assertThat(StepFailureSummary.runError(empty, "shell")).isEqualTo("Step 2 (shell) failed: ");
    }

    @Test
    void put_keepsTheOtherMetadata_evenOnAnImmutableMap() {
        StepResult step = failed(0, "out");
        step.setMetadata(Map.of("totalItems", 3));

        StepFailureSummary.put(step, "why");

        assertThat(step.getMetadata()).containsEntry("totalItems", 3).containsEntry(StepFailureSummary.KEY, "why");
    }

    private static StepResult failed(int index, String output) {
        return StepResult.builder().stepIndex(index).status(StepStatus.FAILED).output(output).build();
    }
}
