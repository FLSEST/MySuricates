package com.automation.service.mcp;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One tool of the MCP server: what {@code tools/list} announces and the code {@code tools/call} runs.
 *
 * <p>The hints tell the client how careful to be: a read-only tool may run without asking, a destructive
 * one (overwrites, cancels, starts a workflow that can do anything) should be confirmed by the user.
 * They are hints for the client, not a guard — every call still goes through the platform's services.
 *
 * @param readOnly    changes nothing on the instance
 * @param destructive may overwrite or end something (only meaningful when not read-only)
 */
public record McpTool(String name, String title, String description, ObjectNode inputSchema,
                      boolean readOnly, boolean destructive, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        McpToolResult call(McpArgs args);
    }

    public static McpTool reading(String name, String title, String description, ObjectNode inputSchema,
                                  Handler handler) {
        return new McpTool(name, title, description, inputSchema, true, false, handler);
    }

    public static McpTool writing(String name, String title, String description, ObjectNode inputSchema,
                                  boolean destructive, Handler handler) {
        return new McpTool(name, title, description, inputSchema, false, destructive, handler);
    }

    /** The entry of {@code tools/list}. */
    ObjectNode definition() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("name", name);
        node.put("title", title);
        node.put("description", description);
        node.set("inputSchema", inputSchema);
        ObjectNode annotations = node.putObject("annotations");
        annotations.put("title", title);
        annotations.put("readOnlyHint", readOnly);
        if (!readOnly) {
            annotations.put("destructiveHint", destructive);
        }
        // Everything happens on this instance; no tool reaches out to the open web by itself.
        annotations.put("openWorldHint", false);
        return node;
    }
}
