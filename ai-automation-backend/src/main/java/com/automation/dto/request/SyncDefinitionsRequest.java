package com.automation.dto.request;

import java.util.List;

public record SyncDefinitionsRequest(
        List<String> projectIds,
        List<String> workflowIds,
        List<String> skillIds,
        List<String> chainIds
) {
    /** Backwards-compatible constructor for callers that don't yet pass chainIds. */
    public SyncDefinitionsRequest(List<String> projectIds, List<String> workflowIds, List<String> skillIds) {
        this(projectIds, workflowIds, skillIds, null);
    }
}
