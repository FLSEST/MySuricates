package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChainEntryTest {

    @Test
    void should_create_empty_entry_with_no_args_constructor() {
        ChainEntry entry = new ChainEntry();

        assertThat(entry.getWorkflowId()).isNull();
        assertThat(entry.getLabel()).isNull();
        assertThat(entry.getSortOrder()).isZero();
        assertThat(entry.isStopOnFailure()).isTrue();
        assertThat(entry.getInputContext()).isNotNull().isEmpty();
    }

    @Test
    void should_set_and_get_workflowId() {
        ChainEntry entry = new ChainEntry();

        entry.setWorkflowId("wf-123");

        assertThat(entry.getWorkflowId()).isEqualTo("wf-123");
    }

    @Test
    void should_set_and_get_label() {
        ChainEntry entry = new ChainEntry();

        entry.setLabel("First step");

        assertThat(entry.getLabel()).isEqualTo("First step");
    }

    @Test
    void should_set_and_get_sortOrder() {
        ChainEntry entry = new ChainEntry();

        entry.setSortOrder(42);

        assertThat(entry.getSortOrder()).isEqualTo(42);
    }

    @Test
    void should_set_and_get_stopOnFailure() {
        ChainEntry entry = new ChainEntry();

        entry.setStopOnFailure(true);

        assertThat(entry.isStopOnFailure()).isTrue();
    }

    @Test
    void should_set_and_get_inputContext() {
        ChainEntry entry = new ChainEntry();
        Map<String, Object> context = new HashMap<>();
        context.put("key", "value");

        entry.setInputContext(context);

        assertThat(entry.getInputContext())
                .hasSize(1)
                .containsEntry("key", "value");
    }

    @Test
    void should_create_entry_with_all_args_constructor() {
        Map<String, Object> context = new HashMap<>();
        context.put("env", "prod");

        ChainEntry entry = new ChainEntry("wf-abc", "Step A", 1, false, context);

        assertThat(entry.getWorkflowId()).isEqualTo("wf-abc");
        assertThat(entry.getLabel()).isEqualTo("Step A");
        assertThat(entry.getSortOrder()).isEqualTo(1);
        assertThat(entry.isStopOnFailure()).isFalse();
        assertThat(entry.getInputContext()).containsEntry("env", "prod");
    }

    @Test
    void should_apply_builder_defaults_when_only_required_fields_set() {
        ChainEntry entry = ChainEntry.builder()
                .workflowId("wf-1")
                .label("Default step")
                .sortOrder(0)
                .build();

        assertThat(entry.isStopOnFailure()).isTrue();
        assertThat(entry.getInputContext())
                .isNotNull()
                .isEmpty();
    }

    @Test
    void should_override_builder_defaults_when_explicit_values_provided() {
        Map<String, Object> context = new HashMap<>();
        context.put("foo", "bar");

        ChainEntry entry = ChainEntry.builder()
                .workflowId("wf-2")
                .label("Custom step")
                .sortOrder(5)
                .stopOnFailure(false)
                .inputContext(context)
                .build();

        assertThat(entry.getWorkflowId()).isEqualTo("wf-2");
        assertThat(entry.getLabel()).isEqualTo("Custom step");
        assertThat(entry.getSortOrder()).isEqualTo(5);
        assertThat(entry.isStopOnFailure()).isFalse();
        assertThat(entry.getInputContext()).containsEntry("foo", "bar");
    }

    @Test
    void should_allow_mutation_of_default_inputContext() {
        ChainEntry entry = ChainEntry.builder()
                .workflowId("wf-3")
                .label("Mutable")
                .sortOrder(0)
                .build();

        entry.getInputContext().put("added", 123);

        assertThat(entry.getInputContext()).containsEntry("added", 123);
    }
}
