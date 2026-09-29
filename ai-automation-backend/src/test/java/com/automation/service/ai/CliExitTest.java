package com.automation.service.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Nur ein CLI-Ende mit Exit-Code 0 gilt als Antwort; sonst wandert die (gekürzte) Meldung in die Exception. */
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
}
