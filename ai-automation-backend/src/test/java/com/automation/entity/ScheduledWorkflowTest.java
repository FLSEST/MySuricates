package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledWorkflowTest {

    // --- getTargetName tests ---

    @Test
    void should_returnChainName_when_targetTypeIsChainAndChainIsSet() {
        WorkflowChain chain = WorkflowChain.builder().name("My Chain").build();
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .targetType(ScheduledWorkflow.TargetType.CHAIN)
                .chain(chain)
                .build();

        assertThat(scheduled.getTargetName()).isEqualTo("My Chain");
    }

    @Test
    void should_returnWorkflowName_when_targetTypeIsWorkflowAndWorkflowIsSet() {
        Workflow workflow = Workflow.builder().name("My Workflow").build();
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .workflow(workflow)
                .build();

        assertThat(scheduled.getTargetName()).isEqualTo("My Workflow");
    }

    @Test
    void should_returnUnknown_when_targetTypeIsWorkflowAndWorkflowIsNull() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                .build();

        assertThat(scheduled.getTargetName()).isEqualTo("Unknown");
    }

    @Test
    void should_returnWorkflowName_when_targetTypeIsChainButChainIsNull() {
        Workflow workflow = Workflow.builder().name("Fallback Workflow").build();
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .targetType(ScheduledWorkflow.TargetType.CHAIN)
                .chain(null)
                .workflow(workflow)
                .build();

        assertThat(scheduled.getTargetName()).isEqualTo("Fallback Workflow");
    }

    @Test
    void should_returnUnknown_when_bothChainAndWorkflowAreNull() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .targetType(ScheduledWorkflow.TargetType.CHAIN)
                .build();

        assertThat(scheduled.getTargetName()).isEqualTo("Unknown");
    }

    // --- Builder defaults tests ---

    @Test
    void should_haveWorkflowAsDefaultTargetType() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder().build();

        assertThat(scheduled.getTargetType()).isEqualTo(ScheduledWorkflow.TargetType.WORKFLOW);
    }

    @Test
    void should_haveCronAsDefaultScheduleType() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder().build();

        assertThat(scheduled.getScheduleType()).isEqualTo(ScheduledWorkflow.ScheduleType.CRON);
    }

    @Test
    void should_haveSkipAsDefaultConflictBehavior() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder().build();

        assertThat(scheduled.getConflictBehavior()).isEqualTo(ScheduledWorkflow.ConflictBehavior.SKIP);
    }

    @Test
    void should_beEnabledByDefault() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder().build();

        assertThat(scheduled.isEnabled()).isTrue();
    }

    @Test
    void should_haveZeroMaxRunsByDefault() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder().build();

        assertThat(scheduled.getMaxRuns()).isZero();
    }

    @Test
    void should_haveZeroRunCountByDefault() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder().build();

        assertThat(scheduled.getRunCount()).isZero();
    }

    @Test
    void should_haveEmptyInputContextByDefault() {
        ScheduledWorkflow scheduled = ScheduledWorkflow.builder().build();

        assertThat(scheduled.getInputContext()).isNotNull().isEmpty();
        assertThat(scheduled.getInputContext()).isInstanceOf(HashMap.class);
    }

    // --- Enum values tests ---

    @Test
    void should_haveAllScheduleTypeValues() {
        assertThat(ScheduledWorkflow.ScheduleType.values())
                .containsExactly(
                        ScheduledWorkflow.ScheduleType.CRON,
                        ScheduledWorkflow.ScheduleType.ONCE,
                        ScheduledWorkflow.ScheduleType.DELAYED
                );
    }

    @Test
    void should_haveAllTargetTypeValues() {
        assertThat(ScheduledWorkflow.TargetType.values())
                .containsExactly(
                        ScheduledWorkflow.TargetType.WORKFLOW,
                        ScheduledWorkflow.TargetType.CHAIN
                );
    }

    @Test
    void should_haveAllConflictBehaviorValues() {
        assertThat(ScheduledWorkflow.ConflictBehavior.values())
                .containsExactly(
                        ScheduledWorkflow.ConflictBehavior.SKIP,
                        ScheduledWorkflow.ConflictBehavior.OVERLAP
                );
    }
}
