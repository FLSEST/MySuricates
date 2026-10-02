package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonLimitStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private final JsonLimitStage stage = new JsonLimitStage();

    @Test
    void keepsFirstEntries_ofRootArray() {
        StageResult r = stage.apply("[1,2,3,4]", Map.of("count", 2), guard);

        assertThat(r.output()).isEqualTo("[1,2]");
        assertThat(r.note()).isEqualTo("−2 entries");
    }

    @Test
    void countZero_emptiesArray_restOfDocumentStays() {
        StageResult r = stage.apply("{\"n\":1,\"a\":{\"items\":[1,2,3]}}", Map.of("path", "a.items", "count", 0), guard);

        assertThat(r.output()).isEqualTo("{\"n\":1,\"a\":{\"items\":[]}}");
        assertThat(r.note()).isEqualTo("−3 entries");
    }

    @Test
    void countAboveSize_isNoOp() {
        StageResult r = stage.apply("[1]", Map.of("count", 10), guard);

        assertThat(r.output()).isEqualTo("[1]");
        assertThat(r.note()).isEqualTo("−0 entries");
    }

    @Test
    void validate_countRequiredAndNonNegative() {
        assertThatThrownBy(() -> stage.validate(Map.of()))
                .isInstanceOf(CompressionException.class).hasMessage("Stage json.limit: 'count' is missing");
        assertThatThrownBy(() -> stage.validate(Map.of("count", -1)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("between 0 and 1,000,000");
        assertThatThrownBy(() -> stage.validate(Map.of("count", 1, "path", 5)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'path' must be text");
        stage.validate(Map.of("count", 0));
    }
}
