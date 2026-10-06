package com.automation.service.todo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance of the agent session identifier (TODO 3497533c).
 *
 * <p>Why normalize at all: The field replaces the running-text convention of writing the command
 * {@code claude --resume <uuid>} into the description. An agent therefore sends
 * by experience exactly that — the whole command, in backticks, in a Markdown line. A
 * field whose content has to be cleaned first is as unreliable as the convention it
 * replaces; so the service extracts the UUID and discards whatever is not one.
 */
class TodoAgentSessionRefTest {

    private static final String UUID = "4fde1b91-2cce-4ab2-b7d0-fd2a138f9322";

    @Test
    void takesAPlainUuid() {
        assertThat(TodoService.normalizeSessionRef(UUID)).isEqualTo(UUID);
    }

    @Test
    void takesTheWholeResumeCommand() {
        assertThat(TodoService.normalizeSessionRef("claude --resume " + UUID)).isEqualTo(UUID);
    }

    @Test
    void takesTheMarkdownFormFromTheDescriptionConvention() {
        assertThat(TodoService.normalizeSessionRef("## Claude Code Session\n`claude --resume "
                + UUID + "`\n")).isEqualTo(UUID);
    }

    @Test
    void takesCodexAndOtherAgentsTheSameWay() {
        assertThat(TodoService.normalizeSessionRef("codex --resume " + UUID)).isEqualTo(UUID);
    }

    @Test
    void lowercasesSoTheSameSessionIsOneValue() {
        assertThat(TodoService.normalizeSessionRef(UUID.toUpperCase())).isEqualTo(UUID);
    }

    @Test
    void rejectsWhatIsNotAUuid_insteadOfStoringCommandText() {
        assertThat(TodoService.normalizeSessionRef("claude --resume letzte-sitzung")).isNull();
        assertThat(TodoService.normalizeSessionRef("siehe oben")).isNull();
        assertThat(TodoService.normalizeSessionRef("4fde1b91-2cce")).isNull();
    }

    @Test
    void treatsEmptyAsAbsent() {
        assertThat(TodoService.normalizeSessionRef(null)).isNull();
        assertThat(TodoService.normalizeSessionRef("   ")).isNull();
    }
}
