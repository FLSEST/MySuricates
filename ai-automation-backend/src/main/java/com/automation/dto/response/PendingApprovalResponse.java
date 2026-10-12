package com.automation.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Answer of {@code GET /executions/{id}/approval}: nothing pending, a pending approval, or a pending human input.
 * Each record writes exactly the keys the earlier hand-built map wrote — nulls included — so UI, plugin and
 * external agents keep their contract.
 */
public sealed interface PendingApprovalResponse {

    boolean pending();

    /** {@code {"pending": false}}. */
    record None(boolean pending) implements PendingApprovalResponse {
        public None() {
            this(false);
        }
    }

    record Approval(boolean pending, String blockId, String question, List<String> options,
                    List<String> rejectOptions, int stepIndex, String type) implements PendingApprovalResponse {
    }

    /** {@code fields} only when the form has several fields, like the human_input_required socket event. */
    record HumanInput(boolean pending, String blockId, String question, List<String> options,
                      List<String> rejectOptions, int stepIndex, String type, String inputType,
                      String defaultValue, String variableName, boolean required, String validationPattern,
                      @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Map<String, Object>> fields)
            implements PendingApprovalResponse {
    }
}
