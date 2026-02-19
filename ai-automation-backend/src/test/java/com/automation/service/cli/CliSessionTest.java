package com.automation.service.cli;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliSessionTest {

    @Test
    void constructor_setsInitialState() {
        CliSession session = new CliSession("container-1", "claude");

        assertThat(session.getContainerId()).isEqualTo("container-1");
        assertThat(session.getCliType()).isEqualTo("claude");
        assertThat(session.isAlive()).isFalse(); // Not started yet
        assertThat(session.getLastActivity()).isNotNull();
    }

    @Test
    void isIdle_recentActivity_returnsFalse() {
        CliSession session = new CliSession("container-1", "claude");

        assertThat(session.isIdle(Duration.ofMinutes(30))).isFalse();
    }

    @Test
    void isIdle_oldActivity_returnsTrue() {
        CliSession session = new CliSession("container-1", "claude");
        // We can't easily set lastActivity to the past, but we can verify the logic
        // with a very short timeout
        assertThat(session.isIdle(Duration.ZERO)).isTrue();
    }

    @Test
    void sendCommand_notAlive_throwsException() {
        CliSession session = new CliSession("container-1", "claude");

        assertThatThrownBy(() -> session.sendCommand("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not alive");
    }

    @Test
    void close_notStarted_doesNotThrow() {
        CliSession session = new CliSession("container-1", "claude");
        session.close();
        assertThat(session.isAlive()).isFalse();
    }

    @Test
    void close_setsAliveToFalse() {
        CliSession session = new CliSession("container-1", "claude");
        session.close();
        assertThat(session.isAlive()).isFalse();
    }

    @Test
    void customPromptPattern_accepted() {
        var pattern = java.util.regex.Pattern.compile("^custom-prompt>");
        CliSession session = new CliSession("container-1", "custom-cli", pattern, true, java.util.List.of());

        assertThat(session.getCliType()).isEqualTo("custom-cli");
    }
}
