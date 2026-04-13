package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChainRunEntryTest {

    // --- Builder defaults ---

    @Test
    void should_defaultStatusToPending_when_usingBuilder() {
        ChainRunEntry entry = ChainRunEntry.builder().build();

        assertThat(entry.getStatus()).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void should_haveNullStringFields_when_builtWithDefaults() {
        ChainRunEntry entry = ChainRunEntry.builder().build();

        assertThat(entry.getWorkflowId()).isNull();
        assertThat(entry.getWorkflowName()).isNull();
        assertThat(entry.getProjectName()).isNull();
        assertThat(entry.getWorkflowRunId()).isNull();
    }

    @Test
    void should_haveZeroEntryIndex_when_builtWithDefaults() {
        ChainRunEntry entry = ChainRunEntry.builder().build();

        assertThat(entry.getEntryIndex()).isZero();
    }

    @Test
    void should_haveNullTimestamps_when_builtWithDefaults() {
        ChainRunEntry entry = ChainRunEntry.builder().build();

        assertThat(entry.getStartedAt()).isNull();
        assertThat(entry.getCompletedAt()).isNull();
    }

    @Test
    void should_haveNullDurationMs_when_builtWithDefaults() {
        ChainRunEntry entry = ChainRunEntry.builder().build();

        assertThat(entry.getDurationMs()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeEntryIndex_when_setViaSetter() {
        ChainRunEntry entry = new ChainRunEntry();

        entry.setEntryIndex(3);

        assertThat(entry.getEntryIndex()).isEqualTo(3);
    }

    @Test
    void should_storeWorkflowId_when_setViaSetter() {
        ChainRunEntry entry = new ChainRunEntry();

        entry.setWorkflowId("wf-123");

        assertThat(entry.getWorkflowId()).isEqualTo("wf-123");
    }

    @Test
    void should_storeWorkflowName_when_setViaSetter() {
        ChainRunEntry entry = new ChainRunEntry();

        entry.setWorkflowName("My Workflow");

        assertThat(entry.getWorkflowName()).isEqualTo("My Workflow");
    }

    @Test
    void should_storeProjectName_when_setViaSetter() {
        ChainRunEntry entry = new ChainRunEntry();

        entry.setProjectName("AutoPilot");

        assertThat(entry.getProjectName()).isEqualTo("AutoPilot");
    }

    @Test
    void should_storeWorkflowRunId_when_setViaSetter() {
        ChainRunEntry entry = new ChainRunEntry();

        entry.setWorkflowRunId("run-456");

        assertThat(entry.getWorkflowRunId()).isEqualTo("run-456");
    }

    @Test
    void should_storeStatus_when_setViaSetter() {
        ChainRunEntry entry = new ChainRunEntry();

        entry.setStatus(RunStatus.RUNNING);

        assertThat(entry.getStatus()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void should_storeStartedAt_when_setViaSetter() {
        ChainRunEntry entry = new ChainRunEntry();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        entry.setStartedAt(ts);

        assertThat(entry.getStartedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeCompletedAt_when_setViaSetter() {
        ChainRunEntry entry = new ChainRunEntry();
        Instant ts = Instant.parse("2024-06-15T10:30:00Z");

        entry.setCompletedAt(ts);

        assertThat(entry.getCompletedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeDurationMs_when_setViaSetter() {
        ChainRunEntry entry = new ChainRunEntry();

        entry.setDurationMs(5000L);

        assertThat(entry.getDurationMs()).isEqualTo(5000L);
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultEntryIndexToZero_when_usingNoArgsConstructor() {
        ChainRunEntry entry = new ChainRunEntry();

        assertThat(entry.getEntryIndex()).isZero();
    }

    @Test
    void should_defaultStatusToPending_when_usingNoArgsConstructor() {
        ChainRunEntry entry = new ChainRunEntry();

        // @Builder.Default initializes the field inline, so NoArgsConstructor also gets PENDING
        assertThat(entry.getStatus()).isEqualTo(RunStatus.PENDING);
    }

    @Test
    void should_defaultStringFieldsToNull_when_usingNoArgsConstructor() {
        ChainRunEntry entry = new ChainRunEntry();

        assertThat(entry.getWorkflowId()).isNull();
        assertThat(entry.getWorkflowName()).isNull();
        assertThat(entry.getProjectName()).isNull();
        assertThat(entry.getWorkflowRunId()).isNull();
    }

    @Test
    void should_defaultTimestampsToNull_when_usingNoArgsConstructor() {
        ChainRunEntry entry = new ChainRunEntry();

        assertThat(entry.getStartedAt()).isNull();
        assertThat(entry.getCompletedAt()).isNull();
    }

    @Test
    void should_defaultDurationMsToNull_when_usingNoArgsConstructor() {
        ChainRunEntry entry = new ChainRunEntry();

        assertThat(entry.getDurationMs()).isNull();
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant started = Instant.parse("2024-06-15T10:00:00Z");
        Instant completed = Instant.parse("2024-06-15T10:30:00Z");

        ChainRunEntry entry = new ChainRunEntry(
                2, "wf-789", "Deploy Pipeline", "AutoPilot",
                "run-abc", RunStatus.COMPLETED, started, completed, 1800000L
        );

        assertThat(entry.getEntryIndex()).isEqualTo(2);
        assertThat(entry.getWorkflowId()).isEqualTo("wf-789");
        assertThat(entry.getWorkflowName()).isEqualTo("Deploy Pipeline");
        assertThat(entry.getProjectName()).isEqualTo("AutoPilot");
        assertThat(entry.getWorkflowRunId()).isEqualTo("run-abc");
        assertThat(entry.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(entry.getStartedAt()).isEqualTo(started);
        assertThat(entry.getCompletedAt()).isEqualTo(completed);
        assertThat(entry.getDurationMs()).isEqualTo(1800000L);
    }

    // --- Builder with explicit values ---

    @Test
    void should_useExplicitValues_when_builderOverridesDefaults() {
        Instant started = Instant.parse("2024-01-01T00:00:00Z");
        Instant completed = Instant.parse("2024-01-01T01:00:00Z");

        ChainRunEntry entry = ChainRunEntry.builder()
                .entryIndex(5)
                .workflowId("wf-custom")
                .workflowName("Custom Workflow")
                .projectName("TestProject")
                .workflowRunId("run-custom")
                .status(RunStatus.FAILED)
                .startedAt(started)
                .completedAt(completed)
                .durationMs(3600000L)
                .build();

        assertThat(entry.getEntryIndex()).isEqualTo(5);
        assertThat(entry.getWorkflowId()).isEqualTo("wf-custom");
        assertThat(entry.getWorkflowName()).isEqualTo("Custom Workflow");
        assertThat(entry.getProjectName()).isEqualTo("TestProject");
        assertThat(entry.getWorkflowRunId()).isEqualTo("run-custom");
        assertThat(entry.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(entry.getStartedAt()).isEqualTo(started);
        assertThat(entry.getCompletedAt()).isEqualTo(completed);
        assertThat(entry.getDurationMs()).isEqualTo(3600000L);
    }

    @Test
    void should_overrideDefaultStatus_when_builderSetsExplicitStatus() {
        ChainRunEntry entry = ChainRunEntry.builder()
                .status(RunStatus.CANCELLED)
                .build();

        assertThat(entry.getStatus()).isEqualTo(RunStatus.CANCELLED);
    }
}
