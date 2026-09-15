package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Labels, names and documentation derived from a block's type and config — pure functions. */
final class BpmnBlockText {

    private static final Map<String, String> TYPE_LABELS = Map.ofEntries(
            Map.entry("simple", "KI"), Map.entry("shell", "Shell"), Map.entry("agentic", "Agent"),
            Map.entry("skill-capability", "Skill"), Map.entry("integration", "Integration"),
            Map.entry("document", "Dokument"), Map.entry("approval", "Freigabe"),
            Map.entry("human-input", "Eingabe"), Map.entry("sub-workflow", "Sub-Workflow"),
            Map.entry("retry", "Retry"), Map.entry("compress", "Komprimierung"),
            Map.entry("compress-ai", "KI-Komprimierung"));

    private BpmnBlockText() {}

    static String label(String type) {
        return TYPE_LABELS.getOrDefault(type, type.isBlank() ? "Task" : type);
    }

    static String nameSource(BlockDefinition block) {
        Map<String, Object> c = block.getConfig();
        return switch (block.getType() == null ? "" : block.getType()) {
            case "agentic" -> str(c, "goal");
            case "approval" -> str(c, "question");
            case "human-input" -> str(c, "prompt");
            case "integration" -> joinNonBlank(str(c, "provider"), str(c, "action"));
            case "document" -> str(c, "file_path");
            case "skill-capability" -> str(c, "capability");
            case "compress" -> str(c, "source");
            case "compress-ai" -> joinNonBlank(str(c, "source"), str(c, "instruction"));
            default -> str(c, "command");
        };
    }

    static String documentationFor(BlockDefinition block) {
        Map<String, Object> c = block.getConfig();
        return switch (block.getType() == null ? "" : block.getType()) {
            case "agentic" -> "Ziel: " + str(c, "goal");
            case "approval" -> "Freigabefrage: " + str(c, "question");
            case "human-input" -> "Eingabe: " + str(c, "prompt");
            case "integration" -> "Integration: " + joinNonBlank(str(c, "provider"), str(c, "action"));
            case "document" -> "Dokument: " + str(c, "file_path");
            case "skill-capability" -> "Skill-Capability: " + str(c, "capability");
            case "while" -> "Wiederholen solange: " + firstLine(str(c, "condition_command"), 200);
            case "foreach" -> "Iteriert über: " + str(c, "variable_name");
            case "retry" -> "Automatische Wiederholung bei Fehler";
            case "compress" -> "Komprimierung: " + compressScript(c);
            case "compress-ai" -> "KI-Komprimierung von " + str(c, "source")
                    + (str(c, "instruction").isBlank() ? "" : ": " + firstLine(str(c, "instruction"), 200));
            default -> firstLine(str(c, "command"), 200);
        };
    }

    static String str(Map<String, Object> c, String key) {
        Object v = c == null ? null : c.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    static int intVal(Map<String, Object> c, String key, int fallback) {
        Object v = c == null ? null : c.get(key);
        return v instanceof Number number ? number.intValue() : fallback;
    }

    static String firstLine(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.strip().lines().findFirst().orElse("").strip();
        return line.length() > max ? line.substring(0, max - 3) + "..." : line;
    }

    static String joinNonBlank(String left, String right) {
        if (left.isBlank()) {
            return right;
        }
        return right.isBlank() ? left : left + "." + right;
    }

    /** Null-sicherer String-Cast: verhindert ClassCastException bei fehlerhaften Configs. */
    static String asString(Object value) {
        return value instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    /** Readable stage chain of a compress block, e.g. "source(backend_tests) | lines.tail(50) | text.truncate(18000)". */
    static String compressScript(Map<String, Object> c) {
        StringBuilder sb = new StringBuilder("source(").append(str(c, "source")).append(")");
        if (c != null && c.get("stages") instanceof List<?> stages) {
            for (Object stage : stages) {
                if (!(stage instanceof Map<?, ?> m)) continue;
                sb.append(" | ").append(m.get("op"));
                Object arg = m.containsKey("max_chars") ? m.get("max_chars")
                        : m.containsKey("lines") ? m.get("lines") : m.get("count");
                if (arg != null) sb.append("(").append(arg).append(")");
            }
        }
        return sb.toString();
    }

    /** Verschachtelte Block-Maps (switch cases[].blocks / default) -> BlockDefinitions. */
    @SuppressWarnings("unchecked")
    static List<BlockDefinition> toBlockDefinitions(Object value) {
        List<BlockDefinition> result = new ArrayList<>();
        for (Map<String, Object> map : listOfMaps(value)) {
            BlockDefinition b = new BlockDefinition();
            b.setType(asString(map.get("type")));
            b.setName(asString(map.get("name")));
            Object config = map.get("config");
            b.setConfig(config instanceof Map<?, ?> m ? new HashMap<>((Map<String, Object>) m) : new HashMap<>());
            result.add(b);
        }
        return result;
    }
}
