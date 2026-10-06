package com.automation.service.workflow;

import com.automation.service.system.SystemSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Work preferences for AI prompts - and only those (TODO d49fbde0).
 *
 * <p><b>What may go in here and what may not.</b> Maintainer decision (2026-09-15,
 * TODO d49fbde0): only <em>work</em> preferences may go into an AI prompt -
 * answer language, answer style, tech stack and tools. Explicitly not: names, contact data,
 * CV, professional profile. That is why there is a fixed list of three keys here and no
 * free profile object: what is not provided for cannot be sent along by accident.
 * An additional key is a deliberate code change, not a configuration question.
 *
 * <p><b>Opt-in per workflow.</b> The block only appears if a workflow requests it via
 * {@code metadata.userPreferences = true} - see
 * {@link AgentInstructionsService#buildInstructionPrefix}. Without the flag the output is
 * byte-identical to before.
 *
 * <p>The values live as runtime settings in {@code app_settings} and therefore need no
 * migration; they can be maintained via {@code PUT /api/v1/system/settings/{key}}.
 */
@Service
@RequiredArgsConstructor
public class UserPreferencesService {

    /** Setting key -> label in the prompt. The order is the order of the output. */
    static final Map<String, String> KEYS = new LinkedHashMap<>(Map.of());

    static {
        KEYS.put("user-pref-language", "Antwortsprache");
        KEYS.put("user-pref-style", "Antwortstil");
        KEYS.put("user-pref-stack", "Tech-Stack und Werkzeuge");
    }

    /** A single value must not dominate the prompt. */
    private static final int MAX_VALUE_LENGTH = 300;

    private final SystemSettingsService settings;

    /**
     * Does this workflow request the work preferences? {@code metadata.userPreferences: true}.
     *
     * <p>Opt-in and not the default because a preference block can change the output of a workflow
     * - for a check or measurement workflow that is undesirable, there the
     * result should depend on the facts only.
     */
    public static boolean requestedBy(Map<String, Object> workflowMetadata) {
        if (workflowMetadata == null) {
            return false;
        }
        Object flag = workflowMetadata.get("userPreferences");
        if (flag instanceof Boolean bool) {
            return bool;
        }
        if (flag instanceof Map<?, ?> map) {   // {"enabled": true} as with autoTodo/audit
            return Boolean.parseBoolean(String.valueOf(map.get("enabled")));
        }
        return Boolean.parseBoolean(String.valueOf(flag));
    }

    /**
     * The preference block for the system prompt, or an empty string if nothing is set.
     *
     * <p>Empty really means empty: a section with the heading and nothing below it would tempt the
     * model to ask follow-up questions, and a placeholder like "no information" is a statement nobody
     * made.
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
