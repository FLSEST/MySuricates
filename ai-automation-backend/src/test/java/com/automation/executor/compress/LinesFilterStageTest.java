package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinesFilterStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private final LinesFilterStage stage = new LinesFilterStage();

    @Test
    void excludeBeatsInclude_evenAsContextLine_andTrailingGapIsMarked() {
        StageResult r = stage.apply("a\nERROR x\nERROR skip\nb\nc",
                Map.of("include", List.of("ERROR"), "exclude", List.of("skip"), "after", 1), guard);

        assertThat(r.output()).isEqualTo("[… 1 lines …]\nERROR x\n[… 3 lines …]");
        assertThat(r.note()).isEqualTo("−4 lines");
    }

    @Test
    void multipleIncludePatterns_areOred() {
        StageResult r = stage.apply("a\nWARN\nb\nERROR", Map.of("include", List.of("WARN", "ERROR")), guard);

        assertThat(r.output()).isEqualTo("[… 1 lines …]\nWARN\n[… 1 lines …]\nERROR");
        assertThat(r.note()).isEqualTo("−2 lines");
    }

    @Test
    void contextBeyondInputBounds_isClamped() {
        StageResult r = stage.apply("ERROR\nb", Map.of("include", List.of("ERROR"), "before", 5), guard);

        assertThat(r.output()).isEqualTo("ERROR\n[… 1 lines …]");
    }

    @Test
    void excludeOnly_removingEverything_isNoHits_withoutGapMarker() {
        StageResult r = stage.apply("x\nx", Map.of("exclude", List.of("x")), guard);

        assertThat(r.output()).isEmpty();
        assertThat(r.note()).isEqualTo("no matches");
    }

    @Test
    void validate_rejectsWrongTypesAndContextOutOfRange() {
        assertThatThrownBy(() -> stage.validate(Map.of("include", "ERROR")))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'include' must be a list of texts");
        assertThatThrownBy(() -> stage.validate(Map.of("include", List.of("E"), "before", 1001)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'before'");
        assertThatThrownBy(() -> stage.validate(Map.of("exclude", List.of("E"), "after", -1)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'after'");
        stage.validate(Map.of("exclude", List.of("E"), "after", 1000));
    }
}
