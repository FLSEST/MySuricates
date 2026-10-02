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
            throw new CompressionException("JSON output failed: " + e.getOriginalMessage());
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

    /** Parsed document plus the array a stage works on — the shared entry of json.limit/sort/to_lines. */
    record Document(JsonNode root, ArrayNode array) {
    }

    /** Parses {@code input} and resolves {@code path} to an array in one step (errors name the op). */
    static Document arrayIn(String input, String path, String op) {
        JsonNode root = parse(input, op);
        return new Document(root, arrayAt(root, path, op));
    }

    static ArrayNode arrayAt(JsonNode root, String path, String op) {
        if (at(root, path) instanceof ArrayNode array) return array;
        String shown = path == null || path.isBlank() ? "(root)" : path;
        throw new CompressionException("Stage " + op + ": '" + shown + "' is not an array");
    }

    static boolean missing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode();
    }

    private static CompressionException notJson(String op) {
        return new CompressionException("Stage " + op + ": input is not valid JSON");
    }
}
