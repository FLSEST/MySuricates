package com.automation.executor.compress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Parsing, compact writing and dotted-path navigation shared by the json.* stages. */
final class JsonPaths {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPaths() {
    }

    static JsonNode parse(String input, String op) {
        try {
            JsonNode node = MAPPER.readTree(input.strip());
            if (node == null || node.isMissingNode()) throw notJson(op);
            return node;
        } catch (JsonProcessingException e) {
            throw notJson(op);
        }
    }

    static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new CompressionException("JSON-Ausgabe fehlgeschlagen: " + e.getOriginalMessage());
        }
    }

    /** "a.b" → node, "" → root; null when a segment is missing or a parent is not an object. */
    static JsonNode at(JsonNode root, String path) {
        if (path == null || path.isBlank()) return root;
        JsonNode node = root;
        for (String part : path.split("\\.")) {
            if (node == null || !node.isObject()) return null;
            node = node.get(part);
        }
        return node;
    }

    static ArrayNode arrayAt(JsonNode root, String path, String op) {
        if (at(root, path) instanceof ArrayNode array) return array;
        String shown = path == null || path.isBlank() ? "(Wurzel)" : path;
        throw new CompressionException("Stufe " + op + ": '" + shown + "' ist kein Array");
    }

    static boolean missing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static CompressionException notJson(String op) {
        return new CompressionException("Stufe " + op + ": Eingabe ist kein gültiges JSON");
    }
}
