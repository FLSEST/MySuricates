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
    void effortFor_blockConfigOverridesWorkflowEffort() {
        context.setEffort("medium");
        var block = com.automation.entity.BlockDefinition.builder()
                .type("simple")
                .config(new java.util.HashMap<>(java.util.Map.of("command", "x", "effort", "XHIGH")))
                .build();

        assertThat(context.effortFor(block)).isEqualTo("xhigh");
    }

    @Test
    void effortFor_fallsBackToWorkflowEffort_whenBlockHasNone() {
        context.setEffort("high");
        var block = com.automation.entity.BlockDefinition.builder()
                .type("simple")
                .config(new java.util.HashMap<>(java.util.Map.of("command", "x")))
                .build();

        assertThat(context.effortFor(block)).isEqualTo("high");
    }

    @Test
    void effortFor_invalidBlockEffort_isIgnored() {
        context.setEffort("low");
        var block = com.automation.entity.BlockDefinition.builder()
                .type("simple")
                .config(new java.util.HashMap<>(java.util.Map.of("command", "x", "effort", "turbo")))
                .build();

        assertThat(context.effortFor(block)).isEqualTo("low");
    }

    @Test
    void effortFor_returnsNull_whenNothingSet() {
        var block = com.automation.entity.BlockDefinition.builder()
                .type("simple")
                .config(new java.util.HashMap<>())
                .build();

        assertThat(context.effortFor(block)).isNull();
        assertThat(context.effortFor(null)).isNull();
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

    // --- Named block outputs: {{ block.<name>.output }} ---

    @Test
    void resolveTemplate_blockOutput_byName() {
        context.setBlockOutput("extract", "JSON-payload");

        String result = context.resolveTemplate("Use {{ block.extract.output }} now");

        assertThat(result).isEqualTo("Use JSON-payload now");
    }

    @Test
    void resolveTemplate_blockOutput_withoutSpaces() {
        context.setBlockOutput("step1", "value");

        String result = context.resolveTemplate("X={{block.step1.output}}");

        assertThat(result).isEqualTo("X=value");
    }

    @Test
    void resolveTemplate_blockOutput_unknownName_leavesPlaceholder() {
        String result = context.resolveTemplate("{{ block.missing.output }}");
        assertThat(result).isEqualTo("{{ block.missing.output }}");
    }

    @Test
    void resolveTemplate_blockOutput_multipleBlocks() {
        context.setBlockOutput("first", "A");
        context.setBlockOutput("second", "B");

        String result = context.resolveTemplate("a={{ block.first.output }} b={{ block.second.output }}");

        assertThat(result).isEqualTo("a=A b=B");
    }

    @Test
    void resolveTemplateShellSafe_blockOutput_isEscaped() {
        context.setBlockOutput("danger", "x'y");

        String result = context.resolveTemplateShellSafe("echo {{ block.danger.output }}");

        assertThat(result).isEqualTo("echo 'x'\\''y'");
    }

    @Test
    void setBlockOutput_blankName_isIgnored() {
        context.setBlockOutput("", "value");
        context.setBlockOutput(null, "value");
        assertThat(context.getBlockOutput("")).isNull();
    }

    @Test
    void setBlockOutput_nullValue_storedAsEmpty() {
        context.setBlockOutput("foo", null);
        String result = context.resolveTemplate("[{{ block.foo.output }}]");
        assertThat(result).isEqualTo("[]");
    }


    // ── resolveShellTemplate: untrusted variables are always escaped ─────────────

    @Test
    void resolveShellTemplate_trustedVariable_isSubstitutedRaw() {
        context.setVariable("path", "/tmp/a b");

        assertThat(context.resolveShellTemplate("ls {{ path }}", false)).isEqualTo("ls /tmp/a b");
    }

    @Test
    void resolveShellTemplate_untrustedVariable_isSingleQuoted() {
        context.setUntrustedVariable("branch", "main; rm -rf /");

        assertThat(context.resolveShellTemplate("git checkout {{ branch }}", false))
                .isEqualTo("git checkout 'main; rm -rf /'");
    }

    @Test
    void resolveShellTemplate_untrustedVariable_commandSubstitutionStaysInert() {
        context.setUntrustedVariable("msg", "$(curl evil)`id`");
        context.setVariable("dir", "/repo");

        assertThat(context.resolveShellTemplate("cd {{ dir }} && echo {{msg}}", false))
                .isEqualTo("cd /repo && echo '$(curl evil)`id`'");
    }

    @Test
    void resolveShellTemplate_untrustedValue_singleQuoteCannotBreakOut() {
        context.setUntrustedVariable("v", "x'; id; echo '");

        assertThat(context.resolveShellTemplate("echo {{ v }}", false))
                .isEqualTo("echo 'x'\\''; id; echo '\\'''");
    }

    @Test
    void resolveShellTemplate_untrustedValue_isNeverReExpanded() {
        context.setVariable("secret", "it's raw $(danger)");
        context.setUntrustedVariable("payload", "{{ secret }}; id");

        String resolved = context.resolveShellTemplate("echo {{ payload }}", false);

        assertThat(resolved).isEqualTo("echo '{{ secret }}; id'");
        assertThat(resolved).doesNotContain("danger");
    }

    @Test
    void resolveShellTemplate_trustedValueReferencingUntrusted_staysUnexpanded() {
        context.setUntrustedVariable("author", "'; id; '");
        context.setVariable("greeting", "Hello {{ author }}");

        assertThat(context.resolveShellTemplate("echo {{ greeting }}", false))
                .isEqualTo("echo Hello {{ author }}");
    }

    @Test
    void resolveShellTemplate_previousOutputAndBlockOutput_stayRaw() {
        context.setPreviousOutput("a | b");
        context.setBlockOutput("scan", "x && y");
        context.setUntrustedVariable("u", "z");

        assertThat(context.resolveShellTemplate("{{ previous_output }} {{ block.scan.output }} {{ u }}", false))
                .isEqualTo("a | b x && y 'z'");
    }

    @Test
    void resolveShellTemplate_escapeAll_quotesEverything() {
        context.setVariable("path", "/tmp/a b");
        context.setUntrustedVariable("u", "z");

        assertThat(context.resolveShellTemplate("ls {{ path }} {{ u }}", true)).isEqualTo("ls '/tmp/a b' 'z'");
    }

    @Test
    void resolveShellTemplate_noUntrustedVariables_isIdenticalToResolveTemplate() {
        context.setVariable("a", "1 $x");
        context.setPreviousOutput("p");

        String template = "{{ a }} {{a}} {{ previous_output }} {{ missing }}";
        assertThat(context.resolveShellTemplate(template, false)).isEqualTo(context.resolveTemplate(template));
    }

    @Test
    void resolveShellTemplate_nullTemplate_returnsNull() {
        context.setUntrustedVariable("u", "z");

        assertThat(context.resolveShellTemplate(null, false)).isNull();
    }

    @Test
    void resolveTemplate_untrustedVariable_isSubstitutedRawForNonShellUse() {
        context.setUntrustedVariable("u", "a'b");

        assertThat(context.resolveTemplate("say {{ u }}")).isEqualTo("say a'b");
    }

    @Test
    void setVariable_clearsUntrustedMark() {
        context.setUntrustedVariable("project_path", "/evil");
        assertThat(context.isUntrusted("project_path")).isTrue();

        context.setVariable("project_path", "/real");

        assertThat(context.isUntrusted("project_path")).isFalse();
        assertThat(context.resolveShellTemplate("cd {{ project_path }}", false)).isEqualTo("cd /real");
    }

    @Test
    void getUntrustedVariables_returnsCopy() {
        context.setUntrustedVariable("u", "z");

        assertThat(context.getUntrustedVariables()).containsExactly("u");
        assertThat(context.getUntrustedVariables()).isNotSameAs(context.getUntrustedVariables());
    }
}
