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
}
