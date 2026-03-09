package com.automation.service.todo;

import com.automation.entity.GateStatus;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses gate_status, gate_category, and gate_description markers from block output text.
 * Stateless utility — no Spring component.
 */
public final class GateStatusOutputParser {

    private GateStatusOutputParser() {}

    public record ParsedGateStatus(GateStatus status, String category, String description) {}

    // Line-anchored patterns, tolerant of Markdown bold (**), backticks (`),
    // hyphens (Gate-Status), underscores (gate_status), and trailing text
    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "^[*`]*gate[_-]status[*`]*:\\s*[*`]*(RED|YELLOW|GREEN)[*`]*(?:\\s.*)?$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "^[*`]*gate[_-]category[*`]*:\\s*[*`]*(.+?)[*`]*\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile(
            "^[*`]*gate[_-]description[*`]*:\\s*[*`]*(.+?)[*`]*\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /**
     * Parses gate status markers from block output text.
     *
     * @param output the raw block output text
     * @return parsed gate status if a valid gate_status marker is found, empty otherwise
     */
    public static Optional<ParsedGateStatus> parse(String output) {
        if (output == null || output.isBlank()) {
            return Optional.empty();
        }

        Matcher statusMatcher = STATUS_PATTERN.matcher(output);
        if (!statusMatcher.find()) {
            return Optional.empty();
        }

        GateStatus status;
        try {
            status = GateStatus.valueOf(statusMatcher.group(1).toUpperCase());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        String category = extractField(CATEGORY_PATTERN, output);
        String description = extractField(DESCRIPTION_PATTERN, output);

        return Optional.of(new ParsedGateStatus(status, category, description));
    }

    private static String extractField(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
