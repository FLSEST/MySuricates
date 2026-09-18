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
 *
 * <p>Tolerant of common markdown shapes around the marker: leading indentation, a list item or
 * blockquote prefix ({@code -}, {@code *}, {@code >}), a heading, bold or backtick decoration, and a
 * fenced code block around the JSON — including one with a language tag ({@code ```json}).
 *
 * <p><b>Two different rules on purpose (fix round 1, A2):</b> a marker keyword not followed by an
 * opening {@code {} at all does not count as a marker — a report that merely documents this feature
 * in prose (no object after the colon) must not raise a false "invalid result"; see {@link #scan}.
 * A <em>second</em> real marker occurrence within the <b>same</b> output, however, makes the whole
 * output ambiguous: quoted content (a log excerpt, a TODO text) that happens to contain marker
 * syntax before the report's own marker must never be able to silently pick the winning verdict —
 * that is a broken report, not data to choose from; see {@link Scan#ambiguous()}. Across several
 * step outputs of one run that rule does not apply: several markers on different steps are a normal
 * sequence, and {@link DashboardWidgetResultParser#parseRun} lets the last one win.
 */
final class DashboardWidgetMarker {

    /** A valid payload is a few hundred bytes (every contract field is capped); nothing longer is read. */
    static final int MAX_JSON_CHARS = 8192;

    // Anchored to the start of a line, after optional indentation and an optional list/blockquote
    // prefix, and tolerant of markdown decoration around the word itself: headings, bold, backticks,
    // "dashboard-widget" with a hyphen, any case.
    private static final Pattern MARKER = Pattern.compile(
            "^[ \\t]*(?:[-*>]\\s+)?(?:#{1,6}\\s+)?[*`]*dashboard[_-]widget[*`]*:",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /**
     * What one output holds. {@code found} without {@code json} means: there is a real marker
     * (followed by {@code {}), but the object never closes within the cap — a broken result, not a
     * missing one. {@code ambiguous} means a second real marker occurs in this output; {@code json}
     * is then left null, because {@link DashboardWidgetResultParser} rejects it outright anyway.
     */
    record Scan(boolean found, String json, boolean ambiguous) {
        static final Scan NONE = new Scan(false, null, false);
    }

    private DashboardWidgetMarker() {
    }

    /**
     * The marker state of this output. A marker occurrence only "counts" when — after skipping
     * decoration — it is immediately followed by {@code {}; a bare mention of the keyword is skipped
     * entirely, so it neither counts as found nor makes a later, real marker ambiguous. A second
     * counting occurrence makes the whole output ambiguous (see the class javadoc).
     */
    static Scan scan(String output) {
        if (output == null || output.isBlank()) {
            return Scan.NONE;
        }
        Matcher matcher = MARKER.matcher(output);
        int firstObjectStart = -1;
        while (matcher.find()) {
            int pos = skipDecoration(output, matcher.end());
            if (pos >= output.length() || output.charAt(pos) != '{') {
                continue;
            }
            if (firstObjectStart < 0) {
                firstObjectStart = pos;
            } else {
                return new Scan(true, null, true);
            }
        }
        if (firstObjectStart < 0) {
            return Scan.NONE;
        }
        return new Scan(true, readObject(output, firstObjectStart), false);
    }

    /**
     * Skips markdown decoration between the marker's colon and the JSON object: whitespace, bold
     * asterisks, and a fence's backticks together with an optional short language tag right after them
     * (e.g. {@code ```json}). A bare word not directly glued to a fence is left alone — the point is
     * to tolerate fence syntax, not to swallow prose.
     */
    private static int skipDecoration(String text, int pos) {
        boolean afterBacktick = false;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == '`') {
                afterBacktick = true;
                pos++;
                continue;
            }
            if (afterBacktick) {
                int afterTag = skipFenceLanguageTag(text, pos);
                afterBacktick = false;
                if (afterTag > pos) {
                    pos = afterTag;
                    continue;
                }
            }
            if (Character.isWhitespace(c) || c == '*') {
                pos++;
                continue;
            }
            break;
        }
        return pos;
    }

    /**
     * A fence's language tag: a short run of letters right after its backticks, itself followed by
     * whitespace (e.g. {@code json}, {@code jsonc}). Returns {@code pos} unchanged for anything else.
     */
    private static int skipFenceLanguageTag(String text, int pos) {
        int end = pos;
        while (end < text.length() && Character.isLetter(text.charAt(end)) && end - pos < 12) {
            end++;
        }
        if (end == pos || end == text.length() || !Character.isWhitespace(text.charAt(end))) {
            return pos;
        }
        return end;
    }

    /**
     * The JSON object starting at {@code start} (the caller has already confirmed {@code '{'} there),
     * or null when it never closes within the cap.
     */
    private static String readObject(String text, int start) {
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
