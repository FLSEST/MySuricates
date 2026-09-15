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
    void parse_jsonStageAfterTextStage_isConfigError() {
        assertThatThrownBy(() -> StageChain.parse(List.of(
                Map.of("op", "json.to_lines", "template", "{a}"),
                Map.of("op", "json.limit", "count", 1))))
                .isInstanceOf(CompressionException.class).hasMessageContaining("erwartet JSON");
    }
}
