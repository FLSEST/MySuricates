package com.automation.dto.response;

import java.util.List;

public record WorkflowDiff(
        List<String> changedFields,
        String modelBefore,
        String modelAfter,
        int blocksAdded,
        int blocksRemoved
) {}
