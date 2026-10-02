package com.automation.service.runoutput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses todo_item markers from block output text.
 * Stateless utility — no Spring component.
 * <p>
 * Format: {@code todo_item: <title> | <priority> | <tags> | <description>}
 * <p>
 * The description (4th field) is optional for backwards compatibility.
 * <p>
 * Example:
 * <pre>
 * todo_item: ChainExecutor testen | HIGH | backend,testing | 160-LOC Execution-Loop ungetestet. Siehe: docs/REPORT.md#1
 * todo_item: useWebSocket Hook testen | MEDIUM | frontend,testing
 * </pre>
 * <p>
 * Tolerant of markdown formatting (headings, bold, backticks, hyphens/underscores).
 */
public final class TodoOutputParser {

    private TodoOutputParser() {}

    public record ParsedTodoItem(String title, String priority, List<String> tags, String description) {}

    // foreach stellt jeder Iterationsausgabe "[<item>]: " voran (ForEachBlockExecutor) — ein
    // Marker in der ersten Zeile einer Iteration stuende sonst nicht am Zeilenanfang (TODO 8671d03b).
    private static final String FOREACH_PREFIX = "(?:\\[[^\\]\\n]*\\]:\\s*)?";

    // Line-anchored pattern with optional 4th description field:
    // Group 1: title, Group 2: priority, Group 3: tags, Group 4: description (optional)
    private static final Pattern TODO_PATTERN = Pattern.compile(
            "^" + FOREACH_PREFIX + "(?:#{1,6}\\s+)?[*`]*todo[_-]item[*`]*:\\s*(.+?)\\s*\\|\\s*(CRITICAL|HIGH|MEDIUM|LOW)\\s*\\|\\s*(.+?)(?:\\s*\\|\\s*(.+?))?\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /**
     * Parses all todo_item markers from block output text.
     *
     * @param output the raw block output text
     * @return list of parsed TODO items (empty list if none found)
     */
    public static List<ParsedTodoItem> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }

        List<ParsedTodoItem> items = new ArrayList<>();
        Matcher matcher = TODO_PATTERN.matcher(output);

        while (matcher.find()) {
            String title = cleanMarkdown(matcher.group(1).trim());
            String priority = matcher.group(2).toUpperCase(Locale.ROOT);
            List<String> tags = Arrays.stream(matcher.group(3).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            String description = matcher.group(4) != null ? cleanMarkdown(matcher.group(4).trim()) : null;

            if (!title.isEmpty()) {
                items.add(new ParsedTodoItem(title, priority, tags, description));
            }
        }

        return items;
    }

    private static String cleanMarkdown(String text) {
        return text.replaceAll("[*`]", "").trim();
    }
}
