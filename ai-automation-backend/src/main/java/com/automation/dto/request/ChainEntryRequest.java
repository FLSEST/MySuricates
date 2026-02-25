package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record ChainEntryRequest(
        @NotBlank String workflowId,
        String label,
        int sortOrder,
        Boolean stopOnFailure,
        Map<String, Object> inputContext
) {}
