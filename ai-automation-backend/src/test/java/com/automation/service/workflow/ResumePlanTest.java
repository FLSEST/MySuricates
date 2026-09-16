package com.automation.service.workflow;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private static StepResult step(int index, StepStatus status) {
        StepResult step = new StepResult();
        step.setStepIndex(index);
        step.setStatus(status);
        return step;
    }
}
