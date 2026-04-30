package com.automation.executor;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
public class ApprovalRequest {
    private final String blockId;
    private final String question;
    private final List<String> options;
    private final int stepIndex;

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
}
