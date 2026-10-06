package com.automation.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PageSuggestionDefaultsTest {

    /** Pages with their own suggestions; all others (e.g. {@code dashboard}) get the general ones. */
    private static final List<String> PAGES = List.of("run", "workflow", "workflows", "runs", "todos", "project",
            "projects", "schedules", "activity", "insights", "wishes", "factory", "chains");

    /** Upper limit that {@link PageSuggestionService} also applies to generated suggestions. */
    private static final int MAX_SUGGESTIONS = 4;

    @Test
    void jedeSeiteHatEigeneVorschlaegeInBeidenSprachenGleichViele() {
        List<String> generalDe = PageSuggestionDefaults.forPage("dashboard", "de");
        List<String> generalEn = PageSuggestionDefaults.forPage("dashboard", "en");

        for (String page : PAGES) {
            List<String> de = PageSuggestionDefaults.forPage(page, "de");
            List<String> en = PageSuggestionDefaults.forPage(page, "en");

            assertThat(de).as(page).isNotEmpty().hasSizeLessThanOrEqualTo(MAX_SUGGESTIONS)
                    .doesNotHaveDuplicates().isNotEqualTo(generalDe).hasSameSizeAs(en);
            assertThat(en).as(page).isNotEqualTo(generalEn).isNotEqualTo(de);
            assertThat(de).as(page).allSatisfy(s -> assertThat(s).isNotBlank());
        }
    }

    @Test
    void seitenUnterscheidenSichVoneinander() {
        Set<List<String>> distinct = new HashSet<>();
        PAGES.forEach(page -> distinct.add(PageSuggestionDefaults.forPage(page, "en")));

        assertThat(distinct).hasSameSizeAs(PAGES);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dashboard", "", "unbekannt", "RUN"})
    void unbekannteSeiteBekommtAllgemeineVorschlaege(String page) {
        assertThat(PageSuggestionDefaults.forPage(page, "en"))
                .containsExactly("What ran today?", "Are there any problems?", "What is coming up?");
        assertThat(PageSuggestionDefaults.forPage(page, "de"))
                .containsExactly("Was lief heute?", "Gibt es Probleme?", "Was steht an?");
    }

    @Test
    void englischNurBeiGenauEn() {
        assertThat(PageSuggestionDefaults.forPage("run", "en")).first().isEqualTo("Explain this run");
        assertThat(PageSuggestionDefaults.forPage("run", "de")).first().isEqualTo("Erkläre diesen Run");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"de", "fr", "EN", "en-US"})
    void jedeAndereSpracheGiltAlsDeutsch(String lang) {
        // The service normalizes the locale to en/de beforehand; anything deviating from that gets German.
        assertThat(PageSuggestionDefaults.forPage("todos", lang))
                .containsExactly("Was ist dringend?", "Priorisiere meine Todos");
    }
}
