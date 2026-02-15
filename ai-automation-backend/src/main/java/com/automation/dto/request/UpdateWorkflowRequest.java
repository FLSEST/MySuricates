package com.automation.dto.request;

import com.automation.entity.BlockDefinition;

import java.util.List;

public record UpdateWorkflowRequest(
        String name,
        String description,
        String icon,
        List<BlockDefinition> blocks
) {}
