package com.automation.executor;

import java.util.List;
import java.util.Locale;

/**
 * Which answer to an approval is a rejection - the one rule for block, validation and UI.
 *
 * <p>Formerly only a literal "No" counted as a rejection. A block with its own options like
 * {@code ["Ja", "Nein"]} and {@code on_no: fail} was silently approved with "Nein" (TODO 50c7b5d5).
 * Now {@code reject_options} names the rejections explicitly; without the key
 * {@link #DEFAULT_REJECT_ANSWERS} apply. Compared case-insensitively and ignoring surrounding whitespace.
 */
public final class ApprovalRejection {

    /** Rejections when a block sets no {@code reject_options}. */
    public static final List<String> DEFAULT_REJECT_ANSWERS = List.of("No", "Nein");

    private ApprovalRejection() {
    }

    /** The rejections from the config value {@code reject_options} - a non-empty list of strings, else the default. */
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

    /** The offered options that are a rejection - so the UI shows them as such without rebuilding the rule. */
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
