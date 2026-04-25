package com.automation.service.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowContentChangedEventTest {

    @Test
    void should_expose_workflow_id_via_accessor() {
        WorkflowContentChangedEvent event = new WorkflowContentChangedEvent("wf-123");

        assertThat(event.workflowId()).isEqualTo("wf-123");
    }

    @Test
    void should_allow_null_workflow_id() {
        WorkflowContentChangedEvent event = new WorkflowContentChangedEvent(null);

        assertThat(event.workflowId()).isNull();
    }

    @Test
    void should_implement_value_equality_for_records() {
        WorkflowContentChangedEvent a = new WorkflowContentChangedEvent("wf-42");
        WorkflowContentChangedEvent b = new WorkflowContentChangedEvent("wf-42");
        WorkflowContentChangedEvent c = new WorkflowContentChangedEvent("wf-99");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void should_include_workflow_id_in_to_string() {
        WorkflowContentChangedEvent event = new WorkflowContentChangedEvent("wf-abc");

        assertThat(event.toString()).contains("wf-abc");
    }
}
