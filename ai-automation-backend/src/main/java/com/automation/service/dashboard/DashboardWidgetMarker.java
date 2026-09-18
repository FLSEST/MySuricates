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
 * <p>Tolerant of common markdown shapes around the marker: leading indentation, a list item,
 * blockquote or numbered-list prefix ({@code -}, {@code *}, {@code +}, {@code >}, {@code 1.},
 * {@code 1)}, up to two digits), a heading, bold or backtick decoration, and a fenced code block
 * around the JSON — including one with a language tag ({@code ```json}, glued directly to the JSON
 * or followed by whitespace).
 *
 * <p><b>Three rules on purpose:</b>
 * <ol>
 *   <li>A marker keyword not followed by an opening {@code {} or {@code [} at all does not count as
 *       a marker — a report that merely documents this feature in prose (no object after the colon)
 *       must not raise a false "invalid result"; see {@link #scan}.</li>
 *   <li>A marker followed by {@code [} (fix round 2, B4) is a real, broken occurrence — an array is
 *       not this contract's shape, but it is clearly an attempt at one, so the workflow author should
 *       see "invalid result", not silently "no result yet".</li>
 *   <li>Ambiguity is decided by an <b>unanchored</b> occurrence count (fix round 2, B3): counting only
 *       line-anchored markers, as round 1 did, missed a marker that does not start at a line's
 *       beginning (a report writing its own conclusion as {@code "Ergebnis: dashboard_widget: ..."},
 *       or an attacker's marker hidden mid-line in quoted content) — such a marker neither won nor
 *       triggered ambiguity, so quoted content could still silently decide the result. More than one
 *       real occurrence <em>anywhere</em> in the output — regardless of line position — now makes the
 *       whole output ambiguous; see {@link Scan#ambiguous()}. The <b>anchored</b> pattern is still
 *       used to pick the winner once the count confirms there is exactly one: a lone real occurrence
 *       that itself is not line-anchored is therefore MISSING, not a winner, and not ambiguous either
 *       — a report's own marker is expected to start its own line. Across several step outputs of one
 *       run the ambiguity rule does not apply at all: several markers on different steps are a normal
 *       sequence, and {@link DashboardWidgetResultParser#parseRun} lets the last one win.</li>
 * </ol>
 *
 * <p><b>Fix round 3, C1 (regression from round 2):</b> the anchored and unanchored patterns are built
 * from one shared {@link #MARKER_KEYWORD} sub-expression so they can never drift apart on what counts
 * as "the keyword" again — round 2's unanchored pattern lacked the anchored one's {@code [*`]*}
 * decoration group, so a marker written as {@code **dashboard_widget**:} counted as zero occurrences
 * for ambiguity while still winning the anchored search, reopening the hijack this class exists to
 * close.
 *
 * <p><b>Fix round 3, C2:</b> the ambiguity counter (unlike the winner search) stops at a blank line
 * while deciding whether an occurrence is real — a prose mention of the feature, followed several
 * paragraphs later by an unrelated example object, must not be miscounted as a real occurrence just
 * because {@code skipDecoration} would otherwise happily walk across any number of blank lines to
 * reach it.
 */
final class DashboardWidgetMarker {

    /** A valid payload is a few hundred bytes (every contract field is capped); nothing longer is read. */
    static final int MAX_JSON_CHARS = 8192;

    /**
     * The marker keyword itself: tolerant of bold/backtick decoration touching the word on either
     * side and the hyphenated spelling, any case. The single source of truth for "what is the
     * keyword" — {@link #ANCHORED_MARKER} and {@link #UNANCHORED_MARKER} are both built from this one
     * sub-expression (fix round 3, C1) so they cannot silently drift apart on what counts as a marker.
     */
    private static final String MARKER_KEYWORD = "[*`]*dashboard[_-]widget[*`]*:";

