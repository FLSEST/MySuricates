package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowVersionTest {

    // --- Builder defaults ---

    @Test
    void should_defaultChangeSourceToUser_when_usingBuilder() {
        WorkflowVersion version = WorkflowVersion.builder().build();

        assertThat(version.getChangeSource()).isEqualTo("user");
    }

    @Test
    void should_defaultCreatedAtToNonNull_when_usingBuilder() {
        Instant before = Instant.now();
        WorkflowVersion version = WorkflowVersion.builder().build();
        Instant after = Instant.now();

        assertThat(version.getCreatedAt()).isNotNull();
        assertThat(version.getCreatedAt()).isBetween(before, after);
    }

    @Test
    void should_haveNullId_when_builtWithDefaults() {
        WorkflowVersion version = WorkflowVersion.builder().build();

        assertThat(version.getId()).isNull();
    }

    @Test
    void should_haveNullWorkflowId_when_builtWithDefaults() {
        WorkflowVersion version = WorkflowVersion.builder().build();

        assertThat(version.getWorkflowId()).isNull();
    }

    @Test
    void should_haveZeroVersionNumber_when_builtWithDefaults() {
        WorkflowVersion version = WorkflowVersion.builder().build();

        assertThat(version.getVersionNumber()).isZero();
    }

    @Test
    void should_haveNullSnapshot_when_builtWithDefaults() {
        WorkflowVersion version = WorkflowVersion.builder().build();

        assertThat(version.getSnapshot()).isNull();
    }

    @Test
    void should_haveNullChangeMessage_when_builtWithDefaults() {
        WorkflowVersion version = WorkflowVersion.builder().build();

        assertThat(version.getChangeMessage()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeWorkflowId_when_setViaSetter() {
        WorkflowVersion version = new WorkflowVersion();

        version.setWorkflowId("wf-123");

        assertThat(version.getWorkflowId()).isEqualTo("wf-123");
    }

    @Test
    void should_storeVersionNumber_when_setViaSetter() {
        WorkflowVersion version = new WorkflowVersion();

        version.setVersionNumber(5);

        assertThat(version.getVersionNumber()).isEqualTo(5);
    }

    @Test
    void should_storeSnapshot_when_setViaSetter() {
        WorkflowVersion version = new WorkflowVersion();
        String json = "{\"name\":\"test\",\"blocks\":[]}";

        version.setSnapshot(json);

        assertThat(version.getSnapshot()).isEqualTo(json);
    }

    @Test
    void should_storeChangeSource_when_setViaSetter() {
        WorkflowVersion version = new WorkflowVersion();

        version.setChangeSource("auto-save");

        assertThat(version.getChangeSource()).isEqualTo("auto-save");
    }

    @Test
    void should_storeChangeMessage_when_setViaSetter() {
        WorkflowVersion version = new WorkflowVersion();

        version.setChangeMessage("Added retry block");

        assertThat(version.getChangeMessage()).isEqualTo("Added retry block");
    }

    @Test
    void should_storeCreatedAt_when_setViaSetter() {
        WorkflowVersion version = new WorkflowVersion();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        version.setCreatedAt(ts);

        assertThat(version.getCreatedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeId_when_setViaSetter() {
        WorkflowVersion version = new WorkflowVersion();

        version.setId("version-42");

        assertThat(version.getId()).isEqualTo("version-42");
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultIdToNull_when_usingNoArgsConstructor() {
        WorkflowVersion version = new WorkflowVersion();

        assertThat(version.getId()).isNull();
    }

    @Test
    void should_defaultWorkflowIdToNull_when_usingNoArgsConstructor() {
        WorkflowVersion version = new WorkflowVersion();

        assertThat(version.getWorkflowId()).isNull();
    }

    @Test
    void should_defaultSnapshotToNull_when_usingNoArgsConstructor() {
        WorkflowVersion version = new WorkflowVersion();

        assertThat(version.getSnapshot()).isNull();
    }

    @Test
    void should_defaultChangeSourceToUser_when_usingNoArgsConstructor() {
        WorkflowVersion version = new WorkflowVersion();

        assertThat(version.getChangeSource()).isEqualTo("user");
    }

    @Test
    void should_defaultChangeMessageToNull_when_usingNoArgsConstructor() {
        WorkflowVersion version = new WorkflowVersion();

        assertThat(version.getChangeMessage()).isNull();
    }

    @Test
    void should_defaultCreatedAtToNonNull_when_usingNoArgsConstructor() {
        Instant before = Instant.now();
        WorkflowVersion version = new WorkflowVersion();
        Instant after = Instant.now();

        assertThat(version.getCreatedAt()).isBetween(before, after);
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant created = Instant.parse("2024-06-15T10:00:00Z");

        WorkflowVersion version = new WorkflowVersion(
                "ver-1", "wf-100", 3,
                "{\"blocks\":[]}", "api",
                "Initial version", null, created
        );

        assertThat(version.getId()).isEqualTo("ver-1");
        assertThat(version.getWorkflowId()).isEqualTo("wf-100");
        assertThat(version.getVersionNumber()).isEqualTo(3);
        assertThat(version.getSnapshot()).isEqualTo("{\"blocks\":[]}");
        assertThat(version.getChangeSource()).isEqualTo("api");
        assertThat(version.getChangeMessage()).isEqualTo("Initial version");
        assertThat(version.getCreatedAt()).isEqualTo(created);
    }

    // --- Builder with custom values ---

    @Test
    void should_overrideChangeSource_when_setViaBuilder() {
        WorkflowVersion version = WorkflowVersion.builder()
                .changeSource("auto-save")
                .build();

        assertThat(version.getChangeSource()).isEqualTo("auto-save");
    }

    @Test
    void should_overrideCreatedAt_when_setViaBuilder() {
        Instant custom = Instant.parse("2023-01-01T00:00:00Z");

        WorkflowVersion version = WorkflowVersion.builder()
                .createdAt(custom)
                .build();

        assertThat(version.getCreatedAt()).isEqualTo(custom);
    }

    @Test
    void should_buildCompleteVersion_when_allFieldsProvided() {
        Instant created = Instant.parse("2024-12-01T08:30:00Z");

        WorkflowVersion version = WorkflowVersion.builder()
                .id("ver-42")
                .workflowId("wf-007")
                .versionNumber(7)
                .snapshot("{\"name\":\"My Workflow\",\"blocks\":[{\"type\":\"shell\"}]}")
                .changeSource("schedule")
                .changeMessage("Scheduled backup")
                .createdAt(created)
                .build();

        assertThat(version.getId()).isEqualTo("ver-42");
        assertThat(version.getWorkflowId()).isEqualTo("wf-007");
        assertThat(version.getVersionNumber()).isEqualTo(7);
        assertThat(version.getSnapshot()).contains("My Workflow");
        assertThat(version.getChangeSource()).isEqualTo("schedule");
        assertThat(version.getChangeMessage()).isEqualTo("Scheduled backup");
        assertThat(version.getCreatedAt()).isEqualTo(created);
    }

    // --- @PrePersist ensureId ---

    @Test
    void should_generateId_when_ensureIdCalledWithNullId() throws Exception {
        WorkflowVersion version = WorkflowVersion.builder()
                .workflowId("wf-1")
                .versionNumber(1)
                .snapshot("{}")
                .build();
        assertThat(version.getId()).isNull();

        invokeEnsureId(version);

        assertThat(version.getId()).isNotNull().isNotBlank();
        assertThat(version.getId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void should_generateId_when_ensureIdCalledWithBlankId() throws Exception {
        WorkflowVersion version = WorkflowVersion.builder()
                .id("   ")
                .workflowId("wf-1")
                .versionNumber(1)
                .snapshot("{}")
                .build();

        invokeEnsureId(version);

        assertThat(version.getId()).isNotBlank();
        assertThat(version.getId()).doesNotContain("   ");
    }

    @Test
    void should_keepExistingId_when_ensureIdCalledWithNonBlankId() throws Exception {
        WorkflowVersion version = WorkflowVersion.builder()
                .id("my-custom-id")
                .workflowId("wf-1")
                .versionNumber(1)
                .snapshot("{}")
                .build();

        invokeEnsureId(version);

        assertThat(version.getId()).isEqualTo("my-custom-id");
    }

    @Test
    void should_generateUniqueIds_when_ensureIdCalledMultipleTimes() throws Exception {
        WorkflowVersion v1 = WorkflowVersion.builder().build();
        WorkflowVersion v2 = WorkflowVersion.builder().build();

        invokeEnsureId(v1);
        invokeEnsureId(v2);

        assertThat(v1.getId()).isNotEqualTo(v2.getId());
    }

    // --- Edge cases ---

    @Test
    void should_allowNullChangeMessage_when_usingBuilder() {
        WorkflowVersion version = WorkflowVersion.builder()
                .workflowId("wf-1")
                .versionNumber(1)
                .snapshot("{}")
                .changeMessage(null)
                .build();

        assertThat(version.getChangeMessage()).isNull();
    }

    @Test
    void should_storeLargeSnapshot_when_setViaSetter() {
        WorkflowVersion version = new WorkflowVersion();
        String largeJson = "{\"blocks\":" + "[{\"type\":\"shell\"}]".repeat(100) + "}";

        version.setSnapshot(largeJson);

        assertThat(version.getSnapshot()).isEqualTo(largeJson);
    }

    @Test
    void should_storeEmptySnapshot_when_setViaSetter() {
        WorkflowVersion version = new WorkflowVersion();

        version.setSnapshot("");

        assertThat(version.getSnapshot()).isEmpty();
    }

    // --- Helper ---

    private void invokeEnsureId(WorkflowVersion version) throws Exception {
        Method ensureId = WorkflowVersion.class.getDeclaredMethod("ensureId");
        ensureId.setAccessible(true);
        ensureId.invoke(version);
    }
}
