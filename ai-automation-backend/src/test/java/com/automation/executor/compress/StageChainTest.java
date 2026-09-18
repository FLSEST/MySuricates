package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageChainTest {

    @Test
    void parse_rejectsEmptyUnknownAndInvalidConfigBeforeRunning() {
        assertThatThrownBy(() -> StageChain.parse(List.of())).hasMessageContaining("'stages'");
        assertThatThrownBy(() -> StageChain.parse(List.of(Map.of("op", "nope"))))
                .isInstanceOf(CompressionException.class).hasMessageContaining("unbekannte Operation 'nope'");
        assertThatThrownBy(() -> StageChain.parse(List.of(Map.of("op", "text.truncate"))))
                .hasMessageContaining("Stufe text.truncate").hasMessageContaining("max_chars");
        assertThatThrownBy(() -> StageChain.parse(List.of("text.normalize"))).hasMessageContaining("Stufe 1");
    }

    @Test
    void run_appliesInOrder_andRecordsSizesPerStage() {
        var steps = StageChain.parse(List.of(
                Map.of("op", "lines.dedupe", "count", true),
                Map.of("op", "lines.head", "lines", 1)));

        StageChain.Outcome o = StageChain.run(steps, "x\nx\ny", RegexGuard.startingNow(Duration.ofSeconds(5)));

        assertThat(o.text()).isEqualTo("2× x\n[… 1 Zeilen ausgelassen …]");
        assertThat(o.stageMetadata()).hasSize(2);
        assertThat(o.stageMetadata().getFirst()).containsEntry("op", "lines.dedupe").containsEntry("inChars", 5);
        assertThat(o.notes().getFirst()).isEqualTo("lines.dedupe (−1 Zeilen)");
    }

    @Test
    void run_stageError_namesPositionAndOp_keepsOriginalText() {
        var steps = StageChain.parse(List.of(
                Map.of("op", "text.normalize"),
                Map.of("op", "lines.replace", "pattern", "a", "replacement", "$9")));

        assertThatThrownBy(() -> StageChain.run(steps, "a", RegexGuard.startingNow(Duration.ofSeconds(5))))
                .isInstanceOf(CompressionException.class)
                .hasMessageStartingWith("Stufe 2 (lines.replace): ungültige Ersetzung '$9'")
                .satisfies(e -> assertThat(e.getMessage().split("Stufe", -1)).as("genau eine Stufen-Angabe").hasSize(2));
    }

    @Test
    void run_stageErrorFromAHelper_isNamedOnce() {
        var steps = StageChain.parse(List.of(Map.of("op", "json.limit", "path", "meta", "count", 1)));

        assertThatThrownBy(() -> StageChain.run(steps, "{\"meta\":{}}", RegexGuard.startingNow(Duration.ofSeconds(5))))
                .isInstanceOf(CompressionException.class)
                .hasMessage("Stufe 1 (json.limit): 'meta' ist kein Array");
    }

    @Test
    void run_timeoutInStage_namesPosition() {
        var steps = StageChain.parse(List.of(Map.of("op", "lines.filter", "include", List.of("^((a+)+)+$"))));

        assertThatThrownBy(() -> StageChain.run(steps, "a".repeat(40) + "!", RegexGuard.startingNow(Duration.ofMillis(200))))
                .isInstanceOf(CompressionException.class)
                .hasMessage("Stufe 1 (lines.filter): Zeitlimit überschritten — regulärer Ausdruck zu aufwendig oder Eingabe zu groß");
    }

    @Test
    void parse_jsonStageAfterTextStage_isConfigError() {
        assertThatThrownBy(() -> StageChain.parse(List.of(
                Map.of("op", "json.to_lines", "template", "{a}"),
                Map.of("op", "json.limit", "count", 1))))
                .isInstanceOf(CompressionException.class).hasMessageContaining("erwartet JSON");
    }
}
