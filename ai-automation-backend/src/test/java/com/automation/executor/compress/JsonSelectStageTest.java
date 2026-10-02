package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSelectStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private final JsonSelectStage stage = new JsonSelectStage();

    @Test
    void parentAndChildPath_keepWholeSubtree_andBothCountAsFound() {
        StageResult r = stage.apply("{\"meta\":{\"count\":3,\"source\":\"rss\"},\"x\":1}",
                Map.of("paths", List.of("meta", "meta.count")), guard);

        assertThat(r.output()).isEqualTo("{\"meta\":{\"count\":3,\"source\":\"rss\"}}");
        assertThat(r.note()).isEqualTo("−6 chars");
    }

    @Test
    void nothingMatched_onObject_yieldsEmptyObject_onArray_yieldsEmptyArray() {
        StageResult obj = stage.apply("{\"a\":1}", Map.of("paths", List.of("b")), guard);
        StageResult arr = stage.apply("[{\"a\":1}]", Map.of("paths", List.of("a")), guard);

        assertThat(obj.output()).isEqualTo("{}");
        assertThat(obj.note()).isEqualTo("path not found: b");
        assertThat(arr.output()).as("Feldpfad ohne [] greift nicht in ein Wurzel-Array").isEqualTo("[]");
        assertThat(arr.note()).isEqualTo("path not found: a");
    }

    @Test
    void arrayElementsWithoutField_stayAsEmptyObjects_soCountIsPreserved() {
        StageResult r = stage.apply("[5,{\"a\":1},{\"b\":2}]", Map.of("paths", List.of("[].a")), guard);

        assertThat(r.output()).isEqualTo("[{},{\"a\":1},{}]");
        assertThat(r.note()).doesNotContain("nicht gefunden");
    }

    @Test
    void nestedArrays_andExplicitNull_areSelected() {
        String input = "{\"items\":[{\"tags\":[{\"n\":\"x\",\"z\":0}],\"t\":null,\"drop\":1}]}";

        StageResult r = stage.apply(input, Map.of("paths", List.of("items[].tags[].n", "items[].t")), guard);

        assertThat(r.output()).isEqualTo("{\"items\":[{\"tags\":[{\"n\":\"x\"}],\"t\":null}]}");
    }

    @Test
    void pathThroughScalar_isNotFound() {
        StageResult r = stage.apply("{\"a\":\"text\"}", Map.of("paths", List.of("a.b")), guard);

        assertThat(r.output()).isEqualTo("{}");
        assertThat(r.note()).isEqualTo("path not found: a.b");
    }

    @Test
    void validate_rejectsMissingEmptyWrongTypeAndMalformedPaths() {
        assertThatThrownBy(() -> stage.validate(Map.of()))
                .isInstanceOf(CompressionException.class).hasMessage("Stage json.select: 'paths' needs at least one path");
        assertThatThrownBy(() -> stage.validate(Map.of("paths", List.of())))
                .isInstanceOf(CompressionException.class).hasMessageContaining("at least one path");
        assertThatThrownBy(() -> stage.validate(Map.of("paths", "a")))
                .isInstanceOf(CompressionException.class).hasMessageContaining("list of texts");
        assertThatThrownBy(() -> stage.validate(Map.of("paths", List.of("a."))))
                .isInstanceOf(CompressionException.class).hasMessageContaining("invalid path 'a.'");
        assertThatThrownBy(() -> stage.validate(Map.of("paths", List.of(".a"))))
                .isInstanceOf(CompressionException.class).hasMessageContaining("invalid path");
    }
}
