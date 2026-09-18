package com.automation.service.dashboard;

import java.util.ArrayList;
import java.util.List;
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
 * or followed by whitespace). Round 3 dropped tolerance for whitespace before the colon itself
 * (round 1/2 briefly accepted {@code dashboard_widget : {...}}; the shared {@link #MARKER_KEYWORD}
 * requires the colon right after the word) — both patterns agree on this, so it is a firm rule, not
 * a gap.
 *
 * <p><b>Fix round 4, R1 (structural — the actual fix for two prior regressions):</b> rounds 2 and 3
 * each wrote "is this occurrence real" as separate, hand-copied logic in the winner loop and in the
 * occurrence counter, and each time the two copies quietly diverged: round 2's counter pattern lacked
 * the winner pattern's decoration group (a decorated marker counted for zero, round 3, C1); round 3's
 * counter alone gained a blank-line check that the winner loop never had, and — because the winner
 * loop stayed fully tolerant of blank lines while the counter's check applied indiscriminately to
 * <em>anchored</em> occurrences too — a line-anchored marker whose value sits after a blank line got
 * silently under-counted, hiding real ambiguity (the round-3 fix's own regression, closed here).
 *
 * <p>There is now exactly one predicate, {@link #realValueStart}, and both the winner loop in
 * {@link #scan} and {@link #countRealOccurrences} call it with no acceptance logic of their own.
 * Finding <em>candidates</em> still uses two patterns — {@link #ANCHORED_MARKER} (line-anchored,
 * decoration around the word) to pick the winner, {@link #UNANCHORED_MARKER} (the same
 * {@link #MARKER_KEYWORD}, anywhere) to enumerate everything the counter must consider — because
 * anchored candidates are a subset of unanchored ones; that split is fine as long as what counts as a
 * <em>real</em> occurrence, once a candidate is found, is identical code either way.
 *
 * <p>The rule {@link #realValueStart} implements: an occurrence is real when it is line-anchored, or
 * when its value starts on the very same line as the keyword. A line-anchored marker is always real,
 * however many lines — blank or not — separate it from its value (so {@code dashboard_widget:} then
 * a blank line then a fenced {@code ```json} block keeps working). An <em>unanchored</em> marker
 * (mid-line, e.g. hidden in quoted content or written as {@code "Ergebnis: dashboard_widget: ..."})
 * is real only if its value follows immediately, without crossing a line terminator — a bare prose
 * mention such as {@code "Siehe dashboard_widget:"}, whose value (if any) is several lines away, is
 * never counted as a real occurrence. Both the winner search and the ambiguity counter apply this
 * rule identically: a marker followed by neither {@code {} nor {@code [} at all is not a marker (a
 * prose mention must not raise a false "invalid result"); a marker followed by {@code [} (round 2,
 * B4) is real but never a valid contract shape (an array is clearly an attempt at one, so the
 * workflow author should see "invalid result", not silently "no result yet"). More than one real
 * occurrence <em>anywhere</em> in the output makes the whole output ambiguous — see
 * {@link Scan#ambiguous()} — regardless of line position, because quoted/injected content must never
 * be able to silently out-vote the report's own verdict. Across several step outputs of one run the
 * ambiguity rule does not apply at all: several markers on different steps are a normal sequence, and
 * {@link DashboardWidgetResultParser#parseRun} lets the last one win.
 */
final class DashboardWidgetMarker {

    /** A valid payload is a few hundred bytes (every contract field is capped); nothing longer is read. */
    static final int MAX_JSON_CHARS = 8192;

    /**
     * The marker keyword itself: tolerant of bold/backtick decoration touching the word on either
     * side and the hyphenated spelling, any case. The single source of truth for "what is the
     * keyword" — {@link #ANCHORED_MARKER} and {@link #UNANCHORED_MARKER} are both built from this one
     * sub-expression so they cannot silently drift apart on what counts as a marker.
     */
    private static final String MARKER_KEYWORD = "[*`]*dashboard[_-]widget[*`]*:";

    // Anchored to the start of a line, after optional indentation and an optional list/blockquote/
    // numbered prefix, and a heading. Finds winner candidates (see class javadoc); every match is by
    // definition "line-anchored" for realValueStart's purposes.
    private static final Pattern ANCHORED_MARKER = Pattern.compile(
            "^[ \\t]*(?:[-*>+]\\s+|\\d{1,2}[.)]\\s+)?(?:#{1,6}\\s+)?" + MARKER_KEYWORD,
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // Unanchored: finds every candidate the ambiguity counter must consider, anywhere in the output —
    // see the class javadoc.
    private static final Pattern UNANCHORED_MARKER = Pattern.compile(
            MARKER_KEYWORD, Pattern.CASE_INSENSITIVE);

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
     * The marker state of this output. More than one real occurrence anywhere makes the whole output
     * ambiguous (see the class javadoc); otherwise the line-anchored occurrence, if any, wins.
     */
    static Scan scan(String output) {
        if (output == null || output.isBlank()) {
            return Scan.NONE;
        }
        List<Integer> anchoredEnds = anchoredKeywordEnds(output);
        if (countRealOccurrences(output, anchoredEnds) > 1) {
            return new Scan(true, null, true);
        }
        for (int keywordEnd : anchoredEnds) {
            int valueStart = realValueStart(output, keywordEnd, true);
            if (valueStart < 0) {
                continue;
            }
            if (output.charAt(valueStart) == '{') {
                return new Scan(true, readObject(output, valueStart), false);
            }
            return new Scan(true, null, false); // '['
        }
        return Scan.NONE;
    }

    /** Every line-anchored marker keyword's end position (right after its colon), in text order. */
    private static List<Integer> anchoredKeywordEnds(String output) {
        List<Integer> ends = new ArrayList<>();
        Matcher matcher = ANCHORED_MARKER.matcher(output);
        while (matcher.find()) {
            ends.add(matcher.end());
        }
        return ends;
    }

    /**
     * How many real marker occurrences exist anywhere in the output — not only at a line's start (fix
     * round 2, B3+B4) — using the single acceptance rule in {@link #realValueStart} (fix round 4,
     * R1). Package-private, not private: the differential test (R2) calls this directly to pin the
     * invariant that whenever {@link #scan} returns a winner, this count is exactly 1.
     */
    static int countRealOccurrences(String output) {
        return countRealOccurrences(output, anchoredKeywordEnds(output));
    }

    private static int countRealOccurrences(String output, List<Integer> anchoredEnds) {
        Matcher matcher = UNANCHORED_MARKER.matcher(output);
        int count = 0;
        while (matcher.find()) {
            int keywordEnd = matcher.end();
            boolean anchored = anchoredEnds.contains(keywordEnd);
            if (realValueStart(output, keywordEnd, anchored) >= 0) {
                count++;
                if (count > 1) {
                    return count;
                }
            }
        }
        return count;
    }

    /**
     * THE single acceptance rule (fix round 4, R1): where a marker's value starts after
     * {@code keywordEnd} (the position right after the matched keyword's colon), or -1 when this
     * occurrence is not real. Used by both {@link #scan}'s winner loop and
     * {@link #countRealOccurrences} — neither has any acceptance logic of its own.
     *
     * <p>Skips decoration, then requires an opening {@code {} or {@code [} right there. When the
     * occurrence is <b>not</b> line-anchored, the decoration walk may not cross a line terminator at
     * all — its value must start on the very same line, or it does not count. A line-anchored
     * occurrence is always real regardless of how many lines (blank or not) the decoration walk
     * crosses, exactly as before.
     */
    private static int realValueStart(String output, int keywordEnd, boolean anchored) {
        int pos = skipDecoration(output, keywordEnd);
        if (pos >= output.length()) {
            return -1;
        }
        char c = output.charAt(pos);
        if (c != '{' && c != '[') {
            return -1;
        }
        if (!anchored && containsLineBreak(output, keywordEnd, pos)) {
            return -1;
        }
        return pos;
    }

    private static boolean containsLineBreak(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
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
