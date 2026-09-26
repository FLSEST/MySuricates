package com.automation.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ChainResponse(
        String id,
        String name,
        String description,
        String icon,
        List<ChainEntryResponse> entries,
        boolean stopOnFailure,
        long runCount,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    public record ChainEntryResponse(
            String workflowId,
            String workflowName,
            String projectName,
            String label,
            int sortOrder,
            boolean stopOnFailure,
            Map<String, Object> inputContext,
            /** Wartezeit der Kette auf diesen Eintrag in Minuten; null = globale Vorgabe (TODO cc0eb3ba). */
            Integer timeoutMinutes
    ) {
        /** Aufrufe von vor {@code timeoutMinutes} (TODO cc0eb3ba). */
        public ChainEntryResponse(String workflowId, String workflowName, String projectName, String label,
                                  int sortOrder, boolean stopOnFailure, Map<String, Object> inputContext) {
            this(workflowId, workflowName, projectName, label, sortOrder, stopOnFailure, inputContext, null);
        }
    }
}
