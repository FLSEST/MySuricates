package com.automation.executor.compress;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownSectionStageTest {

    private final RegexGuard guard = RegexGuard.startingNow(Duration.ofSeconds(5));
    private static final String REPORT = """
            # Scorecard
            Intro
            ## Gesamtscore
            7,4 (+0,3)
            ### Details
            fein
            ## Dimensionen
            viel Text
            ```
            ## Kein Heading im Code
            ```
            ## Empfehlung
            Tests stabilisieren
            """;

    @Test
    void keepsMatchingSectionsWithSubsections_caseInsensitive() {
        StageResult r = new MarkdownSectionStage().apply(REPORT, Map.of("headings", List.of("gesamtSCORE", "empfehlung")), guard);

        assertThat(r.output()).isEqualTo("## Gesamtscore\n7,4 (+0,3)\n### Details\nfein\n\n## Empfehlung\nTests stabilisieren\n");
        assertThat(r.note()).isEqualTo("2 von 5 Abschnitten");
    }

    @Test
    void headingInsideCodeFenceIsIgnored() {
        StageResult r = new MarkdownSectionStage().apply(REPORT, Map.of("headings", List.of("Kein Heading")), guard);

        assertThat(r.output()).isEmpty();
        assertThat(r.note()).isEqualTo("kein passender Abschnitt");
    }

    @Test
    void levelFilter() {
        StageResult r = new MarkdownSectionStage().apply(REPORT, Map.of("headings", List.of("Details"), "level", 3), guard);

        assertThat(r.output()).isEqualTo("### Details\nfein");
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> new MarkdownSectionStage().validate(Map.of("headings", List.of())))
                .isInstanceOf(CompressionException.class).hasMessageContaining("headings");
        assertThatThrownBy(() -> new MarkdownSectionStage().validate(Map.of("headings", List.of("x"), "level", 9)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("level");
    }
}
