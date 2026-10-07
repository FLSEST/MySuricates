package com.automation.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Result of a tool call. A failure the model can act on (unknown id, invalid argument, refused start)
 * is a result with {@code isError}, not a protocol error — the model reads it and can correct the call.
 *
 * @param data    structured answer, sent as {@code structuredContent} and as JSON text; null on error
 * @param message error text; null on success
 */
public record McpToolResult(ObjectNode data, String message) {

    public static McpToolResult ok(ObjectNode data) {
        return new McpToolResult(data, null);
    }

    /** Wraps a non-object answer: {@code structuredContent} must be an object. */
    public static McpToolResult ok(String key, JsonNode value) {
        ObjectNode data = JsonNodeFactory.instance.objectNode();
        data.set(key, value);
        return ok(data);
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(null, message);
    }

    public boolean isError() {
        return message != null;
    }

    /** The {@code result} of {@code tools/call}. */
    ObjectNode toJson() {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ObjectNode text = result.putArray("content").addObject();
        text.put("type", "text");
        if (isError()) {
            text.put("text", message);
            result.put("isError", true);
        } else {
            text.put("text", data.toPrettyString());
            result.set("structuredContent", data);
            result.put("isError", false);
        }
        return result;
    }
}
