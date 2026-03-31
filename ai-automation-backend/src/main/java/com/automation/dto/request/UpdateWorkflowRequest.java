package com.automation.dto.request;

import com.automation.entity.BlockDefinition;

import java.util.List;
import java.util.Map;

public record UpdateWorkflowRequest(
        String name,
        String description,
        String icon,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        String provider,
        Map<String, Object> metadata,
        Map<String, Object> defaultContext,
        String changeSource,
        String changeMessage
) {
    public UpdateWorkflowRequest(String name, String description, String icon,
                                  List<BlockDefinition> blocks, String instructions,
                                  String model, String provider,
                                  Map<String, Object> metadata, Map<String, Object> defaultContext) {
        this(name, description, icon, blocks, instructions, model, provider,
                metadata, defaultContext, null, null);
    }
}
