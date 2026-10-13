package com.automation.service.mcp;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * One tool of the MCP server: what {@code tools/list} announces and the code {@code tools/call} runs.
 *
 * <p>The hints tell the client how careful to be: a read-only tool may run without asking, a destructive
 * one (overwrites, cancels, starts a workflow that can do anything) should be confirmed by the user.
 * They are hints for the client, not a guard — every call still goes through the platform's services.
 * The kind is chosen by the factory's name ({@link #reading}, {@link #writing}, {@link #destructive}),
 * never by a bare flag after a long schema, where a wrong {@code false} would go unnoticed in review.
 *
 * @param effect what a call does to the instance; becomes {@code readOnlyHint} and {@code destructiveHint}
 */
public record McpTool(String name, String title, String description, ObjectNode inputSchema,
                      Effect effect, Handler handler) {

    /** What a call of the tool does to the instance. */
    public enum Effect {
        /** Changes nothing. */
        READ_ONLY,
        /** Adds or changes something without overwriting or ending what exists. */
        WRITING,
        /** May overwrite or end something, or start work that can do anything. */
        DESTRUCTIVE
    }

    @FunctionalInterface
    public interface Handler {
        McpToolResult call(McpArgs args);
    }

    public McpTool {
        // No silent default: a tool without an effect would be announced as a non-destructive write.
        Objects.requireNonNull(effect, "effect");
    }

    /** A tool that changes nothing on the instance. */
    public static McpTool reading(String name, String title, String description, ObjectNode inputSchema,
                                  Handler handler) {
        return new McpTool(name, title, description, inputSchema, Effect.READ_ONLY, handler);
    }

    /** A tool that adds or changes something but neither overwrites nor ends what exists. */
    public static McpTool writing(String name, String title, String description, ObjectNode inputSchema,
                                  Handler handler) {
        return new McpTool(name, title, description, inputSchema, Effect.WRITING, handler);
    }

    /** A tool that may overwrite or end something, or start work that can do anything: the client should ask. */
    public static McpTool destructive(String name, String title, String description, ObjectNode inputSchema,
                                      Handler handler) {
        return new McpTool(name, title, description, inputSchema, Effect.DESTRUCTIVE, handler);
    }

    /** Changes nothing on the instance. */
    public boolean readOnly() {
        return effect == Effect.READ_ONLY;
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
        annotations.put("readOnlyHint", readOnly());
        if (!readOnly()) {
            annotations.put("destructiveHint", effect == Effect.DESTRUCTIVE);
        }
        // Everything happens on this instance; no tool reaches out to the open web by itself.
        annotations.put("openWorldHint", false);
        return node;
    }
}
