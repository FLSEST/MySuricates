package com.automation.service.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Only a CLI end with exit code 0 counts as an answer; otherwise the (truncated) message goes into the exception. */
class CliExitTest {

    @Test
    void exitZero_returnsTheOutputUntouched() throws IOException {
        assertThat(CliExit.answerOrThrow("claude", 0, "  Antwort\n")).isEqualTo("  Antwort\n");
        assertThat(CliExit.answerOrThrow("claude", 0, null)).isNull();
    }

    @Test
    void nonZeroExit_throwsWithLabelCodeAndStrippedMessage() {
        assertThatThrownBy(() -> CliExit.answerOrThrow("pi", 2, "\n Usage limit reached \n"))
                .isInstanceOf(IOException.class)
                .hasMessage("pi failed with exit code 2: Usage limit reached");
    }

    @Test
    void nullOutput_givesAnEmptyMessage() {
        assertThatThrownBy(() -> CliExit.answerOrThrow("claude", 1, null))
                .hasMessage("claude failed with exit code 1: ");
    }

    @Test
    void longMessage_isCutAt300CharsWithEllipsis() {
        String output = "x".repeat(300) + "hinten";

        assertThatThrownBy(() -> CliExit.answerOrThrow("claude", 1, output))
                .hasMessage("claude failed with exit code 1: " + "x".repeat(300) + "...");
    }

    @Test
    void messageOfExactly300Chars_isKept() {
        String output = "y".repeat(300);

        assertThatThrownBy(() -> CliExit.answerOrThrow("claude", 137, output))
                .hasMessage("claude failed with exit code 137: " + output);
    }

    /** c6d1a872: the CLI reported the error itself — its message stands once, without a made-up exit code. */
    @Test
    void errorReportedByTheCli_namesItsMessageOnce() {
        assertThatThrownBy(() -> CliExit.answerOrThrow("Host Claude reasoning", 1,
                "Claude CLI reported an error (error_max_turns) after 3 turns", true))
                .hasMessage("Host Claude reasoning failed: Claude CLI reported an error (error_max_turns) after 3 turns");
    }

    @Test
    void errorReportedByTheCli_withExitCodeZero_isNoError() throws IOException {
        assertThat(CliExit.answerOrThrow("x", 0, "answer", true)).isEqualTo("answer");
    }
}
