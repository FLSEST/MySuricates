package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextTruncateStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));

    private static String numberedLines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("zeile ").append(i).append('\n');
        return sb.toString();
    }

    @Test
    void cut_omittedIsExactlyTheMissingOriginalChars_forEveryKeep() {
        String input = numberedLines(500);

        for (TextTruncateStage.Keep keep : TextTruncateStage.Keep.values()) {
            TextTruncateStage.Cut cut = TextTruncateStage.cut(input, 600, keep);
            String notice = TextTruncateStage.notice(cut.omitted());

            assertThat(cut.text()).as(keep.name()).contains(notice);
            assertThat(cut.text().length() - notice.length()).as(keep.name()).isEqualTo(input.length() - cut.omitted());
            assertThat(cut.text().length()).as(keep.name()).isLessThanOrEqualTo(600);
        }
    }

    @Test
    void cut_withinBudget_isUnchanged_withZeroOmitted() {
        TextTruncateStage.Cut cut = TextTruncateStage.cut("genau", 5, TextTruncateStage.Keep.HEAD);

        assertThat(cut.text()).isEqualTo("genau");
        assertThat(cut.omitted()).isZero();
    }

    @Test
    void notice_usesGermanDigitGrouping() {
        assertThat(TextTruncateStage.notice(12_345)).isEqualTo("\n[… 12,345 chars omitted …]\n");
    }

    @Test
    void budgetBelowNoticeLength_leavesOnlyTheNotice() {
        TextTruncateStage.Cut cut = TextTruncateStage.cut("abcdefghij", 5, TextTruncateStage.Keep.HEAD_TAIL);

        assertThat(cut.text()).isEqualTo("\n[… 10 chars omitted …]\n");
        assertThat(cut.omitted()).isEqualTo(10);
    }

    @Test
    void tail_startsAtLineBoundary_andKeepsTheEnd() {
        String input = numberedLines(200);

        String out = TextTruncateStage.truncate(input, 400, TextTruncateStage.Keep.TAIL);

        assertThat(out).startsWith("\n[… ").endsWith("zeile 199\n");
        String tail = out.substring(out.indexOf("…]\n") + 3);
        assertThat(tail).startsWith("zeile ");
    }

    @Test
    void apply_keepOptionSelectsSide_andNoteIsSignedDelta() {
        String input = "a".repeat(300) + "q".repeat(300);

        StageResult head = new TextTruncateStage().apply(input, Map.of("max_chars", 200, "keep", "head"), guard);
        StageResult tail = new TextTruncateStage().apply(input, Map.of("max_chars", 200, "keep", "tail"), guard);

        assertThat(head.output()).startsWith("aaa").doesNotContain("q");
        assertThat(tail.output()).endsWith("qqq").doesNotContain("aaa");
        assertThat(head.note()).isEqualTo(CompressionNumbers.signedChars(head.output().length() - input.length()));
    }

    @Test
    void validate_maxCharsMissingNonIntegerOrOutOfRange() {
        TextTruncateStage stage = new TextTruncateStage();

        assertThatThrownBy(() -> stage.validate(Map.of()))
                .isInstanceOf(CompressionException.class).hasMessage("Stage text.truncate: 'max_chars' is missing");
        assertThatThrownBy(() -> stage.validate(Map.of("max_chars", 150.5)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("whole number between 100 and 1,000,000");
        assertThatThrownBy(() -> stage.validate(Map.of("max_chars", 1_000_001)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("max_chars");
        stage.validate(Map.of("max_chars", 100, "keep", "tail"));
    }
}
