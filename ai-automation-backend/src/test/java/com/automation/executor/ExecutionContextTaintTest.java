package com.automation.executor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContextTaintTest {

    private final ExecutionContextTaint taint = new ExecutionContextTaint();

    @Test
    void cleanRun_hasNoUntrustedInput_andExportsNothing() {
        assertThat(taint.hasUntrustedInput()).isFalse();
        assertThat(taint.export()).isEmpty();
        assertThat(taint.snapshot()).isSameAs(TemplateResolver.Taint.NONE);
    }

    @Test
    void externalVariable_taintsImmediately_andIsExported() {
        taint.markUntrusted("payload");

        assertThat(taint.isUntrusted("payload")).isTrue();
        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.untrustedVariables()).containsExactly("payload");
        assertThat(taint.export()).containsEntry(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("payload"));
    }

    @Test
    void platformVariable_isEscapedButTaintsOnlyWhenSubstitutedIntoShell() {
        taint.markPlatform("open_todos");

        assertThat(taint.isUntrusted("open_todos")).isTrue();
        assertThat(taint.snapshot().variables()).containsExactly("open_todos");
        assertThat(taint.hasUntrustedInput()).isFalse();
        assertThat(taint.untrustedVariables()).isEmpty();

        taint.substitutedIntoShell("open_todos");

        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.export()).containsEntry(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true);
    }

    @Test
    void substitutedIntoShell_ignoresTrustedAndExternalKeys() {
        taint.markUntrusted("payload");
        taint.substitutedIntoShell("payload");
        taint.substitutedIntoShell("trusted");

        assertThat(taint.export()).doesNotContainKey(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE);
    }

    @Test
    void markTrusted_clearsEarlierLabel() {
        taint.markPlatform("v");
        taint.markTrusted("v");
        taint.substitutedIntoShell("v");

        assertThat(taint.isUntrusted("v")).isFalse();
        assertThat(taint.hasUntrustedInput()).isFalse();
    }

    @Test
    void platformInPrompt_isConsumedOnce_andDoesNotTaintTheRun() {
        taint.markPlatform("previous_report");
        taint.substitutedIntoPrompt("other");
        assertThat(taint.consumePlatformPrompt()).isFalse();

        taint.substitutedIntoPrompt("previous_report");

        assertThat(taint.hasUntrustedInput()).isFalse();
        assertThat(taint.consumePlatformPrompt()).isTrue();
        assertThat(taint.consumePlatformPrompt()).isFalse();
        assertThat(taint.export()).isEmpty();
    }

    @Test
    void markedOutputs_taintTheRun_andAppearInSnapshotAndExport() {
        taint.markOutput("build");
        taint.markBlockOutput("lint");

        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.snapshot().outputs()).containsExactlyInAnyOrder("build", "lint");
        assertThat(taint.snapshot().previousOutput()).isTrue();
        assertThat(taint.export().get(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE))
                .isEqualTo(List.of("build", "lint", ExecutionContext.PREVIOUS_OUTPUT_NAME));
    }

    @Test
    void markPreviousOutput_alone_taints() {
        taint.markPreviousOutput();

        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.export().get(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE))
                .isEqualTo(List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME));
    }

    @Test
    void reappliedPlatformFlag_taintsResumedSegment() {
        taint.markPlatformUsedInShell();

        assertThat(taint.hasUntrustedInput()).isTrue();
        assertThat(taint.export()).containsEntry(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true);
    }
}
