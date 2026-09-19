package com.automation.service.todo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Annahme der Agenten-Sitzungskennung (TODO 3497533c).
 *
 * <p>Warum ueberhaupt normalisiert wird: Das Feld ersetzt die Fliesstext-Konvention, den Befehl
 * {@code claude --resume <uuid>} in die Beschreibung zu schreiben. Ein Agent schickt deshalb
 * erfahrungsgemaess genau das — den ganzen Befehl, in Backticks, in einer Markdown-Zeile. Ein
 * Feld, dessen Inhalt man erst reinigen muss, ist so unzuverlaessig wie die Konvention, die es
 * ersetzt; also nimmt der Service die UUID heraus und verwirft, was keine ist.
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
