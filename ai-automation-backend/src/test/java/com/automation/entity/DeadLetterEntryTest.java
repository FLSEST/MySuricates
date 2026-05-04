package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterEntryTest {

    // --- Builder defaults ---

    @Test
    void should_defaultRetryCountToZero_when_usingBuilderDefaults() {
        DeadLetterEntry entry = DeadLetterEntry.builder()
                .workflowRunId("run-1")
                .lastFailureAt(Instant.now())
                .build();

        assertThat(entry.getRetryCount()).isZero();
    }

    @Test
    void should_defaultStatusToOpen_when_usingBuilderDefaults() {
        DeadLetterEntry entry = DeadLetterEntry.builder()
                .workflowRunId("run-1")
                .lastFailureAt(Instant.now())
                .build();

        assertThat(entry.getStatus()).isEqualTo(DeadLetterEntry.Status.OPEN);
    }

    @Test
    void should_setAllBuilderFields_when_buildingEntry() {
        Instant failedAt = Instant.parse("2026-01-15T10:00:00Z");

        DeadLetterEntry entry = DeadLetterEntry.builder()
                .id("dlq-1")
                .workflowRunId("run-42")
                .workflowId("wf-7")
                .workflowName("My Workflow")
                .errorMessage("Block timeout")
                .failedStepIndex(3)
                .retryCount(2)
                .lastFailureAt(failedAt)
                .status(DeadLetterEntry.Status.RETRIED)
                .build();

        assertThat(entry.getId()).isEqualTo("dlq-1");
        assertThat(entry.getWorkflowRunId()).isEqualTo("run-42");
        assertThat(entry.getWorkflowId()).isEqualTo("wf-7");
        assertThat(entry.getWorkflowName()).isEqualTo("My Workflow");
        assertThat(entry.getErrorMessage()).isEqualTo("Block timeout");
        assertThat(entry.getFailedStepIndex()).isEqualTo(3);
        assertThat(entry.getRetryCount()).isEqualTo(2);
        assertThat(entry.getLastFailureAt()).isEqualTo(failedAt);
        assertThat(entry.getStatus()).isEqualTo(DeadLetterEntry.Status.RETRIED);
    }

    // --- NoArgsConstructor ---

    @Test
    void should_createEmptyEntry_when_usingNoArgsConstructor() {
        DeadLetterEntry entry = new DeadLetterEntry();

        assertThat(entry.getId()).isNull();
        assertThat(entry.getWorkflowRunId()).isNull();
        assertThat(entry.getErrorMessage()).isNull();
        assertThat(entry.getStatus()).isEqualTo(DeadLetterEntry.Status.OPEN);
        assertThat(entry.getCreatedAt()).isNull();
        assertThat(entry.getUpdatedAt()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeWorkflowRunId_when_setViaSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();

        entry.setWorkflowRunId("run-xyz");

        assertThat(entry.getWorkflowRunId()).isEqualTo("run-xyz");
    }

    @Test
    void should_storeWorkflowId_when_setViaSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();

        entry.setWorkflowId("wf-99");

        assertThat(entry.getWorkflowId()).isEqualTo("wf-99");
    }

    @Test
    void should_storeWorkflowName_when_setViaSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();

        entry.setWorkflowName("Daily Report");

        assertThat(entry.getWorkflowName()).isEqualTo("Daily Report");
    }

    @Test
    void should_storeErrorMessage_when_setViaSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();

        entry.setErrorMessage("Connection refused");

        assertThat(entry.getErrorMessage()).isEqualTo("Connection refused");
    }

    @Test
    void should_storeFailedStepIndex_when_setViaSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();

        entry.setFailedStepIndex(7);

        assertThat(entry.getFailedStepIndex()).isEqualTo(7);
    }

    @Test
    void should_storeRetryCount_when_setViaSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();

        entry.setRetryCount(5);

        assertThat(entry.getRetryCount()).isEqualTo(5);
    }

    @Test
    void should_storeLastFailureAt_when_setViaSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();
        Instant ts = Instant.parse("2026-03-01T08:30:00Z");

        entry.setLastFailureAt(ts);

        assertThat(entry.getLastFailureAt()).isEqualTo(ts);
    }

    @Test
    void should_storeStatus_when_setViaSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();

        entry.setStatus(DeadLetterEntry.Status.DISCARDED);

        assertThat(entry.getStatus()).isEqualTo(DeadLetterEntry.Status.DISCARDED);
    }

    @Test
    void should_storeId_when_setViaSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();

        entry.setId("dlq-abc");

        assertThat(entry.getId()).isEqualTo("dlq-abc");
    }

    // --- Audit fields ---

    @Test
    void should_defaultCreatedAtToNull_when_newInstance() {
        DeadLetterEntry entry = new DeadLetterEntry();

        assertThat(entry.getCreatedAt()).isNull();
    }

    @Test
    void should_defaultUpdatedAtToNull_when_newInstance() {
        DeadLetterEntry entry = new DeadLetterEntry();

        assertThat(entry.getUpdatedAt()).isNull();
    }

    @Test
    void should_allowSettingCreatedAt_when_usingSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();
        Instant created = Instant.parse("2026-01-01T00:00:00Z");

        entry.setCreatedAt(created);

        assertThat(entry.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void should_allowSettingUpdatedAt_when_usingSetter() {
        DeadLetterEntry entry = new DeadLetterEntry();
        Instant updated = Instant.parse("2026-02-01T12:00:00Z");

        entry.setUpdatedAt(updated);

        assertThat(entry.getUpdatedAt()).isEqualTo(updated);
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant failedAt = Instant.parse("2026-04-01T10:00:00Z");
        Instant created = Instant.parse("2026-04-01T10:00:01Z");
        Instant updated = Instant.parse("2026-04-01T10:00:02Z");

        DeadLetterEntry entry = new DeadLetterEntry(
                "dlq-id",
                "run-id",
                "wf-id",
                "wf-name",
                "boom",
                4,
                3,
                failedAt,
                DeadLetterEntry.Status.RETRIED,
                created,
                updated
        );

        assertThat(entry.getId()).isEqualTo("dlq-id");
        assertThat(entry.getWorkflowRunId()).isEqualTo("run-id");
        assertThat(entry.getWorkflowId()).isEqualTo("wf-id");
        assertThat(entry.getWorkflowName()).isEqualTo("wf-name");
        assertThat(entry.getErrorMessage()).isEqualTo("boom");
        assertThat(entry.getFailedStepIndex()).isEqualTo(4);
        assertThat(entry.getRetryCount()).isEqualTo(3);
        assertThat(entry.getLastFailureAt()).isEqualTo(failedAt);
        assertThat(entry.getStatus()).isEqualTo(DeadLetterEntry.Status.RETRIED);
        assertThat(entry.getCreatedAt()).isEqualTo(created);
        assertThat(entry.getUpdatedAt()).isEqualTo(updated);
    }

    // --- Status enum ---

    @Test
    void should_haveThreeStatusValues_when_inspectingEnum() {
        assertThat(DeadLetterEntry.Status.values())
                .containsExactly(
                        DeadLetterEntry.Status.OPEN,
                        DeadLetterEntry.Status.RETRIED,
                        DeadLetterEntry.Status.DISCARDED
                );
    }

    @Test
    void should_resolveStatusByName_when_usingValueOf() {
        assertThat(DeadLetterEntry.Status.valueOf("OPEN")).isEqualTo(DeadLetterEntry.Status.OPEN);
        assertThat(DeadLetterEntry.Status.valueOf("RETRIED")).isEqualTo(DeadLetterEntry.Status.RETRIED);
        assertThat(DeadLetterEntry.Status.valueOf("DISCARDED")).isEqualTo(DeadLetterEntry.Status.DISCARDED);
    }

    // --- Status transitions ---

    @Test
    void should_transitionFromOpenToRetried_when_statusUpdated() {
        DeadLetterEntry entry = DeadLetterEntry.builder()
                .workflowRunId("run-1")
                .lastFailureAt(Instant.now())
                .build();

        assertThat(entry.getStatus()).isEqualTo(DeadLetterEntry.Status.OPEN);

        entry.setStatus(DeadLetterEntry.Status.RETRIED);

        assertThat(entry.getStatus()).isEqualTo(DeadLetterEntry.Status.RETRIED);
    }

    @Test
    void should_transitionFromOpenToDiscarded_when_statusUpdated() {
        DeadLetterEntry entry = DeadLetterEntry.builder()
                .workflowRunId("run-1")
                .lastFailureAt(Instant.now())
                .build();

        entry.setStatus(DeadLetterEntry.Status.DISCARDED);

        assertThat(entry.getStatus()).isEqualTo(DeadLetterEntry.Status.DISCARDED);
    }

    @Test
    void should_incrementRetryCount_when_setViaSetter() {
        DeadLetterEntry entry = DeadLetterEntry.builder()
                .workflowRunId("run-1")
                .lastFailureAt(Instant.now())
                .build();

        assertThat(entry.getRetryCount()).isZero();

        entry.setRetryCount(entry.getRetryCount() + 1);
        assertThat(entry.getRetryCount()).isEqualTo(1);

        entry.setRetryCount(entry.getRetryCount() + 1);
        assertThat(entry.getRetryCount()).isEqualTo(2);
    }
}
