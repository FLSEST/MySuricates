package com.automation.executor;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Getter
@Builder
public class ApprovalRequest {
    private final String blockId;
    private final String question;
    private final List<String> options;
    private final int stepIndex;

    /** Die {@link #options}, die eine Ablehnung sind (siehe {@link ApprovalRejection}) — für die UI. */
    @Builder.Default
    private final List<String> rejectOptions = List.of();

    /** Seit wann der Lauf wartet — der Request entsteht unmittelbar vor dem Warten. */
    @Builder.Default
    private final Instant requestedAt = Instant.now();

    /** Nach wie vielen Minuten der wartende Block aufgibt; {@code null}, wenn unbekannt. */
    private final Integer timeoutMinutes;

    // Human-input specific fields
    @Builder.Default
    private final String type = "approval";
    private final String inputType;
    private final String defaultValue;
    private final String variableName;
    private final boolean required;
    private final String validationPattern;

    /**
     * Multi-field human-input forms. When non-null and non-empty the dialog renders
     * a form with one input per entry; the answer is expected as a JSON object map
     * "{ "<variable_name>": "<value>", ... }". Each entry's keys are the same as
     * config keys: variable_name (required), label, input_type (text/textarea/number/
     * select/checkbox/date), required, default_value, options (for select), placeholder,
     * description, validation_pattern.
     */
    private final List<Map<String, Object>> fields;

    /**
     * Die Antwort so, wie der wartende Block sie bekommt. Ein Approval nimmt nur eine seiner Optionen
     * an — ohne Groß-/Kleinschreibung verglichen, weitergereicht in der Schreibweise der Option. Ohne
     * diese Prüfung wurde jeder Freitext stillschweigend zur Freigabe (TODO 50c7b5d5). Human-Input
     * bleibt frei: Er erwartet Text, Zahlen oder ein JSON-Formular.
     *
     * @throws IllegalArgumentException wenn ein Approval die Antwort nicht anbietet
     */
    public String acceptedAnswer(String answer) {
        if (!"approval".equals(type) || options == null || options.isEmpty() || answer == null) {
            return answer;
        }
        String trimmed = answer.strip();
        return options.stream()
                .filter(o -> o.strip().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Answer '" + answer + "' is not one of the offered options " + options));
    }
}
