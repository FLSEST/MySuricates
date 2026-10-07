package com.automation.service.mcp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Small builder for the JSON Schema of a tool's {@code inputSchema}. */
final class McpSchema {

    private final ObjectNode schema = JsonNodeFactory.instance.objectNode();
    private final ObjectNode properties;
    private final ArrayNode required;

    private McpSchema() {
        schema.put("type", "object");
        properties = schema.putObject("properties");
        required = JsonNodeFactory.instance.arrayNode();
    }

    static McpSchema object() {
        return new McpSchema();
    }

    /** A tool without arguments. */
    static ObjectNode none() {
        return object().build();
    }

    McpSchema string(String name, String description, boolean isRequired) {
        return property(name, "string", description, isRequired);
    }

    McpSchema integer(String name, String description, int min, int max) {
        ObjectNode p = add(name, "integer", description);
        p.put("minimum", min);
        p.put("maximum", max);
        return this;
    }

    McpSchema enumeration(String name, String description, List<String> values, boolean isRequired) {
        ObjectNode p = add(name, "string", description);
        ArrayNode list = p.putArray("enum");
        values.forEach(list::add);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    McpSchema stringArray(String name, String description) {
        ObjectNode p = add(name, "array", description);
        p.putObject("items").put("type", "string");
        return this;
    }

    McpSchema objectProperty(String name, String description, boolean isRequired) {
        return property(name, "object", description, isRequired);
    }

    McpSchema objectArray(String name, String description, boolean isRequired) {
        ObjectNode p = add(name, "array", description);
        p.putObject("items").put("type", "object");
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    ObjectNode build() {
        if (!required.isEmpty()) {
            schema.set("required", required);
        }
        return schema;
    }

    private McpSchema property(String name, String type, String description, boolean isRequired) {
        add(name, type, description);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    private ObjectNode add(String name, String type, String description) {
        ObjectNode p = properties.putObject(name);
        p.put("type", type);
        p.put("description", description);
        return p;
    }
}
