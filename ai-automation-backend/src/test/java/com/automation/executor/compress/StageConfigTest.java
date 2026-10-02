package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StageConfigTest {

    private static final String W = StageConfig.where("x.y");

    @Test
    void where_namesTheStage() {
        assertThat(W).isEqualTo("Stage x.y");
    }

    @Test
    void intValue_fallbackAndMissing() {
        assertThat(StageConfig.intValue(null, "n", 7, 0, 10, W)).isEqualTo(7);
        assertThat(StageConfig.intValue(Map.of(), "n", 7, 0, 10, W)).isEqualTo(7);
        assertThatThrownBy(() -> StageConfig.intValue(Map.of(), "n", null, 0, 10, W))
                .isInstanceOf(CompressionException.class).hasMessage("Stage x.y: 'n' is missing");
    }

    @Test
    void intValue_acceptsIntegralNumbersOfAnyType_withinInclusiveBounds() {
        assertThat(StageConfig.intValue(Map.of("n", 5.0), "n", null, 0, 10, W)).isEqualTo(5);
        assertThat(StageConfig.intValue(Map.of("n", 10L), "n", null, 0, 10, W)).isEqualTo(10);
        assertThat(StageConfig.intValue(Map.of("n", 0), "n", null, 0, 10, W)).isZero();
    }

    @Test
    void intValue_rejectsFractionsTextAndOutOfRange_withGermanNumbers() {
        for (Object bad : List.of(5.5, "5", 11, -1, 3_000_000_000L)) {
            assertThatThrownBy(() -> StageConfig.intValue(Map.of("n", bad), "n", null, 0, 10, W))
                    .as(String.valueOf(bad))
                    .isInstanceOf(CompressionException.class)
                    .hasMessage("Stage x.y: 'n' must be a whole number between 0 and 10");
        }
        assertThatThrownBy(() -> StageConfig.intValue(Map.of("n", 0), "n", null, 1, 1_000_000, W))
                .hasMessageContaining("between 1 and 1,000,000");
    }

    @Test
    void string_fallbackMissingAndWrongType() {
        assertThat(StageConfig.string(null, "s", "def", W)).isEqualTo("def");
        assertThat(StageConfig.string(Map.of("s", ""), "s", "def", W)).as("leerer Text ist ein Wert").isEmpty();
        assertThatThrownBy(() -> StageConfig.string(Map.of(), "s", null, W)).hasMessage("Stage x.y: 's' is missing");
        assertThatThrownBy(() -> StageConfig.string(Map.of("s", 1), "s", "def", W))
                .isInstanceOf(CompressionException.class).hasMessage("Stage x.y: 's' must be text");
    }

    @Test
    void strings_absentIsEmpty_listIsCopied_mixedOrNonListFails() {
        List<String> source = new java.util.ArrayList<>(List.of("a", "b"));
        Map<String, Object> config = new HashMap<>(Map.of("l", source));

        List<String> out = StageConfig.strings(config, "l", W);
        source.add("c");

        assertThat(out).containsExactly("a", "b");
        assertThat(StageConfig.strings(null, "l", W)).isEmpty();
        assertThatThrownBy(() -> StageConfig.strings(Map.of("l", Arrays.asList("a", 1)), "l", W))
                .hasMessage("Stage x.y: 'l' must be a list of texts");
        assertThatThrownBy(() -> StageConfig.strings(Map.of("l", "a"), "l", W)).isInstanceOf(CompressionException.class);
    }

    @Test
    void flag_onlyBooleanTrueCounts() {
        assertThat(StageConfig.flag(Map.of("f", true), "f")).isTrue();
        assertThat(StageConfig.flag(Map.of("f", false), "f")).isFalse();
        assertThat(StageConfig.flag(Map.of("f", "true"), "f")).isFalse();
        assertThat(StageConfig.flag(Map.of(), "f")).isFalse();
        assertThat(StageConfig.flag(null, "f")).isFalse();
    }
}
