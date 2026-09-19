package com.automation.service.workflow;

import com.automation.service.system.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Arbeitspräferenzen für KI-Prompts — und nur die (TODO d49fbde0).
 *
 * <p><b>Was hier hinein darf und was nicht.</b> Entscheidung Flo (2026-09-15): In einen KI-Prompt
 * dürfen ausschließlich <em>Arbeits</em>präferenzen — Antwortsprache, Antwortstil, Tech-Stack und
 * Werkzeuge. Ausdrücklich nicht: Namen, Kontaktdaten, Lebenslauf, Berufsprofil. Deshalb gibt es
 * hier eine feste Liste von drei Schlüsseln und kein freies Profil-Objekt: Was nicht vorgesehen
 * ist, kann auch nicht versehentlich mitgeschickt werden. Ein zusätzlicher Schlüssel ist eine
 * bewusste Code-Änderung, keine Konfigurationsfrage.
 *
 * <p><b>Opt-in pro Workflow.</b> Der Block entsteht nur, wenn ein Workflow ihn über
 * {@code metadata.userPreferences = true} anfordert — siehe
 * {@link AgentInstructionsService#buildInstructionPrefix}. Ohne das Flag ist die Ausgabe
 * byte-identisch zu vorher.
 *
 * <p>Die Werte liegen als Laufzeit-Settings in {@code app_settings} und brauchen daher keine
 * Migration; sie sind über {@code PUT /api/v1/system/settings/{key}} pflegbar.
 */
@Service
@RequiredArgsConstructor
public class UserPreferencesService {

    /** Setting-Schlüssel -> Beschriftung im Prompt. Die Reihenfolge ist die der Ausgabe. */
    static final Map<String, String> KEYS = new LinkedHashMap<>(Map.of());

    static {
        KEYS.put("user-pref-language", "Antwortsprache");
        KEYS.put("user-pref-style", "Antwortstil");
        KEYS.put("user-pref-stack", "Tech-Stack und Werkzeuge");
    }

    /** Ein einzelner Wert darf den Prompt nicht dominieren. */
    private static final int MAX_VALUE_LENGTH = 300;

    private final SystemSettingsService settings;

    /**
     * Fordert dieser Workflow die Arbeitspräferenzen an? {@code metadata.userPreferences: true}.
     *
     * <p>Opt-in und nicht Voreinstellung, weil ein Präferenz-Block die Ausgabe eines Workflows
     * verändern kann — bei einem Prüf- oder Messworkflow ist das unerwünscht, dort soll das
     * Ergebnis nur von den Fakten abhängen.
     */
    public static boolean requestedBy(Map<String, Object> workflowMetadata) {
        if (workflowMetadata == null) {
            return false;
        }
        Object flag = workflowMetadata.get("userPreferences");
        if (flag instanceof Boolean bool) {
            return bool;
        }
        if (flag instanceof Map<?, ?> map) {   // {"enabled": true} wie bei autoTodo/audit
            return Boolean.parseBoolean(String.valueOf(map.get("enabled")));
        }
        return Boolean.parseBoolean(String.valueOf(flag));
    }

    /**
     * Der Präferenz-Block für den System-Prompt, oder ein leerer String, wenn nichts gesetzt ist.
     *
     * <p>Leer heißt wirklich leer: Ein Abschnitt mit der Überschrift und nichts darunter würde das
     * Modell zu Rückfragen verleiten, und ein Platzhalter wie „keine Angabe" ist eine Aussage, die
     * niemand getroffen hat.
     */
    public String block() {
        StringBuilder out = new StringBuilder();
        KEYS.forEach((key, label) -> settings.getSetting(key)
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .ifPresent(value -> out.append("- ").append(label).append(": ")
                        .append(shorten(value)).append('\n')));
        if (out.isEmpty()) {
            return "";
        }
        return "## Arbeitspräferenzen\n"
                + "Bevorzugte Arbeitsweise des Nutzers. Beeinflusst Form und Werkzeugwahl,"
                + " nie den Inhalt einer Messung oder Bewertung.\n"
                + out;
    }

    private static String shorten(String value) {
        String single = value.replaceAll("\\s+", " ").strip();
        return single.length() <= MAX_VALUE_LENGTH
                ? single
                : single.substring(0, MAX_VALUE_LENGTH) + " […]";
    }
}
