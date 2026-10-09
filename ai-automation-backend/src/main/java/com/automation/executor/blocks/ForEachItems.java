package com.automation.executor.blocks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Where {@code foreach} items come from and what an item offers to templates — split off {@link ForEachBlockExecutor}. */
final class ForEachItems {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ForEachItems() {}

    /**
     * Splits newline-separated item sources into items. Blank lines never become an iteration —
     * an empty command output means zero items, not one empty item (which would cost an AI call).
     */
    static List<String> splitIntoItems(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("\n")).filter(line -> !line.isBlank()).toList();
    }

    /** The static {@code items}: a list (each entry as text) or newline-separated text; blank ones never count. */
    static List<String> staticItems(Object rawItems) {
        if (rawItems instanceof List<?> list) {
            return list.stream().map(Object::toString).filter(item -> !item.isBlank()).toList();
        }
        if (rawItems instanceof String s) return splitIntoItems(s);
        return List.of();
    }

    /**
     * The top-level fields of an item that is a JSON object ({@code {"id": "auth", "desc": "Login"}}),
     * as {@code <variable>.<field>} → text, so {@code {{ feature.id }}} works in a command or a
     * {@code context} mapping (TODO a5c9fbbf). Nested objects and arrays stay JSON text; any other item
     * has no fields.
     */
    static Map<String, String> fields(String variableName, String item) {
        String trimmed = item.strip();
        if (!trimmed.startsWith("{")) return Map.of();
        JsonNode node;
        try {
            node = JSON.readTree(trimmed);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
        if (node == null || !node.isObject()) return Map.of();
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            JsonNode value = field.getValue();
            fields.put(variableName + "." + field.getKey(),
                    value.isNull() ? "" : value.isValueNode() ? value.asText() : value.toString());
        }
        return fields;
    }
}
