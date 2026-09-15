package com.automation.executor.compress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.Map;

/** json.limit — shortens an array (path, default root) to count entries. */
final class JsonLimitStage implements CompressionStage {

    @Override
    public String op() {
        return "json.limit";
    }

    @Override
    public StageKind inputKind() {
        return StageKind.JSON;
    }

    @Override
    public StageKind outputKind() {
        return StageKind.JSON;
    }

    @Override
    public void validate(Map<String, Object> config) {
        String where = StageConfig.where(op());
        StageConfig.string(config, "path", "", where);
        StageConfig.intValue(config, "count", null, 0, 1_000_000, where);
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        String where = StageConfig.where(op());
        JsonNode root = JsonPaths.parse(input, op());
        ArrayNode array = JsonPaths.arrayAt(root, StageConfig.string(config, "path", "", where), op());
        int count = StageConfig.intValue(config, "count", null, 0, 1_000_000, where);
        int removed = Math.max(0, array.size() - count);
        while (array.size() > count) array.remove(array.size() - 1);
        return new StageResult(JsonPaths.write(root), "−" + CompressionNumbers.fmt(removed) + " Einträge");
    }
}
