package com.automation.entity;

import jakarta.persistence.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowMemoryTest {

    // --- Annotation checks ---

    @Test
    void should_beAnnotatedAsEntity_when_checkingClassAnnotations() {
        assertThat(WorkflowMemory.class.getAnnotation(Entity.class)).isNotNull();
    }

    @Test
    void should_mapToCorrectTable_when_checkingTableAnnotation() {
        Table table = WorkflowMemory.class.getAnnotation(Table.class);

        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("workflow_memory");
    }

    // --- Builder defaults ---

    @Test
    void should_defaultActiveToFalse_when_usingBuilder() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("wf-1")
                .content("some content")
                .provider("CLAUDE")
                .build();

        assertThat(memory.isActive()).isFalse();
    }

    @Test
    void should_leaveCreatedAtNull_when_usingBuilderWithoutCreatedAt() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("wf-1")
                .content("content")
                .provider("CLAUDE")
                .build();

        assertThat(memory.getCreatedAt()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeId_when_setViaSetter() {
        WorkflowMemory memory = new WorkflowMemory();

        memory.setId("mem-123");

        assertThat(memory.getId()).isEqualTo("mem-123");
    }

    @Test
    void should_storeWorkflowId_when_setViaSetter() {
        WorkflowMemory memory = new WorkflowMemory();

        memory.setWorkflowId("wf-456");

        assertThat(memory.getWorkflowId()).isEqualTo("wf-456");
    }

    @Test
    void should_storeTitle_when_setViaSetter() {
        WorkflowMemory memory = new WorkflowMemory();

        memory.setTitle("Memory Title");

        assertThat(memory.getTitle()).isEqualTo("Memory Title");
    }

    @Test
    void should_storeContent_when_setViaSetter() {
        WorkflowMemory memory = new WorkflowMemory();

        memory.setContent("Some important content");

        assertThat(memory.getContent()).isEqualTo("Some important content");
    }

    @Test
    void should_storeTags_when_setViaSetter() {
        WorkflowMemory memory = new WorkflowMemory();

        memory.setTags("tag1,tag2,tag3");

        assertThat(memory.getTags()).isEqualTo("tag1,tag2,tag3");
    }

    @Test
    void should_storeSourceRunIds_when_setViaSetter() {
        WorkflowMemory memory = new WorkflowMemory();

        memory.setSourceRunIds("run-1,run-2");

        assertThat(memory.getSourceRunIds()).isEqualTo("run-1,run-2");
    }

    @Test
    void should_storeProvider_when_setViaSetter() {
        WorkflowMemory memory = new WorkflowMemory();

        memory.setProvider("CODEX");

        assertThat(memory.getProvider()).isEqualTo("CODEX");
    }

    @Test
    void should_storeActive_when_setViaSetter() {
        WorkflowMemory memory = new WorkflowMemory();

        memory.setActive(true);

        assertThat(memory.isActive()).isTrue();
    }

    @Test
    void should_storeCreatedAt_when_setViaSetter() {
        WorkflowMemory memory = new WorkflowMemory();
        Instant ts = Instant.parse("2025-01-15T12:00:00Z");

        memory.setCreatedAt(ts);

        assertThat(memory.getCreatedAt()).isEqualTo(ts);
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant now = Instant.now();

        WorkflowMemory memory = new WorkflowMemory(
                "mem-id", "wf-1", "My Title", "Full content",
                "tag1,tag2", "run-1,run-2", "CLAUDE", true, now
        );

        assertThat(memory.getId()).isEqualTo("mem-id");
        assertThat(memory.getWorkflowId()).isEqualTo("wf-1");
        assertThat(memory.getTitle()).isEqualTo("My Title");
        assertThat(memory.getContent()).isEqualTo("Full content");
        assertThat(memory.getTags()).isEqualTo("tag1,tag2");
        assertThat(memory.getSourceRunIds()).isEqualTo("run-1,run-2");
        assertThat(memory.getProvider()).isEqualTo("CLAUDE");
        assertThat(memory.isActive()).isTrue();
        assertThat(memory.getCreatedAt()).isEqualTo(now);
    }

    // --- prePersist ---

    @Test
    void should_setCreatedAt_when_prePersistCalledAndCreatedAtIsNull() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("wf-1")
                .content("content")
                .provider("CLAUDE")
                .build();

        assertThat(memory.getCreatedAt()).isNull();

        memory.prePersist();

        assertThat(memory.getCreatedAt()).isNotNull();
        assertThat(memory.getCreatedAt()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void should_notOverrideCreatedAt_when_prePersistCalledAndCreatedAtAlreadySet() {
        Instant existingTimestamp = Instant.parse("2024-06-01T00:00:00Z");
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("wf-1")
                .content("content")
                .provider("CLAUDE")
                .createdAt(existingTimestamp)
                .build();

        memory.prePersist();

        assertThat(memory.getCreatedAt()).isEqualTo(existingTimestamp);
    }

    // --- Builder with all fields ---

    @Test
    void should_setAllFields_when_usingBuilderWithAllFields() {
        Instant now = Instant.now();

        WorkflowMemory memory = WorkflowMemory.builder()
                .id("mem-999")
                .workflowId("wf-42")
                .title("Builder Title")
                .content("Builder content")
                .tags("analysis,summary")
                .sourceRunIds("run-5,run-6")
                .provider("CODEX")
                .active(true)
                .createdAt(now)
                .build();

        assertThat(memory.getId()).isEqualTo("mem-999");
        assertThat(memory.getWorkflowId()).isEqualTo("wf-42");
        assertThat(memory.getTitle()).isEqualTo("Builder Title");
        assertThat(memory.getContent()).isEqualTo("Builder content");
        assertThat(memory.getTags()).isEqualTo("analysis,summary");
        assertThat(memory.getSourceRunIds()).isEqualTo("run-5,run-6");
        assertThat(memory.getProvider()).isEqualTo("CODEX");
        assertThat(memory.isActive()).isTrue();
        assertThat(memory.getCreatedAt()).isEqualTo(now);
    }

    // --- Edge cases ---

    @Test
    void should_allowNullTitle_when_titleIsOptional() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("wf-1")
                .content("content")
                .provider("CLAUDE")
                .build();

        assertThat(memory.getTitle()).isNull();
    }

    @Test
    void should_allowNullTags_when_tagsAreOptional() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("wf-1")
                .content("content")
                .provider("CLAUDE")
                .build();

        assertThat(memory.getTags()).isNull();
    }

    @Test
    void should_allowNullSourceRunIds_when_sourceRunIdsAreOptional() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("wf-1")
                .content("content")
                .provider("CLAUDE")
                .build();

        assertThat(memory.getSourceRunIds()).isNull();
    }

    @Test
    void should_handleEmptyContent_when_contentIsEmptyString() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("wf-1")
                .content("")
                .provider("CLAUDE")
                .build();

        assertThat(memory.getContent()).isEmpty();
    }
}
