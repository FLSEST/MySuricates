package com.automation.executor.compress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** json.sort — stable sort of an array by a field; numbers first and numerically, else lexicographically; missing values last. */
final class JsonSortStage implements CompressionStage {

    @Override
    public String op() {
        return "json.sort";
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
        StageConfig.string(config, "by", null, where);
        descending(config);
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        String where = StageConfig.where(op());
        JsonPaths.Document doc = JsonPaths.arrayIn(input, StageConfig.string(config, "path", "", where), op());
        ArrayNode array = doc.array();
        String by = StageConfig.string(config, "by", null, where);
        boolean desc = descending(config);
        List<JsonNode> items = new ArrayList<>();
        array.forEach(items::add);
        items.sort((a, b) -> compare(JsonPaths.at(a, by), JsonPaths.at(b, by), desc));
        array.removeAll();
        items.forEach(array::add);
        return new StageResult(JsonPaths.write(doc.root()),
                CompressionNumbers.fmt(items.size()) + " entries by " + by + (desc ? " descending" : " ascending"));
    }

    private boolean descending(Map<String, Object> config) {
        return switch (StageConfig.string(config, "order", "asc", StageConfig.where(op()))) {
            case "asc" -> false;
            case "desc" -> true;
            default -> throw new CompressionException(StageConfig.where(op()) + ": 'order' must be asc or desc");
        };
    }

    /** Total order: missing last (also for desc); among present values numbers before other values, numbers numerically, others by text. */
    private static int compare(JsonNode a, JsonNode b, boolean desc) {
        boolean missingA = JsonPaths.missing(a);
        boolean missingB = JsonPaths.missing(b);
        if (missingA || missingB) return missingA == missingB ? 0 : (missingA ? 1 : -1);
        int r;
        if (a.isNumber() != b.isNumber()) {
            r = a.isNumber() ? -1 : 1;
        } else {
            r = a.isNumber() ? Double.compare(a.asDouble(), b.asDouble()) : a.asText().compareTo(b.asText());
        }
        return desc ? -r : r;
    }
}
