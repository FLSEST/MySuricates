package com.automation.service.todo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses todo_item markers from block output text.
 * Stateless utility — no Spring component.
 * <p>
 * Format: {@code todo_item: <title> | <priority> | <tags>}
 * <p>
 * Example:
 * <pre>
 * todo_item: ChainExecutor vollständig testen | HIGH | backend,testing
 * todo_item: useWebSocket Hook testen | MEDIUM | frontend,testing
 * </pre>
 * <p>
 * Tolerant of markdown formatting (headings, bold, backticks, hyphens/underscores).
 */
public final class TodoOutputParser {

    private TodoOutputParser() {}

    public record ParsedTodoItem(String title, String priority, List<String> tags) {}

    // Line-anchored pattern, tolerant of:
    // - Markdown headings (###), bold (**), backticks (`)
    // - Hyphens (todo-item) and underscores (todo_item)
    // - Priority: HIGH, MEDIUM, LOW (case-insensitive)
    // - Tags: comma-separated
    private static final Pattern TODO_PATTERN = Pattern.compile(
            "^(?:#{1,6}\\s+)?[*`]*todo[_-]item[*`]*:\\s*(.+?)\\s*\\|\\s*(HIGH|MEDIUM|LOW)\\s*\\|\\s*(.+?)\\s*$",
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
            String priority = matcher.group(2).toUpperCase();
            List<String> tags = Arrays.stream(matcher.group(3).split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            if (!title.isEmpty()) {
                items.add(new ParsedTodoItem(title, priority, tags));
            }
        }

        return items;
    }

    /**
     * Strips common markdown formatting from parsed values.
     */
    private static String cleanMarkdown(String text) {
        return text.replaceAll("[*`]", "").trim();
    }
}
