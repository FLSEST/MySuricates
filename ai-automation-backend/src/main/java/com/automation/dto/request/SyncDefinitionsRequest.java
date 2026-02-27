package com.automation.dto.request;

import java.util.List;

public record SyncDefinitionsRequest(
        List<String> projectIds,
        List<String> workflowIds,
        List<String> skillIds
) {}
