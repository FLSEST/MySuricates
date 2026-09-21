package com.automation.executor;

import java.util.List;
import java.util.Locale;

/**
 * Welche Antwort auf ein Approval eine Ablehnung ist — die eine Regel für Block, Validierung und UI.
 *
 * <p>Früher galt nur ein wörtliches „No“ als Ablehnung. Ein Block mit eigenen Optionen wie
 * {@code ["Ja", "Nein"]} und {@code on_no: fail} wurde mit „Nein“ still freigegeben (TODO 50c7b5d5).
 * Jetzt nennt {@code reject_options} die Ablehnungen ausdrücklich; ohne den Key gelten
 * {@link #DEFAULT_REJECT_ANSWERS}. Verglichen wird ohne Groß-/Kleinschreibung und Rand-Leerzeichen.
 */
public final class ApprovalRejection {

    /** Ablehnungen, wenn ein Block kein {@code reject_options} setzt. */
    public static final List<String> DEFAULT_REJECT_ANSWERS = List.of("No", "Nein");

    private ApprovalRejection() {
    }

    /** Die Ablehnungen aus dem Config-Wert {@code reject_options} — eine nicht-leere Liste von Strings, sonst die Vorgabe. */
    public static List<String> rejectAnswers(Object configured) {
        if (configured instanceof List<?> list) {
            List<String> answers = list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(s -> !s.isBlank())
                    .toList();
            if (!answers.isEmpty()) {
                return answers;
            }
        }
        return DEFAULT_REJECT_ANSWERS;
    }

    public static boolean isRejection(String answer, List<String> rejectAnswers) {
        if (answer == null) {
            return false;
        }
        String normalized = normalize(answer);
        return rejectAnswers.stream().anyMatch(r -> normalize(r).equals(normalized));
    }

    /** Die angebotenen Optionen, die eine Ablehnung sind — damit die UI sie als solche zeigt, ohne die Regel nachzubauen. */
    public static List<String> rejectingOptions(List<String> options, List<String> rejectAnswers) {
        if (options == null) {
            return List.of();
        }
        return options.stream().filter(o -> isRejection(o, rejectAnswers)).toList();
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
