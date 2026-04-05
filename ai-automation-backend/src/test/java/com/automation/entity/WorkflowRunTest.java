package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRunTest {

    // --- Builder defaults tests ---

    @Test
    void should_havePendingStatus_when_builtWithDefaults() {
        WorkflowRun run = WorkflowRun.builder().build();

        assertThat(run.getStatus()).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void should_haveEmptyStepsList_when_builtWithDefaults() {
        WorkflowRun run = WorkflowRun.builder().build();

        assertThat(run.getSteps()).isNotNull().isEmpty();
    }

    @Test
    void should_haveEmptyErrorMap_when_builtWithDefaults() {
        WorkflowRun run = WorkflowRun.builder().build();

        assertThat(run.getError()).isNotNull().isEmpty();
        assertThat(run.getError()).isInstanceOf(HashMap.class);
    }

    @Test
    void should_haveEmptyMetadataMap_when_builtWithDefaults() {
        WorkflowRun run = WorkflowRun.builder().build();

        assertThat(run.getMetadata()).isNotNull().isEmpty();
        assertThat(run.getMetadata()).isInstanceOf(HashMap.class);
    }

    @Test
    void should_haveEmptyInputContextMap_when_builtWithDefaults() {
        WorkflowRun run = WorkflowRun.builder().build();

        assertThat(run.getInputContext()).isNotNull().isEmpty();
        assertThat(run.getInputContext()).isInstanceOf(HashMap.class);
    }

    @Test
    void should_haveNullTimestamps_when_builtWithDefaults() {
        WorkflowRun run = WorkflowRun.builder().build();

        assertThat(run.getStartedAt()).isNull();
        assertThat(run.getCompletedAt()).isNull();
        assertThat(run.getDurationMs()).isNull();
    }

    // --- addStep bidirectional relationship tests ---

    @Test
    void should_setBidirectionalRelationship_when_addStepCalled() {
        WorkflowRun run = WorkflowRun.builder().build();
        StepResult step = StepResult.builder().stepIndex(1).build();

        run.addStep(step);

        assertThat(step.getWorkflowRun()).isSameAs(run);
        assertThat(run.getSteps()).containsExactly(step);
    }

    @Test
    void should_addStepToList_when_addStepCalled() {
        WorkflowRun run = WorkflowRun.builder().build();
        StepResult step = StepResult.builder().stepIndex(1).build();

        run.addStep(step);

        assertThat(run.getSteps()).hasSize(1);
        assertThat(run.getSteps().get(0)).isSameAs(step);
    }

    // --- Auto-index when stepIndex <= 0 ---

    @Test
    void should_autoAssignIndex_when_stepIndexIsZero() {
        WorkflowRun run = WorkflowRun.builder().build();
        StepResult step = StepResult.builder().stepIndex(0).build();

        run.addStep(step);

        assertThat(step.getStepIndex()).isZero(); // steps.size() was 0 before add
    }

    @Test
    void should_autoAssignIndex_when_stepIndexIsNegative() {
        WorkflowRun run = WorkflowRun.builder().build();
        StepResult step = StepResult.builder().stepIndex(-1).build();

        run.addStep(step);

        assertThat(step.getStepIndex()).isZero(); // steps.size() was 0 before add
    }

    @Test
    void should_autoAssignIncrementingIndex_when_multipleStepsWithZeroIndex() {
        WorkflowRun run = WorkflowRun.builder().build();
        StepResult step0 = StepResult.builder().stepIndex(0).build();
        StepResult step1 = StepResult.builder().stepIndex(0).build();
        StepResult step2 = StepResult.builder().stepIndex(0).build();

        run.addStep(step0);
        run.addStep(step1);
        run.addStep(step2);

        assertThat(step0.getStepIndex()).isZero();
        assertThat(step1.getStepIndex()).isEqualTo(1);
        assertThat(step2.getStepIndex()).isEqualTo(2);
    }

    @Test
    void should_preserveExplicitIndex_when_stepIndexIsPositive() {
        WorkflowRun run = WorkflowRun.builder().build();
        StepResult step = StepResult.builder().stepIndex(5).build();

        run.addStep(step);

        assertThat(step.getStepIndex()).isEqualTo(5);
    }

    // --- Multiple steps tests ---

    @Test
    void should_containAllSteps_when_multipleStepsAdded() {
        WorkflowRun run = WorkflowRun.builder().build();
        StepResult step1 = StepResult.builder().stepIndex(1).build();
        StepResult step2 = StepResult.builder().stepIndex(2).build();
        StepResult step3 = StepResult.builder().stepIndex(3).build();

        run.addStep(step1);
        run.addStep(step2);
        run.addStep(step3);

        assertThat(run.getSteps()).hasSize(3);
        assertThat(run.getSteps()).containsExactly(step1, step2, step3);
    }

    @Test
    void should_setWorkflowRunOnAllSteps_when_multipleStepsAdded() {
        WorkflowRun run = WorkflowRun.builder().build();
        StepResult step1 = StepResult.builder().stepIndex(1).build();
        StepResult step2 = StepResult.builder().stepIndex(2).build();

        run.addStep(step1);
        run.addStep(step2);

        assertThat(step1.getWorkflowRun()).isSameAs(run);
        assertThat(step2.getWorkflowRun()).isSameAs(run);
    }

    @Test
    void should_mixAutoAndExplicitIndices_when_stepsHaveDifferentIndexValues() {
        WorkflowRun run = WorkflowRun.builder().build();
        StepResult autoStep = StepResult.builder().stepIndex(0).build();
        StepResult explicitStep = StepResult.builder().stepIndex(10).build();
        StepResult autoStep2 = StepResult.builder().stepIndex(-1).build();

        run.addStep(autoStep);
        run.addStep(explicitStep);
        run.addStep(autoStep2);

        assertThat(autoStep.getStepIndex()).isZero();       // auto: size=0
        assertThat(explicitStep.getStepIndex()).isEqualTo(10); // explicit: preserved
        assertThat(autoStep2.getStepIndex()).isEqualTo(2);   // auto: size=2
    }
}
