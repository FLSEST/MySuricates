package com.automation.service.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionResultTest {

    @Test
    void should_create_result_with_all_fields_when_using_canonical_constructor() {
        ToolExecutionResult result = new ToolExecutionResult("tool-1", "shell", true, "OK");

        assertThat(result.toolId()).isEqualTo("tool-1");
        assertThat(result.type()).isEqualTo("shell");
        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("OK");
    }

    @Test
    void should_create_rejected_result_when_using_rejected_factory() {
        ToolExecutionResult result = ToolExecutionResult.rejected("tool-1", "shell");

        assertThat(result.toolId()).isEqualTo("tool-1");
        assertThat(result.type()).isEqualTo("shell");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("✗ Abgelehnt");
    }

    @Test
    void should_create_expired_result_when_using_expired_factory() {
        ToolExecutionResult result = ToolExecutionResult.expired("tool-2", "create_todo");

        assertThat(result.toolId()).isEqualTo("tool-2");
        assertThat(result.type()).isEqualTo("create_todo");
        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("✗ Vorschlag abgelaufen");
    }

    @Test
    void should_be_equal_when_records_have_same_values() {
        ToolExecutionResult a = new ToolExecutionResult("tool-1", "shell", true, "OK");
        ToolExecutionResult b = new ToolExecutionResult("tool-1", "shell", true, "OK");

        assertThat(a).isEqualTo(b);
    }

    @Test
    void should_have_same_hashCode_when_records_are_equal() {
        ToolExecutionResult a = new ToolExecutionResult("tool-1", "shell", true, "OK");
        ToolExecutionResult b = new ToolExecutionResult("tool-1", "shell", true, "OK");

        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_differ_when_field_values_differ() {
        ToolExecutionResult a = new ToolExecutionResult("tool-1", "shell", true, "OK");
        ToolExecutionResult b = new ToolExecutionResult("tool-2", "shell", true, "OK");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_render_record_in_toString() {
        ToolExecutionResult result = new ToolExecutionResult("tool-1", "shell", false, "error");

        String str = result.toString();
        assertThat(str).contains("tool-1");
        assertThat(str).contains("shell");
        assertThat(str).contains("false");
        assertThat(str).contains("error");
    }
}
