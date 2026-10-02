package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** StageKind carries the chain rule: a JSON stage must not follow a stage that produces text. */
class StageKindTest {

    @Test
    void values() {
        assertThat(StageKind.values()).containsExactly(StageKind.ANY, StageKind.TEXT, StageKind.JSON);
    }

    @Test
    void jsonStage_afterTextProducingStage_isRejected() {
        assertThatThrownBy(() -> StageChain.parse(List.of(
                Map.of("op", "text.normalize"),
                Map.of("op", "json.limit", "count", 1))))
                .isInstanceOf(CompressionException.class)
                .hasMessage("Stage 2 (json.limit): expects JSON, but an earlier stage yields text");
    }

    @Test
    void jsonAsFirstStage_jsonAfterJson_andAnyAfterJson_areAllowed() {
        var steps = StageChain.parse(List.of(
                Map.of("op", "json.select", "paths", List.of("a")),
                Map.of("op", "json.limit", "path", "a", "count", 1),
                Map.of("op", "json.to_lines", "path", "a", "template", "{v}"),
                Map.of("op", "lines.head", "lines", 1)));

        assertThat(steps).hasSize(4);
    }

    @Test
    void jsonStage_afterToLines_isRejected_becauseToLinesProducesText() {
        assertThatThrownBy(() -> StageChain.parse(List.of(
                Map.of("op", "json.to_lines", "template", "{v}"),
                Map.of("op", "json.sort", "by", "v"))))
                .hasMessageContaining("Stage 2 (json.sort): expects JSON");
    }
}
