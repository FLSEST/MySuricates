package com.automation.executor;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

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
}
