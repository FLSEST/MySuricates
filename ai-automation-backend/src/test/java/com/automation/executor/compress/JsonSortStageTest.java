package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSortStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private final JsonSortStage stage = new JsonSortStage();

    @Test
    void sortIsStable_forEqualKeys_inBothDirections() {
        String input = "[{\"k\":1,\"id\":\"a\"},{\"k\":0,\"id\":\"b\"},{\"k\":1,\"id\":\"c\"}]";

        assertThat(stage.apply(input, Map.of("by", "k"), guard).output())
                .isEqualTo("[{\"k\":0,\"id\":\"b\"},{\"k\":1,\"id\":\"a\"},{\"k\":1,\"id\":\"c\"}]");
        assertThat(stage.apply(input, Map.of("by", "k", "order", "desc"), guard).output())
                .isEqualTo("[{\"k\":1,\"id\":\"a\"},{\"k\":1,\"id\":\"c\"},{\"k\":0,\"id\":\"b\"}]");
    }

    @Test
    void numbersCompareNumerically_notAsText() {
        StageResult r = stage.apply("[{\"v\":10},{\"v\":9},{\"v\":1.5}]", Map.of("by", "v"), guard);

        assertThat(r.output()).isEqualTo("[{\"v\":1.5},{\"v\":9},{\"v\":10}]");
        assertThat(r.note()).isEqualTo("3 entries by v ascending");
    }

    @Test
    void nullValue_countsAsMissing_andStaysLast_evenDescending() {
        String input = "[{\"v\":null},{\"v\":1},{\"v\":2}]";

        assertThat(stage.apply(input, Map.of("by", "v", "order", "desc"), guard).output())
                .isEqualTo("[{\"v\":2},{\"v\":1},{\"v\":null}]");
    }

    @Test
    void nestedArrayAndDottedKey_restOfDocumentUntouched() {
        String input = "{\"x\":1,\"items\":[{\"m\":{\"p\":2}},{\"m\":{\"p\":1}}]}";

        StageResult r = stage.apply(input, Map.of("path", "items", "by", "m.p", "order", "desc"), guard);

        assertThat(r.output()).isEqualTo("{\"x\":1,\"items\":[{\"m\":{\"p\":2}},{\"m\":{\"p\":1}}]}");
        assertThat(r.note()).isEqualTo("2 entries by m.p descending");
        assertThat(stage.apply(input, Map.of("path", "items", "by", "m.p"), guard).output())
                .isEqualTo("{\"x\":1,\"items\":[{\"m\":{\"p\":1}},{\"m\":{\"p\":2}}]}");
    }

    @Test
    void emptyArray_staysEmpty() {
        assertThat(stage.apply("[]", Map.of("by", "v"), guard).output()).isEqualTo("[]");
    }

    @Test
    void validate_byIsRequired_andOrderRestricted() {
        assertThatThrownBy(() -> stage.validate(Map.of()))
                .isInstanceOf(CompressionException.class).hasMessage("Stage json.sort: 'by' is missing");
        assertThatThrownBy(() -> stage.validate(Map.of("by", "v", "order", "DESC")))
                .isInstanceOf(CompressionException.class).hasMessage("Stage json.sort: 'order' must be asc or desc");
        assertThatThrownBy(() -> stage.validate(Map.of("by", 1)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'by' must be text");
        stage.validate(Map.of("by", "v", "order", "asc"));
    }
}
