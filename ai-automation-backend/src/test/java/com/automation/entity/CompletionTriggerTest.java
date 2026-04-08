package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompletionTriggerTest {

    // --- Builder defaults ---

    @Test
    void should_defaultEnabledToTrue_when_usingBuilder() {
        CompletionTrigger trigger = CompletionTrigger.builder().build();

        assertThat(trigger.isEnabled()).isTrue();
    }

    @Test
    void should_defaultTriggerOnSuccessToTrue_when_usingBuilder() {
        CompletionTrigger trigger = CompletionTrigger.builder().build();

        assertThat(trigger.isTriggerOnSuccess()).isTrue();
    }

    @Test
    void should_defaultTriggerOnFailureToFalse_when_usingBuilder() {
        CompletionTrigger trigger = CompletionTrigger.builder().build();

        assertThat(trigger.isTriggerOnFailure()).isFalse();
    }

    @Test
    void should_defaultTriggerAlwaysToFalse_when_usingBuilder() {
        CompletionTrigger trigger = CompletionTrigger.builder().build();

        assertThat(trigger.isTriggerAlways()).isFalse();
    }

    @Test
    void should_defaultContextVariablesToEmptyMap_when_usingBuilder() {
        CompletionTrigger trigger = CompletionTrigger.builder().build();

        assertThat(trigger.getContextVariables()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultTriggerCountToZero_when_usingBuilder() {
        CompletionTrigger trigger = CompletionTrigger.builder().build();

        assertThat(trigger.getTriggerCount()).isEqualTo(0L);
    }

    // --- Setter / field access ---

    @Test
    void should_storeId_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();

        trigger.setId("trigger-123");

        assertThat(trigger.getId()).isEqualTo("trigger-123");
    }

    @Test
    void should_storeSourceWorkflow_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();
        Workflow source = new Workflow();
        source.setId("wf-source");

        trigger.setSourceWorkflow(source);

        assertThat(trigger.getSourceWorkflow()).isSameAs(source);
    }

    @Test
    void should_storeTargetWorkflow_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();
        Workflow target = new Workflow();
        target.setId("wf-target");

        trigger.setTargetWorkflow(target);

        assertThat(trigger.getTargetWorkflow()).isSameAs(target);
    }

    @Test
    void should_storeEnabled_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();

        trigger.setEnabled(false);

        assertThat(trigger.isEnabled()).isFalse();
    }

    @Test
    void should_storeTriggerOnSuccess_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();

        trigger.setTriggerOnSuccess(false);

        assertThat(trigger.isTriggerOnSuccess()).isFalse();
    }

    @Test
    void should_storeTriggerOnFailure_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();

        trigger.setTriggerOnFailure(true);

        assertThat(trigger.isTriggerOnFailure()).isTrue();
    }

    @Test
    void should_storeTriggerAlways_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();

        trigger.setTriggerAlways(true);

        assertThat(trigger.isTriggerAlways()).isTrue();
    }

    @Test
    void should_storeDescription_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();

        trigger.setDescription("Trigger target after source completes");

        assertThat(trigger.getDescription()).isEqualTo("Trigger target after source completes");
    }

    @Test
    void should_storeContextVariables_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();
        Map<String, Object> vars = new HashMap<>(Map.of("key", "value"));

        trigger.setContextVariables(vars);

        assertThat(trigger.getContextVariables()).containsEntry("key", "value");
    }

    @Test
    void should_storeLastTriggeredAt_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();
        Instant ts = Instant.parse("2025-06-15T10:00:00Z");

        trigger.setLastTriggeredAt(ts);

        assertThat(trigger.getLastTriggeredAt()).isEqualTo(ts);
    }

    @Test
    void should_storeTriggerCount_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();

        trigger.setTriggerCount(42L);

        assertThat(trigger.getTriggerCount()).isEqualTo(42L);
    }

    // --- Audit fields ---

    @Test
    void should_defaultCreatedAtToNull_when_newInstance() {
        CompletionTrigger trigger = new CompletionTrigger();

        assertThat(trigger.getCreatedAt()).isNull();
    }

    @Test
    void should_defaultUpdatedAtToNull_when_newInstance() {
        CompletionTrigger trigger = new CompletionTrigger();

        assertThat(trigger.getUpdatedAt()).isNull();
    }

    @Test
    void should_storeCreatedAt_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");

        trigger.setCreatedAt(created);

        assertThat(trigger.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void should_storeUpdatedAt_when_setViaSetter() {
        CompletionTrigger trigger = new CompletionTrigger();
        Instant updated = Instant.parse("2024-06-01T12:00:00Z");

        trigger.setUpdatedAt(updated);

        assertThat(trigger.getUpdatedAt()).isEqualTo(updated);
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultIdToNull_when_usingNoArgsConstructor() {
        CompletionTrigger trigger = new CompletionTrigger();

        assertThat(trigger.getId()).isNull();
    }

    @Test
    void should_defaultDescriptionToNull_when_usingNoArgsConstructor() {
        CompletionTrigger trigger = new CompletionTrigger();

        assertThat(trigger.getDescription()).isNull();
    }

    @Test
    void should_defaultLastTriggeredAtToNull_when_usingNoArgsConstructor() {
        CompletionTrigger trigger = new CompletionTrigger();

        assertThat(trigger.getLastTriggeredAt()).isNull();
    }

    @Test
    void should_defaultSourceWorkflowToNull_when_usingNoArgsConstructor() {
        CompletionTrigger trigger = new CompletionTrigger();

        assertThat(trigger.getSourceWorkflow()).isNull();
    }

    @Test
    void should_defaultTargetWorkflowToNull_when_usingNoArgsConstructor() {
        CompletionTrigger trigger = new CompletionTrigger();

        assertThat(trigger.getTargetWorkflow()).isNull();
    }

    // --- Builder with explicit values ---

    @Test
    void should_setAllFields_when_usingBuilderWithExplicitValues() {
        Workflow source = new Workflow();
        source.setId("wf-source");
        Workflow target = new Workflow();
        target.setId("wf-target");
        Map<String, Object> vars = new HashMap<>(Map.of("env", "prod"));
        Instant now = Instant.now();

        CompletionTrigger trigger = CompletionTrigger.builder()
                .id("ct-1")
                .sourceWorkflow(source)
                .targetWorkflow(target)
                .enabled(false)
                .triggerOnSuccess(false)
                .triggerOnFailure(true)
                .triggerAlways(true)
                .description("Full build trigger")
                .contextVariables(vars)
                .lastTriggeredAt(now)
                .triggerCount(5L)
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertThat(trigger.getId()).isEqualTo("ct-1");
        assertThat(trigger.getSourceWorkflow()).isSameAs(source);
        assertThat(trigger.getTargetWorkflow()).isSameAs(target);
        assertThat(trigger.isEnabled()).isFalse();
        assertThat(trigger.isTriggerOnSuccess()).isFalse();
        assertThat(trigger.isTriggerOnFailure()).isTrue();
        assertThat(trigger.isTriggerAlways()).isTrue();
        assertThat(trigger.getDescription()).isEqualTo("Full build trigger");
        assertThat(trigger.getContextVariables()).containsEntry("env", "prod");
        assertThat(trigger.getLastTriggeredAt()).isEqualTo(now);
        assertThat(trigger.getTriggerCount()).isEqualTo(5L);
        assertThat(trigger.getCreatedAt()).isEqualTo(now);
        assertThat(trigger.getUpdatedAt()).isEqualTo(now);
    }

    // --- Context variables mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultContextVariables() {
        CompletionTrigger trigger = CompletionTrigger.builder().build();

        trigger.getContextVariables().put("key", "value");

        assertThat(trigger.getContextVariables()).hasSize(1).containsEntry("key", "value");
    }

    @Test
    void should_allowRemovingEntries_when_contextVariablesHasEntries() {
        Map<String, Object> vars = new HashMap<>(Map.of("a", "1", "b", "2"));
        CompletionTrigger trigger = CompletionTrigger.builder()
                .contextVariables(vars)
                .build();

        trigger.getContextVariables().remove("a");

        assertThat(trigger.getContextVariables()).hasSize(1).containsEntry("b", "2");
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Workflow source = new Workflow();
        Workflow target = new Workflow();
        Instant now = Instant.now();
        Map<String, Object> vars = new HashMap<>(Map.of("x", "y"));

        CompletionTrigger trigger = new CompletionTrigger(
                "ct-all", source, target,
                true, true, false, false,
                "All args desc", vars, now, 10L,
                now, now
        );

        assertThat(trigger.getId()).isEqualTo("ct-all");
        assertThat(trigger.getSourceWorkflow()).isSameAs(source);
        assertThat(trigger.getTargetWorkflow()).isSameAs(target);
        assertThat(trigger.isEnabled()).isTrue();
        assertThat(trigger.isTriggerOnSuccess()).isTrue();
        assertThat(trigger.isTriggerOnFailure()).isFalse();
        assertThat(trigger.isTriggerAlways()).isFalse();
        assertThat(trigger.getDescription()).isEqualTo("All args desc");
        assertThat(trigger.getContextVariables()).containsEntry("x", "y");
        assertThat(trigger.getLastTriggeredAt()).isEqualTo(now);
        assertThat(trigger.getTriggerCount()).isEqualTo(10L);
        assertThat(trigger.getCreatedAt()).isEqualTo(now);
        assertThat(trigger.getUpdatedAt()).isEqualTo(now);
    }
}
