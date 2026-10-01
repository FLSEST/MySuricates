package com.automation.util;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reads workflow blocks from JSON a model wrote — the one mapping JSON → {@link BlockDefinition} for the
 * factory ({@code FactoryResponseParser}), the build-check correction ({@code CorrectionAnswer}), wishes, dialog
 * import and run conversion, so all of them keep {@code type} (default {@code simple}), {@code name},
 * {@code config}, {@code requiredCapabilities} and {@code skillIds} (TODO d6be36f3). A dropped {@code name} breaks every {@code {{ block.<name>.output }}}
 * reference of a later block.
 */
public final class BlockJsonReader {

    /** For {@link #fromMapList}: the values there are plain JSON types, no module of the app mapper is needed. */
    private static final ObjectMapper PLAIN = new ObjectMapper();

    private BlockJsonReader() {
    }

    /**
     * Maps one block object; fields of the wrong JSON kind (e.g. {@code config} as a string) are left out, a missing
     * {@code config} becomes an empty mutable map. Any other field ({@code on_failure} beside {@code config}) is kept
     * as {@link BlockDefinition#unknownFields()}, so the analyzer reports it.
     */
    public static BlockDefinition toBlock(JsonNode node, ObjectMapper objectMapper) {
        BlockDefinition block = new BlockDefinition();
        block.setType(node.path("type").asText("simple"));
        if (node.hasNonNull("name") && node.get("name").isValueNode()) {
            block.setName(node.get("name").asText());
        }
        block.setConfig(node.path("config").isObject()
                ? objectMapper.convertValue(node.get("config"), new TypeReference<LinkedHashMap<String, Object>>() {})
                : new LinkedHashMap<>());
        if (node.path("requiredCapabilities").isArray()) {
            block.setRequiredCapabilities(objectMapper.convertValue(node.get("requiredCapabilities"),
                    new TypeReference<List<String>>() {}));
        }
        if (node.path("skillIds").isArray()) {
            block.setSkillIds(objectMapper.convertValue(node.get("skillIds"), new TypeReference<List<String>>() {}));
        }
        // Fields beside these are kept, as on every other way in, so the analyzer reports them (d3aa0b28)
        node.properties().forEach(field -> {
            if (!BlockDefinition.FIELDS.contains(field.getKey())) {
                block.putUnknownField(field.getKey(), objectMapper.convertValue(field.getValue(), Object.class));
            }
        });
        return block;
    }

    /**
     * Maps a JSON array of block objects. Empty if {@code array} is no array or holds anything but objects —
     * a half-read list must not replace a whole one.
     */
    public static List<BlockDefinition> toBlocks(JsonNode array, ObjectMapper objectMapper) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<BlockDefinition> blocks = new ArrayList<>();
        for (JsonNode node : array) {
            if (!node.isObject()) {
                return List.of();
            }
            blocks.add(toBlock(node, objectMapper));
        }
        return blocks;
    }

    /**
     * The block list of a model answer that was already read into plain Java values (a {@code List} of
     * {@code Map}s, e.g. {@code parsed.get("blocks")}), mapped by {@link #toBlock}. Entries that are no object are
     * skipped; anything but a list gives an empty list. Every block gets its own mutable {@code config}.
     */
    public static List<BlockDefinition> fromMapList(Object blocks) {
        if (!(blocks instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<BlockDefinition> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof java.util.Map<?, ?> map) {
                result.add(toBlock(PLAIN.valueToTree(map), PLAIN));
            }
        }
        return result;
    }

    /**
     * Reads a block list from a model answer: a JSON array or an object with {@code blocks}, optionally inside a
     * markdown code fence and surrounded by prose; JSON is read with {@link JsonRepairUtil}. Empty if the answer
     * holds nothing usable.
     *
     * @throws IOException if the JSON cannot be read even after repair
     */
    public static List<BlockDefinition> parseAnswer(String response, ObjectMapper objectMapper) throws IOException {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        String text = stripFences(response.trim());
        int arr = text.indexOf('[');
        int obj = text.indexOf('{');
        if (arr >= 0 && (obj < 0 || arr < obj)) {
            return toBlocks(JsonRepairUtil.readTreeWithRepair(text.substring(arr, text.lastIndexOf(']') + 1)),
                    objectMapper);
        }
        if (obj >= 0) {
            return toBlocks(JsonRepairUtil.readTreeWithRepair(text.substring(obj, text.lastIndexOf('}') + 1))
                    .path("blocks"), objectMapper);
        }
        return List.of();
    }

    /** Removes a surrounding markdown code fence ({@code ```lang … ```}), if present. */
    public static String stripFences(String text) {
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return text;
    }
}
