package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinesReplaceStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private final LinesReplaceStage stage = new LinesReplaceStage();

    @Test
    void anchorsApplyPerLine_andGroupsAreSubstituted() {
        StageResult r = stage.apply("id=1 id=2\nkeine\nid=3", Map.of("pattern", "id=(\\d)", "replacement", "#$1"), guard);

        assertThat(r.output()).isEqualTo("#1 #2\nkeine\n#3");
        assertThat(r.note()).isEqualTo("2 lines changed");
        assertThat(stage.apply("ab\nab", Map.of("pattern", "^a"), guard).output())
                .as("^ greift je Zeile, replacement default leer").isEqualTo("b\nb");
    }

    @Test
    void escapedDollar_isLiteral() {
        assertThat(stage.apply("preis 5", Map.of("pattern", "\\d", "replacement", "\\$"), guard).output())
                .isEqualTo("preis $");
    }

    @Test
    void noMatch_keepsInput_andReportsZero() {
        StageResult r = stage.apply("a\n\nb", Map.of("pattern", "z", "replacement", "y"), guard);

        assertThat(r.output()).isEqualTo("a\n\nb");
        assertThat(r.note()).isEqualTo("0 lines changed");
    }

    @Test
    void validate_patternRequiredAndValid_replacementMustBeText() {
        assertThatThrownBy(() -> stage.validate(Map.of()))
                .isInstanceOf(CompressionException.class).hasMessage("Stage lines.replace: 'pattern' is missing");
        assertThatThrownBy(() -> stage.validate(Map.of("pattern", "[offen")))
                .isInstanceOf(CompressionException.class).hasMessageContaining("invalid regular expression");
        assertThatThrownBy(() -> stage.validate(Map.of("pattern", "a", "replacement", 1)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("'replacement' must be text");
    }
}
