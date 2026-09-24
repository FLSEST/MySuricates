package com.automation.service.pool;

import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.BlockDefinition;
import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads one pool file as a {@link PoolWorkflowEntry} in one language. A bilingual file
 * ({@code "i18n": ["de","en"]}) gets its blocks and instructions resolved to {@code locale}
 * ({@link LocalizedJson}); name and description stay maps. Extracted from {@link WorkflowPoolService}.
 */
@Slf4j
final class PoolFileParser {

    private PoolFileParser() {
    }

    /**
     * A pool file whose field has the wrong shape, e.g. {@code "name": "Tech-Radar"} instead of
     * {@code {"de": …, "en": …}} (a plain workflow export copied into the pool). The message names
     * file, field and the found type, so the log line alone tells what to fix.
     */
    static final class PoolFileFormatException extends IOException {
        PoolFileFormatException(String message) {
            super(message);
        }
    }

    /**
     * @return the entry, or {@code null} when a required field is missing
     * @throws PoolFileFormatException when a field has the wrong type
     */
    static PoolWorkflowEntry parse(Path file, ObjectMapper objectMapper, String locale) throws IOException {
        JsonNode root = objectMapper.readTree(file.toFile());
        if (root == null || !root.isObject()) {
            throw new PoolFileFormatException("Pool file " + file.getFileName()
                    + ": top level must be a JSON object, found " + typeOf(root));
        }
        JsonNode resolved = LocalizedJson.resolveDefinition(root, LocalizedJson.normalize(locale));
        Map<String, Object> raw = objectMapper.convertValue(resolved, new TypeReference<>() {});

        Map<String, String> name = localized(raw, "name", file);
        if (name == null || name.isEmpty()) {
            log.warn("Pool file missing required 'name' field: {}", file.getFileName());
            return null;
        }

        Object blocksRaw = raw.get("blocks");
        if (blocksRaw == null) {
            log.warn("Pool file missing required 'blocks' field: {}", file.getFileName());
            return null;
        }
        if (!(blocksRaw instanceof List<?>)) {
            throw wrongType(file, "blocks", "an array of blocks", blocksRaw);
        }
        List<BlockDefinition> blocks;
        try {
            blocks = objectMapper.convertValue(blocksRaw, new TypeReference<>() {});
        } catch (IllegalArgumentException e) {
            throw new PoolFileFormatException("Pool file " + file.getFileName()
                    + ": field 'blocks' is not a valid block list: " + e.getMessage());
        }

        String filename = file.getFileName().toString();
        String id = filename.replace(".json", "");

        Map<String, String> description = localized(raw, "description", file);
        if (description == null) {
            description = Map.of();
        }

        String icon = string(raw, "icon", file);
        if (icon == null) {
            icon = "📋";
        }
        String category = string(raw, "category", file);
        // Bewusst ohne Default: ein Eintrag ohne 'scope' soll im Pool-Browser als
        // "nicht deklariert" erkennbar sein, nicht als portabel durchgehen.
        String scope = raw.get("scope") instanceof String s ? s : null;

        List<String> tags = stringList(raw, "tags", file);

        String instructions = string(raw, "instructions", file);
        String model = string(raw, "model", file);
        String effort = string(raw, "effort", file);
        String version = string(raw, "version", file);
        if (version == null) {
            version = "1.0.0";
        }
        String author = string(raw, "author", file);

        Map<String, Object> defaultContext = object(raw, "defaultContext", file);
        Map<String, Object> metadata = object(raw, "metadata", file);

        return new PoolWorkflowEntry(
                id, name, description, icon, category, scope, tags,
                blocks, instructions, model, effort, version, author,
                defaultContext, metadata, filename
        );
    }

    /** {@code {"de": "…", "en": "…"}}; {@code null} when absent. */
    private static Map<String, String> localized(Map<String, Object> raw, String field, Path file)
            throws PoolFileFormatException {
        Object value = raw.get(field);
        if (value == null) {
            return null;
        }
        String expected = "an object {\"de\": \"…\", \"en\": \"…\"}";
        if (!(value instanceof Map<?, ?> map)) {
            throw wrongType(file, field, expected, value);
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String)) {
                throw wrongType(file, field + "." + entry.getKey(), "a string", entry.getValue());
            }
        }
        @SuppressWarnings("unchecked")
        Map<String, String> texts = (Map<String, String>) map;
        return texts;
    }

    private static String string(Map<String, Object> raw, String field, Path file) throws PoolFileFormatException {
        Object value = raw.get(field);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        throw wrongType(file, field, "a string", value);
    }

    private static List<String> stringList(Map<String, Object> raw, String field, Path file)
            throws PoolFileFormatException {
        Object value = raw.get(field);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || !list.stream().allMatch(String.class::isInstance)) {
            throw wrongType(file, field, "an array of strings", value);
        }
        @SuppressWarnings("unchecked")
        List<String> strings = (List<String>) list;
        return strings;
    }

    private static Map<String, Object> object(Map<String, Object> raw, String field, Path file)
            throws PoolFileFormatException {
        Object value = raw.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw wrongType(file, field, "an object", value);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private static PoolFileFormatException wrongType(Path file, String field, String expected, Object found) {
        return new PoolFileFormatException("Pool file " + file.getFileName() + ": field '" + field
                + "' must be " + expected + ", found " + typeOf(found));
    }

    private static String typeOf(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof JsonNode node) {
            return node.getNodeType().name().toLowerCase(Locale.ROOT);
        }
        if (value instanceof String s) {
            return "string \"" + (s.length() > 40 ? s.substring(0, 40) + "…" : s) + "\"";
        }
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Number) {
            return "number";
        }
        return value.getClass().getSimpleName();
    }
}
