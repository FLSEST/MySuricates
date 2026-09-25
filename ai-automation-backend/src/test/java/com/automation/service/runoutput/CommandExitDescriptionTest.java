package com.automation.service.runoutput;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Die synthetische Gate-Beschreibung eines checked_command – geschrieben und erkannt an einer Stelle (TODO 2aa9ddd5). */
class CommandExitDescriptionTest {

    @Test
    void whatTheBlockWrites_isRecognised() {
        assertThat(CommandExitDescription.of(0)).isEqualTo("Command exit 0");
        assertThat(CommandExitDescription.isSynthetic(CommandExitDescription.of(0))).isTrue();
        assertThat(CommandExitDescription.isSynthetic(CommandExitDescription.of(137))).isTrue();
        assertThat(CommandExitDescription.isSynthetic(CommandExitDescription.of(-1))).isTrue();
        assertThat(CommandExitDescription.isSynthetic(" Command exit 1 ")).isTrue();
    }

    @Test
    void aDescriptionWithWordsOfItsOwn_isNotSynthetic() {
        assertThat(CommandExitDescription.isSynthetic("Command exit 0, 214 Tests gruen")).isFalse();
        assertThat(CommandExitDescription.isSynthetic("Gesundheit gut")).isFalse();
        assertThat(CommandExitDescription.isSynthetic("")).isFalse();
        assertThat(CommandExitDescription.isSynthetic(null)).isFalse();
    }
}
