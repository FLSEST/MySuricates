package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LineStagesTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));

    @Test
    void filter_excludeOnly_removesSilently() {
        String input = "Exception x\n\tat a.B(B.java:1)\n\tat c.D(D.java:2)\nweiter";

        StageResult r = new LinesFilterStage().apply(input, Map.of("exclude", List.of("^\\s+at ")), guard);

        assertThat(r.output()).isEqualTo("Exception x\nweiter");
        assertThat(r.note()).isEqualTo("−2 lines");
    }

    @Test
    void filter_includeWithContext_marksGaps() {
        String input = "a\nb\nERROR eins\nc\nd\ne\nf\nERROR zwei\ng";

        StageResult r = new LinesFilterStage().apply(input,
                Map.of("include", List.of("ERROR"), "before", 1, "after", 1), guard);

        assertThat(r.output()).isEqualTo("[… 1 lines …]\nb\nERROR eins\nc\n[… 2 lines …]\nf\nERROR zwei\ng");
    }

    @Test
    void filter_noHits_isEmptyWithNote_notAnError() {
        StageResult r = new LinesFilterStage().apply("a\nb", Map.of("include", List.of("ERROR")), guard);

        assertThat(r.output()).isEmpty();
        assertThat(r.note()).isEqualTo("no matches");
    }

    @Test
    void filter_validation_needsPatternAndValidRegex() {
        assertThatThrownBy(() -> new LinesFilterStage().validate(Map.of()))
                .isInstanceOf(CompressionException.class).hasMessageContaining("include");
        assertThatThrownBy(() -> new LinesFilterStage().validate(Map.of("include", List.of("(offen"))))
                .isInstanceOf(CompressionException.class).hasMessageContaining("invalid regular expression");
    }

    @Test
    void replace_normalizesTimestamps() {
        String input = "2026-09-14T10:54:03.123Z INFO start\n2026-09-14T10:55:09.999Z INFO start";

        StageResult r = new LinesReplaceStage().apply(input,
                Map.of("pattern", "^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s*", "replacement", ""), guard);

        assertThat(r.output()).isEqualTo("INFO start\nINFO start");
        assertThat(r.note()).isEqualTo("2 lines changed");
    }

    @Test
    void replace_invalidGroupReference_isCompressionException() {
        assertThatThrownBy(() -> new LinesReplaceStage().apply("abc", Map.of("pattern", "b", "replacement", "$3"), guard))
                .isInstanceOf(CompressionException.class).hasMessageContaining("invalid replacement");
    }

    @Test
    void dedupe_globalWithCount_keepsFirstPositionAndBlankLines() {
        String input = "WARN x\nINFO a\nWARN x\n\nWARN x\nINFO b";

        StageResult r = new LinesDedupeStage().apply(input, Map.of("count", true), guard);

        assertThat(r.output()).isEqualTo("3× WARN x\nINFO a\n\nINFO b");
        assertThat(r.note()).isEqualTo("−2 lines");
    }

    @Test
    void dedupe_consecutiveOnly() {
        StageResult r = new LinesDedupeStage().apply("a\na\nb\na", Map.of("consecutive_only", true, "count", true), guard);

        assertThat(r.output()).isEqualTo("2× a\nb\na");
    }

    @Test
    void headAndTail_withNotice() {
        String input = "1\n2\n3\n4\n5";

        assertThat(LinesHeadTailStage.head().apply(input, Map.of("lines", 2), guard).output())
                .isEqualTo("1\n2\n[… 3 lines omitted …]");
        assertThat(LinesHeadTailStage.tail().apply(input, Map.of("lines", 2), guard).output())
                .isEqualTo("[… 3 lines omitted …]\n4\n5");
        assertThat(LinesHeadTailStage.tail().apply(input, Map.of("lines", 9), guard).output()).isEqualTo(input);
    }

    /** TODO 0f376d6a: The trailing line break does not count as a separate (empty) line. */
    @Test
    void headAndTail_trailingNewlineIsNoExtraLine() {
        String input = "a\nb\n";

        assertThat(LinesHeadTailStage.tail().apply(input, Map.of("lines", 1), guard).output())
                .isEqualTo("[… 1 lines omitted …]\nb\n");
        assertThat(LinesHeadTailStage.head().apply(input, Map.of("lines", 1), guard).output())
                .isEqualTo("a\n[… 1 lines omitted …]\n");
        assertThat(LinesHeadTailStage.tail().apply(input, Map.of("lines", 2), guard).output()).isEqualTo(input);
    }

    @Test
    void veryLongSingleLine_isHandled() {
        String input = "x".repeat(200_000);

        StageResult r = new LinesFilterStage().apply(input, Map.of("exclude", List.of("y")), guard);

        assertThat(r.output()).hasSize(200_000);
    }
}
