package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LinesDedupeStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private final LinesDedupeStage stage = new LinesDedupeStage();

    @Test
    void defaultIsGlobal_withoutCounts_nullConfigAllowed() {
        StageResult r = stage.apply("a\nb\na\n", null, guard);

        assertThat(r.output()).isEqualTo("a\nb\n");
        assertThat(r.note()).isEqualTo("−1 lines");
    }

    @Test
    void blankLines_areNeverDeduplicated() {
        assertThat(stage.apply("x\n\n\ny\n\n", Map.of(), guard).output()).isEqualTo("x\n\n\ny\n\n");
        assertThat(stage.apply("\n\nx", Map.of("consecutive_only", true), guard).output()).isEqualTo("\n\nx");
    }

    @Test
    void count_prefixOnlyForRepeatedLines() {
        StageResult r = stage.apply("a\nb\na", Map.of("count", true), guard);

        assertThat(r.output()).isEqualTo("2× a\nb");
    }

    @Test
    void nothingRemoved_noteIsZero() {
        StageResult r = stage.apply("a\nb", Map.of(), guard);

        assertThat(r.output()).isEqualTo("a\nb");
        assertThat(r.note()).isEqualTo("−0 lines");
    }

    @Test
    void flagsMustBeRealBooleans() {
        assertThat(stage.apply("a\na", Map.of("count", "true"), guard).output()).as("Text 'true' ist kein Flag").isEqualTo("a");
        assertThat(stage.apply("a\nb\na", Map.of("consecutive_only", "true"), guard).output()).isEqualTo("a\nb");
    }
}
