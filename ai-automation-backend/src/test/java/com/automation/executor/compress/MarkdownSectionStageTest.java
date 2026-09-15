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
    void headingMatch_isGuardedByTheDeadline() {
        RegexGuard expired = RegexGuard.startingNow(Duration.ZERO);
        String costlyHeading = "# x" + " ".repeat(900) + "y";

        assertThatThrownBy(() -> new MarkdownSectionStage().apply(costlyHeading, Map.of("headings", List.of("zzz"), "level", 6), expired))
                .isInstanceOf(CompressionException.class).hasMessageContaining("Zeitlimit");
    }

    @Test
    void headingTitle_dropsClosingHashesAndSpaces() {
        StageResult r = new MarkdownSectionStage().apply("## Fazit ##  \ntext\n# Anderes", Map.of("headings", List.of("^Fazit$")), guard);

        assertThat(r.output()).isEqualTo("## Fazit ##  \ntext");
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> new MarkdownSectionStage().validate(Map.of("headings", List.of())))
                .isInstanceOf(CompressionException.class).hasMessageContaining("headings");
        assertThatThrownBy(() -> new MarkdownSectionStage().validate(Map.of("headings", List.of("x"), "level", 9)))
                .isInstanceOf(CompressionException.class).hasMessageContaining("level");
    }
}
