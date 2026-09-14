package com.automation.service.cli;

import com.automation.service.live.LiveLineKind;
import com.automation.service.live.LiveOutputSink;
import com.automation.util.LineListener;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns single lines of Claude CLI {@code --output-format stream-json} into short live-log lines:
 * tool calls compact ("Bash: mvn test"), assistant text truncated, tool results only as size and
 * error flag, and a closing summary. Works line by line while the process runs; the full output is
 * still parsed afterwards by {@link ClaudeOutputParser}, which this class deliberately does not touch.
 */
public final class ClaudeStreamEventFormatter {

    public record Line(LiveLineKind kind, String text) {}

    /** stream-json lines carry whole tool results; a lower cap would make them unparseable. */
    static final int MAX_JSON_LINE_BYTES = 2 * 1024 * 1024;
    static final int TEXT_PREVIEW_CHARS = 300;
    static final int TOOL_SUMMARY_CHARS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClaudeStreamEventFormatter() {}

    /** Line listener that formats stream-json into {@code sink}; {@link LineListener#NONE} when inactive. */
    public static LineListener listener(LiveOutputSink sink) {
        if (sink == null || !sink.isActive()) {
            return LineListener.NONE;
        }
        return LineListener.withMaxLineBytes(MAX_JSON_LINE_BYTES, (line, droppedBytes) -> {
            for (Line formatted : format(line, droppedBytes)) {
                sink.emit(formatted.kind(), formatted.text());
            }
        });
    }

    /** Formats one raw output line. Never throws; unknown events yield an empty list. */
    public static List<Line> format(String rawLine, long droppedBytes) {
        if (rawLine == null || rawLine.isBlank()) {
            return List.of();
        }
        String trimmed = rawLine.strip();
        if (!trimmed.startsWith("{")) {
            return List.of(new Line(LiveLineKind.LINE, droppedBytes > 0 ? trimmed + " …" : trimmed));
        }
        if (droppedBytes > 0) {
            return truncatedEvent(trimmed);
        }
        try {
            return formatEvent(MAPPER.readTree(trimmed));
        } catch (Exception e) {
            return List.of(new Line(LiveLineKind.LINE, preview(trimmed, TEXT_PREVIEW_CHARS)));
        }
    }

    private static List<Line> formatEvent(JsonNode node) {
        String type = node.path("type").asText("");
        return switch (type) {
            case "system" -> system(node);
            case "assistant" -> assistant(node.path("message").path("content"));
            case "user" -> toolResults(node.path("message").path("content"));
            case "result" -> List.of(result(node));
            default -> List.of();
        };
    }

    private static List<Line> system(JsonNode node) {
        String subtype = node.path("subtype").asText("");
        if ("init".equals(subtype)) {
            StringBuilder text = new StringBuilder("session");
            String model = node.path("model").asText("");
            if (!model.isBlank()) text.append(" · ").append(model);
            int tools = node.path("tools").size();
            if (node.path("tools").isArray()) text.append(" · ").append(tools).append(tools == 1 ? " tool" : " tools");
            return List.of(new Line(LiveLineKind.SYSTEM, text.toString()));
        }
        if ("compact_boundary".equals(subtype)) {
            return List.of(new Line(LiveLineKind.SYSTEM, "context compacted"));
        }
        return List.of();
    }

    private static List<Line> assistant(JsonNode content) {
        if (!content.isArray()) return List.of();
        List<Line> lines = new ArrayList<>();
        for (JsonNode item : content) {
            switch (item.path("type").asText("")) {
                case "text" -> {
                    String text = oneLine(item.path("text").asText(""));
                    if (!text.isBlank()) lines.add(new Line(LiveLineKind.TEXT, preview(text, TEXT_PREVIEW_CHARS)));
                }
                case "tool_use" -> lines.add(new Line(LiveLineKind.TOOL,
                        summarizeTool(item.path("name").asText("tool"), item.path("input"))));
                default -> { /* thinking, redacted_thinking, … are not shown */ }
            }
        }
        return lines;
    }

