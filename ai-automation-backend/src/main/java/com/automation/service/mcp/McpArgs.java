package com.automation.service.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The {@code arguments} of a tool call, read with a clear message for every mistake. A wrong argument
 * throws {@link InvalidArgument}; the server turns it into an error result the model can correct.
 */
public final class McpArgs {

    /** An argument is missing or has the wrong shape. */
    public static final class InvalidArgument extends RuntimeException {
        public InvalidArgument(String message) {
            super(message);
        }
    }

    private final ObjectNode node;

    public McpArgs(JsonNode arguments) {
        this.node = arguments instanceof ObjectNode object ? object : JsonNodeFactory.instance.objectNode();
    }

    public ObjectNode raw() {
        return node;
    }

    public boolean has(String name) {
        JsonNode value = node.get(name);
        return value != null && !value.isNull();
    }

    public String string(String name) {
        String value = optionalString(name);
        if (value == null || value.isBlank()) {
            throw new InvalidArgument("'" + name + "' is required");
        }
        return value;
    }

    public String optionalString(String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new InvalidArgument("'" + name + "' must be a string");
        }
        return value.asText();
    }

    public int integer(String name, int defaultValue, int min, int max) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (!value.canConvertToInt() || !value.isIntegralNumber()) {
            throw new InvalidArgument("'" + name + "' must be an integer");
        }
        int result = value.asInt();
        if (result < min || result > max) {
            throw new InvalidArgument("'" + name + "' must be between " + min + " and " + max);
        }
        return result;
    }

    public List<String> stringList(String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (value.isTextual()) {
            return List.of(value.asText());
        }
        if (!value.isArray()) {
            throw new InvalidArgument("'" + name + "' must be an array of strings");
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            if (!item.isTextual()) {
                throw new InvalidArgument("'" + name + "' must be an array of strings");
            }
            result.add(item.asText());
        });
        return result;
    }

    /** {@code de} or {@code en}; anything else is refused, absent is English. */
    public String locale(String name) {
        String value = optionalString(name);
        if (value == null || value.isBlank()) {
            return "en";
        }
        String locale = value.trim().toLowerCase(Locale.ROOT);
        if (!locale.equals("de") && !locale.equals("en")) {
            throw new InvalidArgument("'" + name + "' must be 'de' or 'en'");
        }
        return locale;
    }

    /** One of {@code allowed} (case-insensitive), or {@code defaultValue} when absent. */
    public String choice(String name, String defaultValue, List<String> allowed) {
        String value = optionalString(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(lower)) {
            throw new InvalidArgument("'" + name + "' must be one of " + allowed);
        }
        return lower;
    }

    /** An object argument, or null when absent. */
    public ObjectNode optionalObject(String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new InvalidArgument("'" + name + "' must be an object");
        }
        return (ObjectNode) value;
    }
}
