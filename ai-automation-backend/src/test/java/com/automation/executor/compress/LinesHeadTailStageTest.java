package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinesHeadTailStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));

    @Test
    void factoriesYieldDistinctOps() {
        assertThat(LinesHeadTailStage.head().op()).isEqualTo("lines.head");
        assertThat(LinesHeadTailStage.tail().op()).isEqualTo("lines.tail");
    }

    @Test
    void exactlyKeepLines_isUnchanged_withoutNote() {
        StageResult r = LinesHeadTailStage.head().apply("a\nb", Map.of("lines", 2), guard);

        assertThat(r.output()).isEqualTo("a\nb");
        assertThat(r.note()).isNull();
    }

    @Test
    void cut_noteCountsOmittedLines_withGermanGrouping() {
        String input = "x\n".repeat(1500) + "ende";

        StageResult r = LinesHeadTailStage.tail().apply(input, Map.of("lines", 1), guard);

        assertThat(r.output()).isEqualTo("[… 1.500 Zeilen ausgelassen …]\nende");
        assertThat(r.note()).isEqualTo("−1.500 Zeilen");
    }

    @Test
    void validate_linesRequiredAndPositive() {
        assertThatThrownBy(() -> LinesHeadTailStage.head().validate(Map.of()))
                .isInstanceOf(CompressionException.class).hasMessage("Stufe lines.head: 'lines' fehlt");
        assertThatThrownBy(() -> LinesHeadTailStage.tail().validate(Map.of("lines", 0)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Stufe lines.tail: 'lines' muss eine ganze Zahl");
        LinesHeadTailStage.head().validate(Map.of("lines", 1));
    }
}
