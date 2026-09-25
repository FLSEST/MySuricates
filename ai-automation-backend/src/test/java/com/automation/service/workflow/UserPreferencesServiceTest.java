package com.automation.service.workflow;

import com.automation.service.system.SystemIdentityService;
import com.automation.service.system.SystemSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Arbeitspräferenzen im System-Prompt (TODO d49fbde0).
 *
 * <p>Die Entscheidung vom 2026-09-15 zieht eine Grenze: Nur Arbeitspräferenzen dürfen in einen
 * KI-Prompt, keine Namen, Kontaktdaten oder Berufsprofile. Diese Tests halten beide Hälften fest —
 * dass die drei erlaubten Werte ankommen, und dass ohne Opt-in nichts am Prompt anders ist.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPreferencesServiceTest {

    @Mock private SystemSettingsService settings;
    @Mock private SystemIdentityService identity;

    private UserPreferencesService preferences;
    private AgentInstructionsService instructions;

    @BeforeEach
    void setUp() {
        preferences = new UserPreferencesService(settings);
        instructions = new AgentInstructionsService(identity, preferences);
        when(identity.getAgentIdentityBlock()).thenReturn("IDENTITY\n");
        when(settings.getSetting(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
    }

    private void set(String key, String value) {
        when(settings.getSetting(key)).thenReturn(Optional.of(value));
    }

    // --- der Block selbst -------------------------------------------------------------

    @Test
    void rendersTheThreeAllowedPreferences() {
        set("user-pref-language", "Deutsch");
        set("user-pref-style", "knapp, keine Aufzählungen ohne Not");
        set("user-pref-stack", "Java 21, Spring Boot, React");

        String block = preferences.block();

        assertThat(block)
                .contains("## Arbeitspräferenzen")
                .contains("- Antwortsprache: Deutsch")
                .contains("- Antwortstil: knapp, keine Aufzählungen ohne Not")
                .contains("- Tech-Stack und Werkzeuge: Java 21, Spring Boot, React");
    }

    @Test
    void nothingSet_yieldsAnEmptyBlockAndNotAnEmptyHeading() {
        assertThat(preferences.block())
                .as("eine Überschrift ohne Inhalt verleitet das Modell zu Rückfragen")
                .isEmpty();
    }

    @Test
    void onlyOneSet_yieldsOnlyThatLine() {
        set("user-pref-language", "Deutsch");

        assertThat(preferences.block())
                .contains("- Antwortsprache: Deutsch")
                .doesNotContain("Antwortstil")
                .doesNotContain("Tech-Stack");
    }

    @Test
    void blankValuesCountAsUnset() {
        set("user-pref-style", "   ");

        assertThat(preferences.block()).isEmpty();
    }

    @Test
    void aLongValueIsShortenedSoItCannotDominateThePrompt() {
        set("user-pref-stack", "x".repeat(500));

        assertThat(preferences.block()).contains("[…]");
        assertThat(preferences.block().length()).isLessThan(500);
    }

    @Test
    void newlinesAreFoldedSoOneValueStaysOneLine() {
        set("user-pref-style", "knapp\n## Neue Überschrift\n- und eine Liste");

        assertThat(preferences.block())
                .as("sonst kann ein Wert eigene Abschnitte in den Prompt schreiben")
                .contains("- Antwortstil: knapp ## Neue Überschrift - und eine Liste")
                .doesNotContain("\n## Neue");
    }

    /**
     * Die Grenze der Entscheidung: Es gibt keinen Weg, etwas anderes als die drei Schlüssel
     * hineinzubekommen. Ein Berufsprofil in einem beliebigen anderen Setting bleibt draußen.
     */
    @Test
    void anySettingOutsideTheThreeKeysNeverReachesThePrompt() {
        set("user-pref-language", "Deutsch");
        set("onboarding-profile", "Lebenslauf: Alex, geboren ...");
        set("user-name", "Klarname");
        set("user-pref-cv", "Berufsprofil");

        String block = preferences.block();

        assertThat(block).contains("Deutsch");
        assertThat(block).doesNotContain("Lebenslauf", "Klarname", "Berufsprofil");
        assertThat(UserPreferencesService.KEYS).containsOnlyKeys(
                "user-pref-language", "user-pref-style", "user-pref-stack");
    }

    // --- Opt-in pro Workflow ----------------------------------------------------------

    @Test
    void metadataFlagDecides() {
        assertThat(UserPreferencesService.requestedBy(Map.of("userPreferences", true))).isTrue();
        assertThat(UserPreferencesService.requestedBy(Map.of("userPreferences", "true"))).isTrue();
        assertThat(UserPreferencesService.requestedBy(
                Map.of("userPreferences", Map.of("enabled", true)))).isTrue();
        assertThat(UserPreferencesService.requestedBy(Map.of("userPreferences", false))).isFalse();
        assertThat(UserPreferencesService.requestedBy(Map.of())).isFalse();
        assertThat(UserPreferencesService.requestedBy(null)).isFalse();
    }

    @Test
    void withoutOptIn_thePromptIsByteIdenticalToBefore() {
        set("user-pref-language", "Deutsch");

        String withoutFlag = instructions.buildInstructionPrefix("Workflow-Hinweis");
        String explicitlyOff = instructions.buildInstructionPrefix("Workflow-Hinweis", false);

        assertThat(withoutFlag)
                .as("ein Workflow, der nichts anfordert, darf keinen geänderten Prompt sehen")
                .doesNotContain("Arbeitspräferenzen")
                .isEqualTo(explicitlyOff);
    }

    @Test
    void withOptIn_theBlockSitsBeforeTheWorkflowInstructions() {
        set("user-pref-language", "Deutsch");

        String prompt = instructions.buildInstructionPrefix("Workflow-Hinweis", true);

        assertThat(prompt).contains("## Arbeitspräferenzen");
        assertThat(prompt.indexOf("## Arbeitspräferenzen"))
                .as("die workflow-eigenen Hinweise stehen zuletzt und wiegen damit am schwersten")
                .isLessThan(prompt.indexOf("## Workflow-Specific Instructions"));
    }

    @Test
    void withOptInButNothingSet_thePromptStaysUnchanged() {
        assertThat(instructions.buildInstructionPrefix("Hinweis", true))
                .isEqualTo(instructions.buildInstructionPrefix("Hinweis", false));
    }
}
