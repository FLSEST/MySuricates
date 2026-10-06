package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record ChainEntryRequest(
        @NotBlank String workflowId,
        String label,
        int sortOrder,
        Boolean stopOnFailure,
        Map<String, Object> inputContext,
        /** Wait time of the chain for this entry in minutes (5–1440); without a value the global default. */
        Integer timeoutMinutes
) {
    /** Aufrufe von vor {@code timeoutMinutes} (TODO cc0eb3ba). */
    public ChainEntryRequest(String workflowId, String label, int sortOrder, Boolean stopOnFailure,
                             Map<String, Object> inputContext) {
        this(workflowId, label, sortOrder, stopOnFailure, inputContext, null);
    }
}
