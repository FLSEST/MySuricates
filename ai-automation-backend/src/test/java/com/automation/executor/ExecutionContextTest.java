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

    // --- resolveTemplateShellSafe tests ---

    @Test
    void resolveTemplateShellSafe_simpleValue() {
        context.setVariable("name", "world");

        String result = context.resolveTemplateShellSafe("echo {{ name }}");

        assertThat(result).isEqualTo("echo 'world'");
    }

    @Test
    void resolveTemplateShellSafe_singleQuoteInValue() {
        context.setVariable("msg", "it's fine");

        String result = context.resolveTemplateShellSafe("echo {{ msg }}");

        assertThat(result).isEqualTo("echo 'it'\\''s fine'");
    }

    @Test
    void resolveTemplateShellSafe_semicolonInjection() {
        context.setVariable("input", "; rm -rf /");

        String result = context.resolveTemplateShellSafe("echo {{ input }}");

        assertThat(result).isEqualTo("echo '; rm -rf /'");
    }

    @Test
    void resolveTemplateShellSafe_commandSubstitution() {
        context.setVariable("input", "$(cat /etc/passwd)");

        String result = context.resolveTemplateShellSafe("echo {{ input }}");

        assertThat(result).isEqualTo("echo '$(cat /etc/passwd)'");
    }

    @Test
    void resolveTemplateShellSafe_backticks() {
        context.setVariable("input", "`id`");

        String result = context.resolveTemplateShellSafe("echo {{ input }}");

        assertThat(result).isEqualTo("echo '`id`'");
    }

    @Test
    void resolveTemplateShellSafe_doubleQuotesAndDollar() {
        context.setVariable("input", "\"$HOME\"");

        String result = context.resolveTemplateShellSafe("echo {{ input }}");

        assertThat(result).isEqualTo("echo '\"$HOME\"'");
    }

    @Test
    void resolveTemplateShellSafe_multilineValue() {
        context.setVariable("data", "line1\nline2");

        String result = context.resolveTemplateShellSafe("echo {{ data }}");

        assertThat(result).isEqualTo("echo 'line1\nline2'");
    }

    @Test
    void resolveTemplateShellSafe_emptyValue() {
        context.setVariable("empty", "");

        String result = context.resolveTemplateShellSafe("echo {{ empty }}");

        assertThat(result).isEqualTo("echo ''");
    }

    @Test
    void resolveTemplateShellSafe_nullTemplate() {
        assertThat(context.resolveTemplateShellSafe(null)).isNull();
    }

    @Test
    void resolveTemplateShellSafe_previousOutput() {
        context.setPreviousOutput("; malicious");

        String result = context.resolveTemplateShellSafe("run {{ previous_output }}");

        assertThat(result).isEqualTo("run '; malicious'");
    }

    @Test
    void resolveTemplateShellSafe_multipleVariables() {
        context.setVariable("a", "x'y");
        context.setVariable("b", "$(evil)");

        String result = context.resolveTemplateShellSafe("cmd {{ a }} {{ b }}");

        assertThat(result).isEqualTo("cmd 'x'\\''y' '$(evil)'");
    }

    @Test
    void resolveTemplateShellSafe_withoutSpaces() {
        context.setVariable("name", "world");

        String result = context.resolveTemplateShellSafe("echo {{name}}");

        assertThat(result).isEqualTo("echo 'world'");
    }

    // --- shellEscape static method tests ---

    @Test
    void shellEscape_null() {
        assertThat(ExecutionContext.shellEscape(null)).isEqualTo("''");
    }

    @Test
    void shellEscape_empty() {
        assertThat(ExecutionContext.shellEscape("")).isEqualTo("''");
    }

    @Test
    void shellEscape_simple() {
        assertThat(ExecutionContext.shellEscape("hello")).isEqualTo("'hello'");
    }

    @Test
    void shellEscape_singleQuote() {
        assertThat(ExecutionContext.shellEscape("'")).isEqualTo("''\\'''");
    }

    @Test
    void shellEscape_multipleSingleQuotes() {
        assertThat(ExecutionContext.shellEscape("a'b'c")).isEqualTo("'a'\\''b'\\''c'");
    }
}
