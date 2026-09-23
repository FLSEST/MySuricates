package com.automation.service.workflow;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResumePlanTest {

    @Test
    void fresh_startsAtTheFirstBlock() {
        assertThat(ResumePlan.FRESH.resuming()).isFalse();
        assertThat(ResumePlan.FRESH.blocksToSkip()).isZero();
        assertThat(ResumePlan.FRESH.firstStepIndex()).isZero();
    }

    @Test
    void wrapperBlockIndices_doNotShiftTheBlockPosition() {
        // foreach with 2 items (step 0, items consumed 1+2), shell (step 3), shell interrupted (step 4)
        ResumePlan plan = ResumePlan.after(List.of(
                step(0, StepStatus.COMPLETED),
                step(3, StepStatus.COMPLETED),
                step(4, StepStatus.RUNNING)));

        assertThat(plan.resuming()).isTrue();
        assertThat(plan.blocksToSkip()).isEqualTo(2);      // two blocks done — not "skip up to index 4"
        assertThat(plan.firstStepIndex()).isEqualTo(5);    // above every persisted index
    }

    @Test
    void failedStepThatDidNotStopTheRun_countsAsAPassedBlock() {
        ResumePlan plan = ResumePlan.after(List.of(
                step(0, StepStatus.COMPLETED),
                step(1, StepStatus.FAILED),
                step(2, StepStatus.COMPLETED),
                step(3, StepStatus.RUNNING)));

        assertThat(plan.blocksToSkip()).isEqualTo(3);
        assertThat(plan.firstStepIndex()).isEqualTo(4);
    }

    @Test
    void trailingFailure_resumesAtTheFailedBlock() {
        ResumePlan plan = ResumePlan.after(List.of(
                step(0, StepStatus.COMPLETED),
                step(1, StepStatus.FAILED)));

        assertThat(plan.blocksToSkip()).isEqualTo(1);
        assertThat(plan.firstStepIndex()).isEqualTo(2);
    }

    @Test
    void unorderedSteps_areSortedByIndexFirst() {
        ResumePlan plan = ResumePlan.after(List.of(
                step(4, StepStatus.RUNNING),
                step(0, StepStatus.COMPLETED),
                step(3, StepStatus.COMPLETED)));

        assertThat(plan.blocksToSkip()).isEqualTo(2);
        assertThat(plan.firstStepIndex()).isEqualTo(5);
    }

    @Test
    void noPersistedSteps_startsAtTheFirstBlock() {
        ResumePlan plan = ResumePlan.after(List.of());

        assertThat(plan.resuming()).isTrue();
        assertThat(plan.blocksToSkip()).isZero();
        assertThat(plan.firstStepIndex()).isZero();
    }

    @Test
    void secondResume_countsBlocksNotStaleAttempts() {
        // TODO cd0ea0f9 — foreach(0), a(1), b interrupted (2), b resumed (3), c interrupted (4)
        ResumePlan plan = ResumePlan.after(List.of(
                step(0, StepStatus.COMPLETED, 0),
                step(3, StepStatus.COMPLETED, 1),
                step(4, StepStatus.RUNNING, 2),
                step(5, StepStatus.COMPLETED, 2),
                step(6, StepStatus.RUNNING, 3)));

        assertThat(plan.blocksToSkip()).isEqualTo(3);      // c (block 3) runs again — not skipped
        assertThat(plan.firstStepIndex()).isEqualTo(7);
    }

    @Test
    void stepsWithoutBlockIndex_fallBackToTheirPosition() {
        ResumePlan plan = ResumePlan.after(List.of(
                step(0, StepStatus.COMPLETED, 0),
                step(3, StepStatus.COMPLETED),
                step(4, StepStatus.RUNNING)));

        assertThat(plan.blocksToSkip()).isEqualTo(2);
    }

    private static StepResult step(int index, StepStatus status, int blockIndex) {
        StepResult step = step(index, status);
        step.setMetadata(new HashMap<>(Map.of("blockIndex", blockIndex)));
        return step;
    }

    private static StepResult step(int index, StepStatus status) {
        StepResult step = new StepResult();
        step.setStepIndex(index);
        step.setStatus(status);
        return step;
    }
}
