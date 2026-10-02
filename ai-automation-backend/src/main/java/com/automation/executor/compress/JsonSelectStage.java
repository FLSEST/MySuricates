package com.automation.executor.compress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * json.select — keeps only the given paths ("meta.count", "items[].title", "[].a"); the result stays valid JSON.
 * Array elements without a selected field stay as {} so counts do not silently change.
 */
final class JsonSelectStage implements CompressionStage {

    private static final String EACH = "[]";

    private record Path(String raw, List<String> segments) {
    }

    private record Tail(Path path, int pos) {
        boolean done() {
            return pos >= path.segments().size();
        }

        String head() {
            return path.segments().get(pos);
        }

        Tail next() {
            return new Tail(path, pos + 1);
        }
    }

    @Override
    public String op() {
        return "json.select";
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
        paths(config);
    }

    @Override
    public StageResult apply(String input, Map<String, Object> config, RegexGuard guard) {
        JsonNode root = JsonPaths.parse(input, op());
        List<Path> paths = paths(config);
        Set<String> matched = new HashSet<>();
        JsonNode picked = pick(root, paths.stream().map(p -> new Tail(p, 0)).toList(), matched);
        JsonNode result = picked != null ? picked
                : root.isArray() ? JsonPaths.MAPPER.createArrayNode() : JsonPaths.MAPPER.createObjectNode();
        String out = JsonPaths.write(result);
        List<String> missing = paths.stream().map(Path::raw).filter(r -> !matched.contains(r)).toList();
        String note = missing.isEmpty() ? CompressionNumbers.signedChars(out.length() - input.length())
                : "path not found: " + String.join(", ", missing);
        return new StageResult(out, note);
    }

    private List<Path> paths(Map<String, Object> config) {
        String where = StageConfig.where(op());
        List<String> raw = StageConfig.strings(config, "paths", where);
        if (raw.isEmpty()) throw new CompressionException(where + ": 'paths' needs at least one path");
        List<Path> paths = new ArrayList<>();
        for (String r : raw) {
            List<String> segments = new ArrayList<>();
            for (String part : r.split("\\.", -1)) {
                if (part.isEmpty()) throw new CompressionException(where + ": invalid path '" + r + "'");
                if (part.equals(EACH)) {
                    segments.add(EACH);
                } else if (part.endsWith(EACH)) {
                    segments.add(part.substring(0, part.length() - EACH.length()));
                    segments.add(EACH);
                } else {
                    segments.add(part);
                }
            }
            paths.add(new Path(r, segments));
        }
        return paths;
    }

    private static JsonNode pick(JsonNode node, List<Tail> tails, Set<String> matched) {
        if (tails.stream().anyMatch(Tail::done)) {
            tails.forEach(t -> matched.add(t.path().raw()));
            return node.deepCopy();
        }
        if (node.isArray()) {
            List<Tail> each = tails.stream().filter(t -> t.head().equals(EACH)).map(Tail::next).toList();
            if (each.isEmpty()) return null;
            ArrayNode out = JsonPaths.MAPPER.createArrayNode();
            for (JsonNode element : node) {
                JsonNode r = pick(element, each, matched);
                out.add(r != null ? r : JsonPaths.MAPPER.createObjectNode());
            }
            return out;
        }
        if (node.isObject()) {
            Map<String, List<Tail>> byField = new LinkedHashMap<>();
            for (Tail t : tails) {
                if (!t.head().equals(EACH)) byField.computeIfAbsent(t.head(), k -> new ArrayList<>()).add(t.next());
            }
            ObjectNode out = JsonPaths.MAPPER.createObjectNode();
            byField.forEach((field, next) -> {
                JsonNode child = node.get(field);
                JsonNode r = child == null ? null : pick(child, next, matched);
                if (r != null) out.set(field, r);
            });
            return out.isEmpty() ? null : out;
        }
        return null;
    }
}