    private static List<Line> toolResults(JsonNode content) {
        if (!content.isArray()) return List.of();
        List<Line> lines = new ArrayList<>();
        for (JsonNode item : content) {
            if (!"tool_result".equals(item.path("type").asText(""))) continue;
            long bytes = resultBytes(item.path("content"));
            boolean error = item.path("is_error").asBoolean(false);
            lines.add(error
                    ? new Line(LiveLineKind.TOOL_ERROR, "↳ error · " + formatBytes(bytes))
                    : new Line(LiveLineKind.TOOL_RESULT, "↳ " + formatBytes(bytes)));
        }
        return lines;
    }

    private static Line result(JsonNode node) {
        String subtype = node.path("subtype").asText("");
        boolean failed = node.path("is_error").asBoolean(false) || subtype.startsWith("error");
        List<String> parts = new ArrayList<>();
        if (failed && !subtype.isBlank()) parts.add(subtype);
        if (node.hasNonNull("num_turns")) {
            int turns = node.path("num_turns").asInt();
            parts.add(turns + (turns == 1 ? " turn" : " turns"));
        }
        if (node.hasNonNull("total_cost_usd")) parts.add(formatCost(node.path("total_cost_usd").asDouble()));
        if (node.hasNonNull("duration_ms")) parts.add(formatDuration(node.path("duration_ms").asLong()));
        return new Line(LiveLineKind.RESULT, (failed ? "✗ " : "✓ ") + String.join(" · ", parts));
    }

    /** A line beyond {@link #MAX_JSON_LINE_BYTES} cannot be parsed; classify it by its prefix. */
    private static List<Line> truncatedEvent(String prefix) {
        String compact = prefix.replace(" ", "");
        if (compact.startsWith("{\"type\":\"user\"")) {
            return List.of(new Line(LiveLineKind.TOOL_RESULT, "↳ > " + formatBytes(MAX_JSON_LINE_BYTES)));
        }
        if (compact.startsWith("{\"type\":\"assistant\"")) {
            return List.of(new Line(LiveLineKind.TOOL, "(large message)"));
        }
        return List.of();
    }

    static String summarizeTool(String name, JsonNode input) {
        String summary = switch (name) {
            case "Bash" -> firstLine(input.path("command").asText(""));
            case "Read", "Write", "Edit", "MultiEdit" -> input.path("file_path").asText("");
            case "NotebookEdit" -> input.path("notebook_path").asText("");
            case "Grep", "Glob" -> withPath(input.path("pattern").asText(""), input.path("path").asText(""));
            case "LS" -> input.path("path").asText("");
            case "WebFetch" -> input.path("url").asText("");
            case "WebSearch" -> input.path("query").asText("");
            case "Task", "Agent" -> input.path("description").asText("");
            case "TodoWrite" -> input.path("todos").size() + " todos";
            default -> compactJson(input);
        };
        String label = name.startsWith("mcp__") ? name.substring(5).replace("__", "/") : name;
        return preview(summary.isBlank() ? label : label + ": " + summary, TOOL_SUMMARY_CHARS);
    }

    private static long resultBytes(JsonNode content) {
        if (content.isTextual()) return utf8Length(content.asText());
        long bytes = 0;
        if (content.isArray()) {
            for (JsonNode part : content) {
                bytes += part.has("text") ? utf8Length(part.path("text").asText("")) : part.toString().length();
            }
        }
        return bytes;
    }

    private static String withPath(String pattern, String path) {
        return path.isBlank() ? pattern : pattern + " in " + path;
    }

    private static String firstLine(String command) {
        String stripped = command.strip();
        int newline = stripped.indexOf('\n');
        return newline < 0 ? oneLine(stripped) : oneLine(stripped.substring(0, newline)) + " …";
    }

    private static String compactJson(JsonNode input) {
        return input.isMissingNode() || input.isEmpty() ? "" : input.toString();
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String preview(String text, int maxChars) {
        return text.length() > maxChars ? text.substring(0, maxChars) + "…" : text;
    }

    private static long utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String formatCost(double usd) {
        return String.format(Locale.ROOT, usd < 1 ? "$%.3f" : "$%.2f", usd);
    }

    static String formatDuration(long millis) {
        long seconds = Math.round(millis / 1000.0);
        return seconds < 60 ? seconds + "s" : (seconds / 60) + "m " + (seconds % 60) + "s";
    }
}
