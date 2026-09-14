package com.automation.dto.request;

import com.automation.entity.BlockDefinition;

import java.util.List;
import java.util.Map;

/**
 * Partial update of a workflow. Who made the change is not part of the request — callers pass a
 * server-determined {@link com.automation.entity.ChangeSource} to {@code WorkflowService.update}.
 */
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
        String changeMessage,
        List<String> tags,
        String category,
        String effort
) {
    public UpdateWorkflowRequest(String name, String description, String icon,
                                  List<BlockDefinition> blocks, String instructions,
                                  String model, String provider,
                                  Map<String, Object> metadata, Map<String, Object> defaultContext) {
        this(name, description, icon, blocks, instructions, model, provider,
                metadata, defaultContext, null, null, null, null);
    }

    public UpdateWorkflowRequest(String name, String description, String icon,
                                  List<BlockDefinition> blocks, String instructions,
                                  String model, String provider,
                                  Map<String, Object> metadata, Map<String, Object> defaultContext,
                                  String changeMessage) {
        this(name, description, icon, blocks, instructions, model, provider,
                metadata, defaultContext, changeMessage, null, null, null);
    }
}
