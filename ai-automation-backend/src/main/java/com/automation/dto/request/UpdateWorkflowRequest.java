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
                metadata, defaultContext, null, null, null, null, null);
    }

    public UpdateWorkflowRequest(String name, String description, String icon,
                                  List<BlockDefinition> blocks, String instructions,
                                  String model, String provider,
                                  Map<String, Object> metadata, Map<String, Object> defaultContext,
                                  String changeSource, String changeMessage) {
        this(name, description, icon, blocks, instructions, model, provider,
                metadata, defaultContext, changeSource, changeMessage, null, null, null);
    }

    /** Backwards-compat constructor for call-sites written before effort support existed. */
    public UpdateWorkflowRequest(String name, String description, String icon,
                                  List<BlockDefinition> blocks, String instructions,
                                  String model, String provider,
                                  Map<String, Object> metadata, Map<String, Object> defaultContext,
                                  String changeSource, String changeMessage,
                                  List<String> tags, String category) {
        this(name, description, icon, blocks, instructions, model, provider,
                metadata, defaultContext, changeSource, changeMessage, tags, category, null);
    }
}
