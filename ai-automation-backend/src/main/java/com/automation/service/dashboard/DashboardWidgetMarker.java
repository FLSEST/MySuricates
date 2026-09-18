package com.automation.service.dashboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the {@code dashboard_widget:} marker in block output — the same idea as {@code gate_status:}
 * (GateStatusOutputParser), except the value is a JSON object that may span several lines.
 *
 * <p>Deliberately no greedy dotall regex: a run's output can be hundreds of kilobytes, and
 * {@code \{.*}} would scan all of it for every step. The scanner reads balanced braces from the
 * first {@code &#123;} after the marker and gives up after {@link #MAX_JSON_CHARS} characters.
 */
final class DashboardWidgetMarker {

    /** A valid payload is a few hundred bytes (every contract field is capped); nothing longer is read. */
    static final int MAX_JSON_CHARS = 8192;

    // Line-anchored and tolerant of markdown decoration, like the gate marker: headings, bold, backticks,
    // "dashboard-widget" with a hyphen, any case.
    private static final Pattern MARKER = Pattern.compile(
            "^(?:#{1,6}\\s+)?[*`]*dashboard[_-]widget[*`]*:",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** What one output holds: whether there is a marker at all, and the JSON text if it is readable. */
    record Scan(boolean found, String json) {
        static final Scan NONE = new Scan(false, null);
    }

    private DashboardWidgetMarker() {
    }

    /**
     * The first marker of this output. {@code found} without {@code json} means: there is a marker,
     * but no object that closes within the cap — that is a broken result, not a missing one.
     */
    static Scan scan(String output) {
        if (output == null || output.isBlank()) {
            return Scan.NONE;
        }
        Matcher matcher = MARKER.matcher(output);
        if (!matcher.find()) {
            return Scan.NONE;
        }
        int pos = matcher.end();
        // The object may sit on the next line, behind a closing "**" or inside a code fence's backticks.
        while (pos < output.length() && (Character.isWhitespace(output.charAt(pos))
                || output.charAt(pos) == '`' || output.charAt(pos) == '*')) {
            pos++;
        }
        return new Scan(true, readObject(output, pos));
    }

    /** The JSON object starting at {@code start}, or null when none starts or closes there. */
    private static String readObject(String text, int start) {
        if (start >= text.length() || text.charAt(start) != '{') {
            return null;
        }
        int limit = Math.min(text.length(), start + MAX_JSON_CHARS);
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < limit; i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(start, i + 1);
            }
        }
        return null;
    }
}
