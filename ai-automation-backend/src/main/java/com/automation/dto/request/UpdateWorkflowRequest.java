package com.automation.dto.request;

import com.automation.entity.BlockDefinition;

import java.util.List;
import java.util.Map;

/**
 * Partial update of a workflow. Who made the change is not part of the request — callers pass a
 * server-determined {@link com.automation.entity.ChangeSource} to {@code WorkflowService.update}.
 *
 * @param version the workflow's version label (e.g. the pool version {@code 4.4.0}); {@code null}
 *                leaves it unchanged. A label, not part of the definition: no version row, no hash,
 *                allowed on sealed workflows.
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
        String effort,
        String version
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
                                  String changeMessage) {
        this(name, description, icon, blocks, instructions, model, provider,
                metadata, defaultContext, changeMessage, null, null, null, null);
    }

    /** Backwards-compat constructor for call-sites written before the version label was writable. */
    public UpdateWorkflowRequest(String name, String description, String icon,
                                  List<BlockDefinition> blocks, String instructions,
                                  String model, String provider,
                                  Map<String, Object> metadata, Map<String, Object> defaultContext,
                                  String changeMessage, List<String> tags, String category, String effort) {
        this(name, description, icon, blocks, instructions, model, provider,
                metadata, defaultContext, changeMessage, tags, category, effort, null);
    }
}
