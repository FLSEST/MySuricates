package com.automation.service.cli;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CliSessionTest {

    // --- Constructor & Initial State ---

    @Test
    void constructor_setsInitialState() {
        CliSession session = new CliSession("container-1", "claude");

        assertThat(session.getContainerId()).isEqualTo("container-1");
        assertThat(session.getCliType()).isEqualTo("claude");
        assertThat(session.isAlive()).isFalse(); // Not started yet
        assertThat(session.getLastActivity()).isNotNull();
    }

    @Test
    void constructor_withSkipPermissionsAndAllowedTools_setsState() {
        CliSession session = new CliSession("container-1", "claude", false,
                java.util.List.of("Bash", "Read"));

        assertThat(session.getContainerId()).isEqualTo("container-1");
        assertThat(session.getCliType()).isEqualTo("claude");
        assertThat(session.isAlive()).isFalse();
    }

    @Test
    void constructor_withNullAllowedTools_doesNotThrow() {
        // Null allowedTools should default to empty list internally
        CliSession session = new CliSession("container-1", "claude", null, true, null);

        assertThat(session.getCliType()).isEqualTo("claude");
        assertThat(session.getContainerId()).isEqualTo("container-1");
    }

    @Test
    void customPromptPattern_accepted() {
        var pattern = java.util.regex.Pattern.compile("^custom-prompt>");
        CliSession session = new CliSession("container-1", "custom-cli", pattern, true,
                java.util.List.of());

        assertThat(session.getCliType()).isEqualTo("custom-cli");
    }

    // --- Idle Detection ---

    @Test
    void isIdle_recentActivity_returnsFalse() {
        CliSession session = new CliSession("container-1", "claude");

        assertThat(session.isIdle(Duration.ofMinutes(30))).isFalse();
    }

    @Test
    void isIdle_oldActivity_returnsTrue() {
        CliSession session = new CliSession("container-1", "claude");
        // Duration.ZERO means anything is older than the timeout
        assertThat(session.isIdle(Duration.ZERO)).isTrue();
    }

    @Test
    void isIdle_largeTimeout_returnsFalse() {
        CliSession session = new CliSession("container-1", "claude");

        assertThat(session.isIdle(Duration.ofHours(1))).isFalse();
    }

    // --- Session Lifecycle (start / close) ---

    @Test
    void start_oneOffMode_setsAliveTrue() throws Exception {
        CliSession session = new CliSession("container-1", "claude");

        session.start();

        assertThat(session.isAlive()).isTrue();
    }

    @Test
    void start_isIdempotentInOneOffMode() throws Exception {
        CliSession session = new CliSession("container-1", "claude");

        session.start();
        session.start(); // second call must not throw or change state to false

        assertThat(session.isAlive()).isTrue();
    }

    @Test
    void startThenClose_lifecycle_transitionsAliveCorrectly() throws Exception {
        CliSession session = new CliSession("container-1", "claude");

        assertThat(session.isAlive()).isFalse();
        session.start();
        assertThat(session.isAlive()).isTrue();
        session.close();
        assertThat(session.isAlive()).isFalse();
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
    void close_calledTwice_doesNotThrow() throws Exception {
        CliSession session = new CliSession("container-1", "claude");
        session.start();

        session.close();
        session.close(); // double-close should be safe (forced stream close path)

        assertThat(session.isAlive()).isFalse();
    }

    @Test
    void session_implementsCloseable_canBeUsedInTryWithResources() throws Exception {
        CliSession captured;
        try (CliSession session = new CliSession("container-1", "claude")) {
            session.start();
            assertThat(session.isAlive()).isTrue();
            captured = session;
        }
        // try-with-resources triggered close()
        assertThat(captured.isAlive()).isFalse();
    }

    // --- sendCommand error paths ---

    @Test
    void sendCommand_notAlive_throwsException() {
        // claude + oneOffMode → delegates to sendCommandEnriched, which also checks alive
        CliSession session = new CliSession("container-1", "claude");

        assertThatThrownBy(() -> session.sendCommand("test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not alive");
    }

    @Test
    void sendCommand_nonClaude_notAlive_throws() {
        // Non-claude path: skips sendCommandEnriched delegation, hits the alive check directly
        CliSession session = new CliSession("container-1", "bash");

        assertThatThrownBy(() -> session.sendCommand("ls"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not alive");
    }

    @Test
    void sendCommand_oneOffMode_nonClaude_throwsUnsupported() throws Exception {
        // bash in one-off mode is not supported — verifies the executeOneOff guard
        CliSession session = new CliSession("container-1", "bash");
        session.start();

        assertThatThrownBy(() -> session.sendCommand("ls"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Only claude CLI type is supported");
    }

    // --- sendCommandEnriched overloads (error / not-alive paths) ---

    @Test
    void sendCommandEnriched_singleArg_notAlive_throws() {
        CliSession session = new CliSession("container-1", "claude");

        assertThatThrownBy(() -> session.sendCommandEnriched("hi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not alive");
    }

    @Test
    void sendCommandEnriched_withModel_notAlive_throws() {
        CliSession session = new CliSession("container-1", "claude");

        assertThatThrownBy(() -> session.sendCommandEnriched("hi", "claude-sonnet-4-6"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not alive");
    }

    @Test
    void sendCommandEnriched_withModelAndSystemPrompt_notAlive_throws() {
        CliSession session = new CliSession("container-1", "claude");

        assertThatThrownBy(() -> session.sendCommandEnriched("hi", "claude-sonnet-4-6", "be helpful"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not alive");
    }

    // --- Records ---

    @Test
    void commandResult_record_holdsValues() {
        var result = new CliSession.CommandResult("hello world", 0);

        assertThat(result.output()).isEqualTo("hello world");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void commandResult_record_supportsEquality() {
        var a = new CliSession.CommandResult("x", 0);
        var b = new CliSession.CommandResult("x", 0);
        var c = new CliSession.CommandResult("x", 1);

        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void enrichedCommandResult_toCommandResult_convertsValues() {
        var enriched = new CliSession.EnrichedCommandResult("output text", 1, null);

        CliSession.CommandResult basic = enriched.toCommandResult();

        assertThat(basic.output()).isEqualTo("output text");
        assertThat(basic.exitCode()).isEqualTo(1);
    }

    @Test
    void enrichedCommandResult_record_holdsAllValues() {
        var enriched = new CliSession.EnrichedCommandResult("out", 42, null);

        assertThat(enriched.output()).isEqualTo("out");
        assertThat(enriched.exitCode()).isEqualTo(42);
        assertThat(enriched.parsed()).isNull();
    }
}
