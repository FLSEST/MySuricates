package com.automation.executor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContextTest {

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.builder()
                .containerId("container-1")
                .cliType("claude")
                .workflowRunId("run-1")
                .build();
    }

    @Test
    void resolveTemplate_previousOutput_withSpaces() {
        context.setPreviousOutput("some output");

        String result = context.resolveTemplate("Use: {{ previous_output }}");

        assertThat(result).isEqualTo("Use: some output");
    }

    @Test
    void resolveTemplate_previousOutput_withoutSpaces() {
        context.setPreviousOutput("output");

        String result = context.resolveTemplate("Use: {{previous_output}}");

        assertThat(result).isEqualTo("Use: output");
    }

    @Test
    void resolveTemplate_previousOutput_null_replacesWithEmpty() {
        String result = context.resolveTemplate("Use: {{ previous_output }}");

        assertThat(result).isEqualTo("Use: ");
    }

    @Test
    void resolveTemplate_customVariables() {
        context.setVariable("branch", "main");
        context.setVariable("file", "test.js");

        String result = context.resolveTemplate("git diff {{ branch }} -- {{ file }}");

        assertThat(result).isEqualTo("git diff main -- test.js");
    }

    @Test
    void resolveTemplate_customVariables_withoutSpaces() {
        context.setVariable("name", "world");

        String result = context.resolveTemplate("Hello {{name}}!");

        assertThat(result).isEqualTo("Hello world!");
    }

    @Test
    void resolveTemplate_nullTemplate_returnsNull() {
        assertThat(context.resolveTemplate(null)).isNull();
    }

    @Test
    void resolveTemplate_noPlaceholders_returnsUnchanged() {
        String result = context.resolveTemplate("static command");

        assertThat(result).isEqualTo("static command");
    }

    @Test
    void nextStepIndex_incrementsSequentially() {
        assertThat(context.nextStepIndex()).isEqualTo(0);
        assertThat(context.nextStepIndex()).isEqualTo(1);
        assertThat(context.nextStepIndex()).isEqualTo(2);
    }

    @Test
    void setAndGetVariable() {
        context.setVariable("key", "value");

        assertThat(context.getVariable("key")).isEqualTo("value");
        assertThat(context.getVariable("nonexistent")).isNull();
    }

    @Test
    void pauseAndResume() {
        assertThat(context.isPaused()).isFalse();

        context.pause();
        assertThat(context.isPaused()).isTrue();

        context.resume();
        assertThat(context.isPaused()).isFalse();
    }

    @Test
    void cancel() {
        assertThat(context.shouldStop()).isFalse();

        context.cancel();
        assertThat(context.shouldStop()).isTrue();
    }
}
