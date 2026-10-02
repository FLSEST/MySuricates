package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextStagesTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));

    @Test
    void normalize_removesAnsiCrTrailingSpaceAndBlankRuns() {
        String input = "\n\n\u001B[31mERROR\u001B[0m  \r\nzeile\r\n\n\n\nende   \n\n";

        StageResult r = new TextNormalizeStage().apply(input, Map.of(), guard);

        assertThat(r.output()).isEqualTo("ERROR\nzeile\n\nende");
        assertThat(r.note()).startsWith("−");
    }

    @Test
    void normalize_emptyInput() {
        assertThat(new TextNormalizeStage().apply("", Map.of(), guard).output()).isEmpty();
    }

    @Test
    void truncate_shortInputUnchanged_noNote() {
        StageResult r = new TextTruncateStage().apply("kurz", Map.of("max_chars", 100), guard);

        assertThat(r.output()).isEqualTo("kurz");
        assertThat(r.note()).isNull();
    }

    @Test
    void truncate_headTail_respectsBudgetIncludingNotice_andKeepsMoreTail() {
        String input = "A".repeat(5000) + "\n" + "Z".repeat(5000);

        String out = TextTruncateStage.truncate(input, 1000, TextTruncateStage.Keep.HEAD_TAIL);

        assertThat(out.length()).isLessThanOrEqualTo(1000);
        assertThat(out).contains("chars omitted");
        long as = out.chars().filter(c -> c == 'A').count();
        long zs = out.chars().filter(c -> c == 'Z').count();
        assertThat(zs).isGreaterThan(as);
    }

    @Test
    void truncate_neverSplitsSurrogatePairs() {
        String input = "😀".repeat(3000);

        for (TextTruncateStage.Keep keep : TextTruncateStage.Keep.values()) {
            String out = TextTruncateStage.truncate(input, 501, keep);
            assertThat(out.length()).isLessThanOrEqualTo(501);
            String withoutNotice = out.replaceAll("\\n\\[… [\\d,]+ chars omitted …]\\n", "");
            assertThat(withoutNotice.codePoints().allMatch(cp -> cp == 0x1F600)).as(keep.name()).isTrue();
        }
    }

    @Test
    void truncate_prefersLineBoundaries() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("zeile ").append(i).append('\n');

        String out = TextTruncateStage.truncate(sb.toString(), 400, TextTruncateStage.Keep.HEAD);

        assertThat(out).startsWith("zeile 0\n");
        assertThat(out.substring(0, out.indexOf("\n[…"))).endsWith("\n");
    }

    @Test
    void truncate_validation() {
        assertThatThrownBy(() -> new TextTruncateStage().validate(Map.of("max_chars", 10)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("max_chars");
        assertThatThrownBy(() -> new TextTruncateStage().validate(Map.of("max_chars", 500, "keep", "mitte")))
                .isInstanceOf(CompressionException.class).hasMessageContaining("keep");
    }
}
