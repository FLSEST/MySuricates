package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoTriggerTest {

    // --- Builder defaults ---

    @Test
    void should_defaultContextTemplateToEmptyMap_when_usingBuilderDefaults() {
        TodoTrigger trigger = TodoTrigger.builder()
                .tagPattern("bug")
                .targetWorkflow(new Workflow())
                .build();

        assertThat(trigger.getContextTemplate()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultEnabledToTrue_when_usingBuilderDefaults() {
        TodoTrigger trigger = TodoTrigger.builder().build();
        assertThat(trigger.isEnabled()).isTrue();
    }

    @Test
    void should_defaultMaxAttemptsToThree_when_usingBuilderDefaults() {
        TodoTrigger trigger = TodoTrigger.builder().build();
        assertThat(trigger.getMaxAttempts()).isEqualTo(3);
    }

    @Test
    void should_defaultAutoCloseToTrue_when_usingBuilderDefaults() {
        TodoTrigger trigger = TodoTrigger.builder().build();
        assertThat(trigger.isAutoClose()).isTrue();
    }

    @Test
    void should_defaultProcessBacklogToTrue_when_usingBuilderDefaults() {
        TodoTrigger trigger = TodoTrigger.builder().build();
        assertThat(trigger.isProcessBacklog()).isTrue();
    }

    @Test
    void should_defaultTriggerCountToZero_when_usingBuilderDefaults() {
        TodoTrigger trigger = TodoTrigger.builder().build();
        assertThat(trigger.getTriggerCount()).isZero();
    }

    // --- NoArgsConstructor ---

    @Test
    void should_createInstance_when_usingNoArgsConstructor() {
        TodoTrigger trigger = new TodoTrigger();
        assertThat(trigger).isNotNull();
        assertThat(trigger.getId()).isNull();
        assertThat(trigger.getTagPattern()).isNull();
    }

    // --- Setter / Getter ---

    @Test
    void should_storeId_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setId("trigger-uuid");
        assertThat(trigger.getId()).isEqualTo("trigger-uuid");
    }

    @Test
    void should_storeTagPattern_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setTagPattern("bug,security");
        assertThat(trigger.getTagPattern()).isEqualTo("bug,security");
    }

    @Test
    void should_storeProjectId_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setProjectId("project-123");
        assertThat(trigger.getProjectId()).isEqualTo("project-123");
    }

    @Test
    void should_storeSourceFilter_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setSourceFilter("AI");
        assertThat(trigger.getSourceFilter()).isEqualTo("AI");
    }

    @Test
    void should_storeMinPriority_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setMinPriority("HIGH");
        assertThat(trigger.getMinPriority()).isEqualTo("HIGH");
    }

    @Test
    void should_storeTargetWorkflow_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        Workflow workflow = new Workflow();
        trigger.setTargetWorkflow(workflow);
        assertThat(trigger.getTargetWorkflow()).isSameAs(workflow);
    }

    @Test
    void should_storeContextTemplate_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("foo", "bar");
        trigger.setContextTemplate(ctx);
        assertThat(trigger.getContextTemplate()).containsEntry("foo", "bar");
    }

    @Test
    void should_storeEnabled_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setEnabled(false);
        assertThat(trigger.isEnabled()).isFalse();
    }

    @Test
    void should_storeMaxAttempts_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setMaxAttempts(10);
        assertThat(trigger.getMaxAttempts()).isEqualTo(10);
    }

    @Test
    void should_storeAutoClose_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setAutoClose(false);
        assertThat(trigger.isAutoClose()).isFalse();
    }

    @Test
    void should_storeProcessBacklog_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setProcessBacklog(false);
        assertThat(trigger.isProcessBacklog()).isFalse();
    }

    @Test
    void should_storeDescription_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setDescription("Trigger fuer Bugs");
        assertThat(trigger.getDescription()).isEqualTo("Trigger fuer Bugs");
    }

    @Test
    void should_storeLastTriggeredAt_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        Instant now = Instant.now();
        trigger.setLastTriggeredAt(now);
        assertThat(trigger.getLastTriggeredAt()).isEqualTo(now);
    }

    @Test
    void should_storeTriggerCount_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        trigger.setTriggerCount(42L);
        assertThat(trigger.getTriggerCount()).isEqualTo(42L);
    }

    @Test
    void should_storeCreatedAt_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        Instant now = Instant.now();
        trigger.setCreatedAt(now);
        assertThat(trigger.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void should_storeUpdatedAt_when_setViaSetter() {
        TodoTrigger trigger = new TodoTrigger();
        Instant now = Instant.now();
        trigger.setUpdatedAt(now);
        assertThat(trigger.getUpdatedAt()).isEqualTo(now);
    }

    // --- Builder full chain ---

    @Test
    void should_buildFullyConfiguredTrigger_when_usingBuilder() {
        Workflow workflow = new Workflow();
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("priority", "HIGH");
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-01-02T00:00:00Z");
        Instant triggered = Instant.parse("2026-01-03T00:00:00Z");

        TodoTrigger trigger = TodoTrigger.builder()
                .id("uuid-1")
                .tagPattern("bug")
                .projectId("p1")
                .sourceFilter("AI")
                .minPriority("MEDIUM")
                .targetWorkflow(workflow)
                .contextTemplate(ctx)
                .enabled(false)
                .maxAttempts(5)
                .autoClose(false)
                .processBacklog(false)
                .description("desc")
                .lastTriggeredAt(triggered)
                .triggerCount(7L)
                .createdAt(created)
                .updatedAt(updated)
                .build();

        assertThat(trigger.getId()).isEqualTo("uuid-1");
        assertThat(trigger.getTagPattern()).isEqualTo("bug");
        assertThat(trigger.getProjectId()).isEqualTo("p1");
        assertThat(trigger.getSourceFilter()).isEqualTo("AI");
        assertThat(trigger.getMinPriority()).isEqualTo("MEDIUM");
        assertThat(trigger.getTargetWorkflow()).isSameAs(workflow);
        assertThat(trigger.getContextTemplate()).containsEntry("priority", "HIGH");
        assertThat(trigger.isEnabled()).isFalse();
        assertThat(trigger.getMaxAttempts()).isEqualTo(5);
        assertThat(trigger.isAutoClose()).isFalse();
        assertThat(trigger.isProcessBacklog()).isFalse();
        assertThat(trigger.getDescription()).isEqualTo("desc");
        assertThat(trigger.getLastTriggeredAt()).isEqualTo(triggered);
        assertThat(trigger.getTriggerCount()).isEqualTo(7L);
        assertThat(trigger.getCreatedAt()).isEqualTo(created);
        assertThat(trigger.getUpdatedAt()).isEqualTo(updated);
    }

    // --- AllArgsConstructor ---

    @Test
    void should_createInstance_when_usingAllArgsConstructor() {
        Workflow workflow = new Workflow();
        Map<String, Object> ctx = new HashMap<>();
        Instant now = Instant.now();

        TodoTrigger trigger = new TodoTrigger(
                "id-1",
                "tag",
                "proj",
                "src",
                "HIGH",
                workflow,
                ctx,
                true,
                3,
                true,
                true,
                "desc",
                now,
                1L,
                now,
                now
        );

        assertThat(trigger.getId()).isEqualTo("id-1");
        assertThat(trigger.getTagPattern()).isEqualTo("tag");
        assertThat(trigger.getProjectId()).isEqualTo("proj");
        assertThat(trigger.getSourceFilter()).isEqualTo("src");
        assertThat(trigger.getMinPriority()).isEqualTo("HIGH");
        assertThat(trigger.getTargetWorkflow()).isSameAs(workflow);
        assertThat(trigger.getContextTemplate()).isSameAs(ctx);
        assertThat(trigger.isEnabled()).isTrue();
        assertThat(trigger.getMaxAttempts()).isEqualTo(3);
        assertThat(trigger.isAutoClose()).isTrue();
        assertThat(trigger.isProcessBacklog()).isTrue();
        assertThat(trigger.getDescription()).isEqualTo("desc");
        assertThat(trigger.getLastTriggeredAt()).isEqualTo(now);
        assertThat(trigger.getTriggerCount()).isEqualTo(1L);
        assertThat(trigger.getCreatedAt()).isEqualTo(now);
        assertThat(trigger.getUpdatedAt()).isEqualTo(now);
    }

    // --- Mutability of contextTemplate ---

    @Test
    void should_allowMutatingContextTemplate_when_addingEntries() {
        TodoTrigger trigger = TodoTrigger.builder().build();
        trigger.getContextTemplate().put("k", "v");
        assertThat(trigger.getContextTemplate()).containsEntry("k", "v");
    }
}
