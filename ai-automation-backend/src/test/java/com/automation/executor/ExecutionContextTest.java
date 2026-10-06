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

    @Test
    void cancelKillsAHostCliProcessDrainedInsideTheStep() throws Exception {
        // A Claude/pi CLI started by a step is drained via ProcessDrain under the step's tracker
        Process cli = new ProcessBuilder("/bin/sh", "-c", "sleep 60").start();
        java.util.concurrent.CountDownLatch registered = new java.util.concurrent.CountDownLatch(1);
        com.automation.util.ProcessTracking.Tracker stepTracker = context.processTracker();
        com.automation.util.ProcessTracking.Tracker observed = new com.automation.util.ProcessTracking.Tracker() {
            @Override public void started(Process process) { stepTracker.started(process); registered.countDown(); }
            @Override public void finished(Process process) { stepTracker.finished(process); }
        };
        java.util.concurrent.CompletableFuture<Long> drained = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            com.automation.util.ProcessTracking.bind(observed, () -> {
                try {
                    return com.automation.util.ProcessDrain.drain(cli, 60, "Host Claude action", com.automation.util.LineListener.NONE);
                } catch (java.io.IOException e) {
                    return e.getMessage();
                }
            });
            return java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
        });
        assertThat(registered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        context.cancel();

        assertThat(drained.get(10, java.util.concurrent.TimeUnit.SECONDS)).isLessThan(10);
        assertThat(cli.isAlive()).isFalse();
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

    // ── Platform data variables: always escaped, a run-level taint source on use ──

    @Test
    void setUntrustedPlatformVariable_isSingleQuotedInShellCommands() {
        context.setUntrustedPlatformVariable("previous_report", "x; rm -rf /");

        assertThat(context.isUntrusted("previous_report")).isTrue();
        assertThat(context.resolveShellTemplate("echo {{ previous_report }}", false))
                .isEqualTo("echo 'x; rm -rf /'");
    }

    @Test
    void setUntrustedPlatformVariable_staysRawForNonShellUse() {
        context.setUntrustedPlatformVariable("open_todos", "a'b");

        assertThat(context.resolveTemplate("say {{ open_todos }}")).isEqualTo("say a'b");
    }

    @Test
    void setUntrustedPlatformVariable_doesNotTaintTheRun() {
        context.setUntrustedPlatformVariable("previous_report", "report");
        context.recordOutput("scan", "a | b");

        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.resolveShellTemplate("{{ previous_output }} {{ block.scan.output }}", false))
                .isEqualTo("a | b a | b");
        assertThat(context.exportTaint()).isEmpty();
    }

    @Test
    void setVariable_clearsThePlatformMark() {
        context.setUntrustedPlatformVariable("previous_report", "x; id");

        context.setVariable("previous_report", "clean");

        assertThat(context.isUntrusted("previous_report")).isFalse();
        assertThat(context.resolveShellTemplate("echo {{ previous_report }}", false)).isEqualTo("echo clean");
    }

    @Test
    void setUntrustedVariable_upgradesAPlatformVariableToAFullTaintSource() {
        context.setUntrustedPlatformVariable("previous_report", "x");
        context.setUntrustedVariable("previous_report", "y; id");

        assertThat(context.hasUntrustedInput()).isTrue();
        assertThat(context.getUntrustedVariables()).containsExactly("previous_report");
        assertThat(context.resolveShellTemplate("echo {{ previous_report }}", false)).isEqualTo("echo 'y; id'");
    }

    // ── Platform data taints the run once it really lands in a shell command line ──

    @Test
    void platformVariable_substitutedIntoAShellCommand_taintsTheRun() {
        context.setUntrustedPlatformVariable("previous_report", "x; id");

        assertThat(context.resolveShellTemplate("echo {{ previous_report }}", false))
                .isEqualTo("echo 'x; id'");

        assertThat(context.hasUntrustedInput()).isTrue();
    }

    @Test
    void platformVariable_substitutedIntoAShellCommand_escapesLaterDerivedVariables() {
        context.setUntrustedPlatformVariable("previous_report", "report");
        context.resolveShellTemplate("echo {{ previous_report }}", false);

        context.setDerivedVariable("item", "a.txt; id");

        assertThat(context.isUntrusted("item")).isTrue();
        assertThat(context.resolveShellTemplate("cat {{ item }}", false)).isEqualTo("cat 'a.txt; id'");
    }

    @Test
    void platformVariable_substitutedIntoAShellCommand_taintsLaterBlockOutputs() {
        context.setUntrustedPlatformVariable("open_todos", "todos");
        context.resolveShellTemplate("echo {{open_todos}}", false);

        context.recordOutput("scan", "a | b");

        assertThat(context.resolveShellTemplate("{{ previous_output }} {{ block.scan.output }}", false))
                .isEqualTo("'a | b' 'a | b'");
        assertThat(context.exportTaint()).containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                java.util.List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME, "scan"));
    }

    @Test
    void platformVariable_substitutedWithEscapeAll_taintsTheRun() {
        context.setUntrustedPlatformVariable("previous_report", "report");

        assertThat(context.resolveShellTemplate("echo {{ previous_report }}", true))
                .isEqualTo("echo 'report'");

        assertThat(context.hasUntrustedInput()).isTrue();
    }

    @Test
    void platformVariable_usedOnlyInAPrompt_doesNotTaintTheRun() {
        context.setUntrustedPlatformVariable("previous_report", "x; id");

        assertThat(context.resolveTemplate("Bewerte: {{ previous_report }}")).isEqualTo("Bewerte: x; id");

        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.exportTaint()).isEmpty();
    }

    @Test
    void platformVariable_notMentionedInTheShellCommand_leavesTheRunUntainted() {
        context.setUntrustedPlatformVariable("previous_report", "x; id");
        context.setVariable("branch", "main");

        assertThat(context.resolveShellTemplate("git checkout {{ branch }}", false))
                .isEqualTo("git checkout main");

        context.recordOutput("scan", "a | b");
        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.resolveShellTemplate("{{ previous_output }}", false)).isEqualTo("a | b");
        assertThat(context.exportTaint()).isEmpty();
    }

    @Test
    void platformVariable_clearedBeforeUse_doesNotTaintTheRun() {
        context.setUntrustedPlatformVariable("previous_report", "x; id");
        context.setVariable("previous_report", "clean");

        assertThat(context.resolveShellTemplate("echo {{ previous_report }}", false)).isEqualTo("echo clean");

        assertThat(context.hasUntrustedInput()).isFalse();
    }

    // ── Platform data in an AI prompt taints that block's output (TODO 22e6bec8) ──

    @Test
    void platformVariable_inAPrompt_taintsTheOutputOfThatBlock() {
        context.setUntrustedPlatformVariable("previous_report", "x; id");

        assertThat(context.resolvePromptTemplate("Bewerte: {{ previous_report }}"))
                .isEqualTo("Bewerte: x; id");
        context.recordOutput("analyse", "a | b");

        assertThat(context.resolveShellTemplate("echo {{ previous_output }}", false))
                .isEqualTo("echo 'a | b'");
        assertThat(context.resolveShellTemplate("echo {{ block.analyse.output }}", false))
                .isEqualTo("echo 'a | b'");
    }

    @Test
    void promptWithoutPlatformData_leavesTheRecordedOutputRaw() {
        context.setUntrustedPlatformVariable("previous_report", "x; id");
        context.setVariable("topic", "release");

        context.resolvePromptTemplate("Bewerte: {{ topic }}");
        context.recordOutput("analyse", "a | b");

        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.resolveShellTemplate("echo {{ previous_output }}", false)).isEqualTo("echo a | b");
        assertThat(context.exportTaint()).isEmpty();
    }

    @Test
    void platformVariable_inAPromptWithoutAnyRecordedOutput_leavesTheRunClean() {
        context.setUntrustedPlatformVariable("previous_report", "x; id");

        context.resolvePromptTemplate("Bewerte: {{ previous_report }}");

        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.exportTaint()).isEmpty();
    }

    /** The flag belongs to the block that used it: an output recorded before the prompt stays raw. */
    @Test
    void platformPrompt_taintsOnlyTheOutputsRecordedAfterIt() {
        context.setUntrustedPlatformVariable("previous_report", "report");
        context.recordOutput("early", "e | f");

        context.resolvePromptTemplate("Bewerte: {{ previous_report }}");
        context.recordOutput("late", "a | b");

        assertThat(context.resolveShellTemplate("{{ block.early.output }} {{ block.late.output }}", false))
                .isEqualTo("e | f 'a | b'");
    }

    /** Sub-workflow hand-over and resume carry the tainted output via {@code __untrusted_outputs}. */
    @Test
    void exportTaint_afterAPlatformPrompt_carriesTheTaintedOutput() {
        context.setUntrustedPlatformVariable("open_todos", "todos");
        context.resolvePromptTemplate("Fasse zusammen: {{ open_todos }}");

        context.recordOutput("analyse", "a | b");

        assertThat(context.exportTaint())
                .containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                        java.util.List.of("analyse", ExecutionContext.PREVIOUS_OUTPUT_NAME));
    }

    /**
     * The tainted output taints the run: from then on the taint is sticky as with external input —
     * even a following block with a clean prompt delivers a quoted output, and the export is
     * no longer empty. That is the statement that was in the docs and is pinned down here.
     */
    @Test
    void platformPrompt_recordedOutput_taintsTheRestOfTheRun() {
        context.setUntrustedPlatformVariable("previous_report", "letzter Bericht");
        context.resolvePromptTemplate("Bewerte: {{ previous_report }}");
        context.recordOutput("analyse", "a | b");

        // second block, prompt without platform data
        context.resolvePromptTemplate("Fasse den Stand zusammen");
        context.recordOutput("zusammenfassung", "c | d");
        context.setDerivedVariable("step_1_output", "e | f");

        assertThat(context.hasUntrustedInput()).isTrue();
        assertThat(context.resolveShellTemplate("echo {{ block.zusammenfassung.output }}", false))
                .isEqualTo("echo 'c | d'");
        assertThat(context.resolveShellTemplate("echo {{ previous_output }}", false)).isEqualTo("echo 'c | d'");
        assertThat(context.resolveShellTemplate("echo {{ step_1_output }}", false)).isEqualTo("echo 'e | f'");
        assertThat(context.exportTaint())
                .isNotEmpty()
                .containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                        java.util.List.of("analyse", ExecutionContext.PREVIOUS_OUTPUT_NAME, "zusammenfassung"));
    }

    @Test
    void resolvePromptTemplate_neverEscapes_andReportsOnlyPlatformData() {
        context.setUntrustedPlatformVariable("previous_report", "a'b");
        context.setUntrustedVariable("payload", "c'd");

        assertThat(context.resolvePromptTemplate("{{ previous_report }} {{ payload }}")).isEqualTo("a'b c'd");
    }

    // ── replaceValue: value changes, trust label stays ──────────────────────────

    @Test
    void replaceValue_platformVariable_keepsThePlatformLabel() {
        context.setUntrustedPlatformVariable("previous_report", "/source/repo/a.java");

        context.replaceValue("previous_report", "/runs/run/repo/a.java; id");

        assertThat(context.isUntrusted("previous_report")).isTrue();
        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.resolveShellTemplate("echo {{ previous_report }}", false))
                .isEqualTo("echo '/runs/run/repo/a.java; id'");
    }

    @Test
    void replaceValue_untrustedVariable_staysExternallyUntrusted() {
        context.setUntrustedVariable("branch", "/source/repo");

        context.replaceValue("branch", "/runs/run/repo; id");

        assertThat(context.isUntrusted("branch")).isTrue();
        assertThat(context.hasUntrustedInput()).isTrue();
        assertThat(context.getUntrustedVariables()).containsExactly("branch");
        assertThat(context.resolveShellTemplate("cd {{ branch }}", false)).isEqualTo("cd '/runs/run/repo; id'");
    }

    @Test
    void replaceValue_trustedVariable_staysTrusted() {
        context.setVariable("target_file", "/source/repo/a.java");

        context.replaceValue("target_file", "/runs/run/repo/a.java");

        assertThat(context.isUntrusted("target_file")).isFalse();
        assertThat(context.resolveShellTemplate("cat {{ target_file }}", false))
                .isEqualTo("cat /runs/run/repo/a.java");
    }

    // ── Taint propagation: outputs derived from untrusted input ─────────────────

    @Test
    void recordOutput_cleanRun_previousOutputAndBlockOutputStayRaw() {
        context.recordOutput("scan", "a | b");

        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.resolveShellTemplate("{{ previous_output }} {{ block.scan.output }}", false))
                .isEqualTo("a | b a | b");
        assertThat(context.exportTaint()).isEmpty();
    }

    @Test
    void recordOutput_untrustedVariableInRun_taintsPreviousOutputAndBlockOutput() {
        context.setUntrustedVariable("branch", "main");
        context.recordOutput("scan", "x; id");

        assertThat(context.hasUntrustedInput()).isTrue();
        assertThat(context.resolveShellTemplate("echo {{ previous_output }} {{ block.scan.output }}", false))
                .isEqualTo("echo 'x; id' 'x; id'");
    }

    @Test
    void recordOutput_taintedOutput_singleQuoteCannotBreakOut() {
        context.setUntrustedVariable("u", "z");
        context.recordOutput("scan", "x'; id; echo '");

        assertThat(context.resolveShellTemplate("echo {{ block.scan.output }}", false))
                .isEqualTo("echo 'x'\\''; id; echo '\\'''");
    }

    @Test
    void recordOutput_taintedOutput_isNeverReExpanded() {
        context.setVariable("secret", "it's raw $(danger)");
        context.setUntrustedVariable("u", "z");
        context.recordOutput(null, "{{ secret }}; id");

        String resolved = context.resolveShellTemplate("echo {{ previous_output }}", false);

        assertThat(resolved).isEqualTo("echo '{{ secret }}; id'");
        assertThat(resolved).doesNotContain("danger");
    }

    @Test
    void recordOutput_taintStaysStickyAfterTheUntrustedVariableIsOverwritten() {
        context.setUntrustedVariable("u", "z");
        context.recordOutput("first", "tainted");
        context.setVariable("u", "clean");   // no untrusted variable left, but the run stays tainted

        assertThat(context.hasUntrustedInput()).isTrue();
        context.recordOutput("second", "also tainted");

        assertThat(context.resolveShellTemplate(
                "{{ block.first.output }} {{ block.second.output }} {{ previous_output }}", false))
                .isEqualTo("'tainted' 'also tainted' 'also tainted'");
    }

    @Test
    void recordOutput_unnamedBlock_taintsOnlyPreviousOutput() {
        context.setUntrustedVariable("u", "z");
        context.setBlockOutput("scan", "raw && stays");
        context.recordOutput(null, "x; id");

        assertThat(context.resolveShellTemplate("{{ previous_output }} {{ block.scan.output }}", false))
                .isEqualTo("'x; id' raw && stays");
    }

    @Test
    void recordOutput_taintedOutput_staysRawForNonShellUse() {
        context.setUntrustedVariable("u", "z");
        context.recordOutput("scan", "a'b");

        assertThat(context.resolveTemplate("{{ previous_output }} {{ block.scan.output }}"))
                .isEqualTo("a'b a'b");
    }

    @Test
    void markOutputUntrusted_unknownBlockName_leavesPlaceholder() {
        context.markOutputUntrusted("missing");

        assertThat(context.resolveShellTemplate("[{{ block.missing.output }}]", false))
                .isEqualTo("[{{ block.missing.output }}]");
    }

    @Test
    void markOutputUntrusted_previousOutput_escapesTheSeededOutput() {
        context.setPreviousOutput("x; id");
        context.markOutputUntrusted(ExecutionContext.PREVIOUS_OUTPUT_NAME);

        assertThat(context.hasUntrustedInput()).isTrue();
        assertThat(context.resolveShellTemplate("echo {{ previous_output }}", false)).isEqualTo("echo 'x; id'");
    }

    @Test
    void setDerivedVariable_isEscapedOnlyInATaintedRun() {
        context.setDerivedVariable("step_0_output", "a | b");
        assertThat(context.resolveShellTemplate("{{ step_0_output }}", false)).isEqualTo("a | b");

        context.setUntrustedVariable("u", "z");
        context.setDerivedVariable("step_1_output", "c; id");
        assertThat(context.resolveShellTemplate("{{ step_1_output }}", false)).isEqualTo("'c; id'");
    }

    @Test
    void exportTaint_cleanRun_isEmpty() {
        context.setVariable("a", "1");
        context.recordOutput("b", "2");
        context.setDerivedVariable("step_0_output", "3");

        assertThat(context.exportTaint()).isEmpty();
    }

    @Test
    void exportTaint_taintedRun_listsUntrustedVariablesAndOutputs() {
        context.setUntrustedVariable("branch", "main");
        context.recordOutput("scan", "out");
        context.setDerivedVariable("step_0_output", "out");

        assertThat(context.exportTaint())
                .containsEntry(ExecutionContext.UNTRUSTED_KEYS_VARIABLE,
                        java.util.List.of("branch", "step_0_output"))
                .containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                        java.util.List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME, "scan"));
    }

    /**
     * TODO cadfc423: the flag "platform data landed in a shell command line" belongs
     * in the export — otherwise a resume starts without booked output, untainted again.
     */
    @Test
    void exportTaint_platformDataUsedInShell_isExportedWithoutAnyRecordedOutput() {
        context.setUntrustedPlatformVariable("previous_report", "x; id");
        context.resolveShellTemplate("echo {{ previous_report }}", false);

        assertThat(context.exportTaint())
                .containsEntry(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true)
                .doesNotContainKey(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE);
    }

    @Test
    void markPlatformUsedInShell_taintsTheResumedRun_withoutAnyRecordedOutput() {
        ExecutionContext resumed = ExecutionContext.builder().workflowRunId("run-2").build();

        resumed.markPlatformUsedInShell();

        assertThat(resumed.hasUntrustedInput()).isTrue();
        assertThat(resumed.exportTaint())
                .containsEntry(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true);
    }

    @Test
    void providerFor_blockConfigOverridesWorkflowProvider() {
        context.setProvider("CODEX");
        var block = com.automation.entity.BlockDefinition.builder()
                .type("simple")
                .config(new java.util.HashMap<>(java.util.Map.of("command", "x", "provider", "PI")))
                .build();

        assertThat(context.providerFor(block)).isEqualTo("PI");
    }

    @Test
    void providerFor_fallsBackToWorkflowProvider_whenBlockHasNone() {
        context.setProvider("CODEX");
        var block = com.automation.entity.BlockDefinition.builder()
                .type("simple")
                .config(new java.util.HashMap<>(java.util.Map.of("command", "x")))
                .build();

        assertThat(context.providerFor(block)).isEqualTo("CODEX");
        assertThat(context.providerFor(null)).isEqualTo("CODEX");
    }

    @Test
    void modelFor_blockConfigOverridesWorkflowModel() {
        context.setModel("sonnet");
        var block = com.automation.entity.BlockDefinition.builder()
                .type("simple")
                .config(new java.util.HashMap<>(java.util.Map.of("command", "x", "model", "opus")))
                .build();

        assertThat(context.modelFor(block)).isEqualTo("opus");
    }

    @Test
    void modelFor_workflowModelNotInherited_whenBlockSwitchesProvider() {
        context.setProvider("CLAUDE");
        context.setModel("sonnet");
        var sameProvider = com.automation.entity.BlockDefinition.builder()
                .type("simple")
                .config(new java.util.HashMap<>(java.util.Map.of("command", "x")))
                .build();
        var otherProvider = com.automation.entity.BlockDefinition.builder()
                .type("simple")
                .config(new java.util.HashMap<>(java.util.Map.of("command", "x", "provider", "PI")))
                .build();

        assertThat(context.modelFor(sameProvider)).isEqualTo("sonnet");
        assertThat(context.modelFor(otherProvider)).isNull();
    }

    @org.junit.jupiter.api.Nested
    class NamesEscapedValue {

        @Test
        void untrustedVariable_inBothSpellings() {
            context.setUntrustedVariable("branch", "--config=/x");
            assertThat(context.namesEscapedValue("--ref={{ branch }}")).isTrue();
            assertThat(context.namesEscapedValue("--ref={{branch}}")).isTrue();
        }

        @Test
        void platformVariable() {
            context.setUntrustedPlatformVariable("open_todos", "x");
            assertThat(context.namesEscapedValue("{{ open_todos }}")).isTrue();
        }

        @Test
        void taintedOutputs() {
            context.setUntrustedVariable("payload", "x");
            context.recordOutput("scan", "out");
            assertThat(context.namesEscapedValue("{{ block.scan.output }}")).isTrue();
            assertThat(context.namesEscapedValue("{{ previous_output }}")).isTrue();
        }

        @Test
        void trustedValuesAndPlainText() {
            context.setVariable("target", "/workspace/sub");
            context.recordOutput("scan", "out");
            assertThat(context.namesEscapedValue("{{ target }} {{ block.scan.output }} {{ previous_output }} fs")).isFalse();
        }
    }
}
