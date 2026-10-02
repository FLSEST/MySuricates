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
                .isInstanceOf(CompressionException.class).hasMessageContaining("unknown operation 'nope'");
        assertThatThrownBy(() -> StageChain.parse(List.of(Map.of("op", "text.truncate"))))
                .hasMessageContaining("Stage 1 (text.truncate)").hasMessageContaining("max_chars");
        assertThatThrownBy(() -> StageChain.parse(List.of("text.normalize"))).hasMessageContaining("Stage 1");
    }

    @Test
    void parse_configError_namesPositionAndOp_likeARuntimeError() {
        assertThatThrownBy(() -> StageChain.parse(List.of(
                Map.of("op", "text.normalize"),
                Map.of("op", "text.truncate"))))
                .isInstanceOf(CompressionException.class)
                .hasMessageStartingWith("Stage 2 (text.truncate): ")
                .hasMessageContaining("max_chars")
                .satisfies(e -> assertThat(e.getMessage().split("Stage", -1)).as("genau eine Stufen-Angabe").hasSize(2));
    }

    @Test
    void run_appliesInOrder_andRecordsSizesPerStage() {
        var steps = StageChain.parse(List.of(
                Map.of("op", "lines.dedupe", "count", true),
                Map.of("op", "lines.head", "lines", 1)));

        StageChain.Outcome o = StageChain.run(steps, "x\nx\ny", RegexGuard.startingNow(Duration.ofSeconds(5)));

        assertThat(o.text()).isEqualTo("2× x\n[… 1 lines omitted …]");
        assertThat(o.stageMetadata()).hasSize(2);
        assertThat(o.stageMetadata().getFirst()).containsEntry("op", "lines.dedupe").containsEntry("inChars", 5);
        assertThat(o.notes().getFirst()).isEqualTo("lines.dedupe (−1 lines)");
    }

    @Test
    void run_stageError_namesPositionAndOp_keepsOriginalText() {
        var steps = StageChain.parse(List.of(
                Map.of("op", "text.normalize"),
                Map.of("op", "lines.replace", "pattern", "a", "replacement", "$9")));

        assertThatThrownBy(() -> StageChain.run(steps, "a", RegexGuard.startingNow(Duration.ofSeconds(5))))
                .isInstanceOf(CompressionException.class)
                .hasMessageStartingWith("Stage 2 (lines.replace): invalid replacement '$9'")
                .satisfies(e -> assertThat(e.getMessage().split("Stage", -1)).as("genau eine Stufen-Angabe").hasSize(2));
    }

    @Test
    void run_stageErrorFromAHelper_isNamedOnce() {
        var steps = StageChain.parse(List.of(Map.of("op", "json.limit", "path", "meta", "count", 1)));

        assertThatThrownBy(() -> StageChain.run(steps, "{\"meta\":{}}", RegexGuard.startingNow(Duration.ofSeconds(5))))
                .isInstanceOf(CompressionException.class)
                .hasMessage("Stage 1 (json.limit): 'meta' is not an array");
    }

    @Test
    void run_timeoutInStage_namesPosition() {
        var steps = StageChain.parse(List.of(Map.of("op", "lines.filter", "include", List.of("^((a+)+)+$"))));

        assertThatThrownBy(() -> StageChain.run(steps, "a".repeat(40) + "!", RegexGuard.startingNow(Duration.ofMillis(200))))
                .isInstanceOf(CompressionException.class)
                .hasMessage("Stage 1 (lines.filter): Time limit exceeded — regular expression too costly or input too large");
    }

    @Test
    void parse_jsonStageAfterTextStage_isConfigError() {
        assertThatThrownBy(() -> StageChain.parse(List.of(
                Map.of("op", "json.to_lines", "template", "{a}"),
                Map.of("op", "json.limit", "count", 1))))
                .isInstanceOf(CompressionException.class).hasMessageContaining("expects JSON");
    }
}
