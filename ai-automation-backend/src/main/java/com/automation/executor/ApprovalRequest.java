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

    /** The {@link #options} that are a rejection (see {@link ApprovalRejection}) - for the UI. */
    @Builder.Default
    private final List<String> rejectOptions = List.of();

    /** Since when the run has been waiting - the request is created immediately before waiting. */
    @Builder.Default
    private final Instant requestedAt = Instant.now();

    /** After how many minutes the waiting block gives up; {@code null} if unknown. */
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
     * The answer as the waiting block receives it. An approval accepts only one of its options
     * - compared case-insensitively, passed on in the option's spelling. Without
     * this check any free text silently became an approval (TODO 50c7b5d5). Human input
     * stays free: it expects text, numbers or a JSON form.
     *
     * @throws IllegalArgumentException if an approval does not offer the answer
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