    // Anchored to the start of a line, after optional indentation and an optional list/blockquote/
    // numbered prefix, and a heading. Used to pick the winner (see class javadoc) once the unanchored
    // count below has confirmed there is exactly one real occurrence.
    private static final Pattern ANCHORED_MARKER = Pattern.compile(
            "^[ \\t]*(?:[-*>+]\\s+|\\d{1,2}[.)]\\s+)?(?:#{1,6}\\s+)?" + MARKER_KEYWORD,
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // Unanchored (fix round 2, B3): used only to count how many real marker occurrences exist
    // anywhere in the output, regardless of line position — see the class javadoc.
    private static final Pattern UNANCHORED_MARKER = Pattern.compile(
            MARKER_KEYWORD, Pattern.CASE_INSENSITIVE);

    // A blank line: a line break, optional horizontal whitespace, another line break (fix round 3,
    // C2) — used only by the ambiguity counter to stop crediting a marker with a value that is
    // actually several paragraphs away.
    private static final Pattern BLANK_LINE = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n");

    /**
     * What one output holds. {@code found} without {@code json} means: there is a real marker
     * (followed by {@code {} or {@code [}), but either it is an array (never a valid contract shape)
     * or the object never closes within the cap — a broken result, not a missing one. {@code
     * ambiguous} means more than one real marker occurs anywhere in this output; {@code json} is then
     * left null, because {@link DashboardWidgetResultParser} rejects it outright anyway.
     */
    record Scan(boolean found, String json, boolean ambiguous) {
        static final Scan NONE = new Scan(false, null, false);
    }

    private DashboardWidgetMarker() {
    }

    /**
     * The marker state of this output. A marker occurrence only "counts" when — after skipping
     * decoration — it is immediately followed by {@code {} or {@code [}; a bare mention of the
     * keyword is skipped entirely. More than one counting occurrence anywhere makes the whole output
     * ambiguous (see the class javadoc); otherwise the line-anchored occurrence, if any, wins.
     */
    static Scan scan(String output) {
        if (output == null || output.isBlank()) {
            return Scan.NONE;
        }
        if (countRealOccurrences(output) > 1) {
            return new Scan(true, null, true);
        }
        Matcher matcher = ANCHORED_MARKER.matcher(output);
        while (matcher.find()) {
            int pos = skipDecoration(output, matcher.end());
            if (pos >= output.length()) {
                continue;
            }
            char c = output.charAt(pos);
            if (c == '{') {
                return new Scan(true, readObject(output, pos), false);
            }
            if (c == '[') {
                return new Scan(true, null, false);
            }
        }
        return Scan.NONE;
    }

    /**
     * How many real marker occurrences (a marker counts once it is followed, after decoration, by
     * {@code {} or {@code [}) exist anywhere in the output — not only at a line's start (fix round 2,
     * B3+B4). Short-circuits once a second one is found; the exact count above 1 is never needed.
     *
     * <p>Fix round 3, C2: unlike the winner search, a candidate here is only real if nothing between
     * the marker and its value crosses a blank line — otherwise a bare prose mention of the feature,
     * followed several paragraphs later by an unrelated example object, would count as a real
     * occurrence merely because {@link #skipDecoration} tolerates walking across blank lines (needed
     * for the winner search, e.g. a marker line followed by a fenced JSON block).
     */
    private static int countRealOccurrences(String output) {
        Matcher matcher = UNANCHORED_MARKER.matcher(output);
        int count = 0;
        while (matcher.find()) {
            int start = matcher.end();
            int pos = skipDecoration(output, start);
            if (pos < output.length() && (output.charAt(pos) == '{' || output.charAt(pos) == '[')
                    && !BLANK_LINE.matcher(output).region(start, pos).find()) {
                count++;
                if (count > 1) {
                    return count;
                }
            }
        }
        return count;
    }

    /**
     * Skips markdown decoration between the marker's colon and the JSON value: whitespace, bold
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
     * whitespace or (fix round 2, B5) directly by {@code {} — so {@code ```json{"status":"GREEN"}} on
     * one line works, not only {@code ```json} followed by a line break. Returns {@code pos} unchanged
     * for anything else.
     */
    private static int skipFenceLanguageTag(String text, int pos) {
        int end = pos;
        while (end < text.length() && Character.isLetter(text.charAt(end)) && end - pos < 12) {
            end++;
        }
        if (end == pos || end == text.length()) {
            return pos;
        }
        char next = text.charAt(end);
        if (!Character.isWhitespace(next) && next != '{') {
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
