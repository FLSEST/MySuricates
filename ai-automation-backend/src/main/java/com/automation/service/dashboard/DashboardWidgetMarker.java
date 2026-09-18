package com.automation.service.dashboard;

import com.automation.service.dashboard.DashboardWidgetOccurrences.Occurrence;

import java.util.List;

/**
 * Finds the {@code dashboard_widget:} marker in block output — the same idea as {@code gate_status:}
 * (GateStatusOutputParser), except the value is a JSON object that may span several lines.
 *
 * <p>Deliberately no greedy dotall regex: a run's output can be hundreds of kilobytes, and
 * {@code \{.*}} would scan all of it for every step. The scanner reads balanced braces from the
 * first opening brace after the marker and gives up after {@link #MAX_JSON_CHARS} characters.
 *
 * <p><b>Two questions, answered separately</b> (fix round 5 — rounds 2 to 4 kept merging them, and
 * every merge reopened the same hijack). {@link DashboardWidgetOccurrences} enumerates every keyword
 * occurrence once and answers both for each of them: <em>is there evidence of another marker</em>
 * (permissive, so the output fails closed) and <em>is this a usable marker</em> (precise, so exactly
 * one occurrence is read). This class only combines the two answers:
 * <ul>
 *   <li>more than one occurrence counts as evidence ⇒ the whole output is ambiguous, {@code json}
 *       stays null and {@link DashboardWidgetResultParser} rejects it. Workflow output is
 *       AI-generated text that quotes external sources, so a quoted marker must never be able to
 *       out-vote the report's own verdict — when in doubt, no widget rather than the wrong one;</li>
 *   <li>otherwise the first occurrence usable as a winner (line-anchored, with a value) decides.</li>
 * </ul>
 * Across several step outputs of one run the ambiguity rule does not apply at all: markers on
 * different steps are a normal sequence, and {@link DashboardWidgetResultParser#parseRun} lets the
 * last one win.
 *
 * <p>{@code found} without {@code json} means a real marker whose value is not a readable object:
 * an array (never a valid contract shape, so the author sees "invalid result" rather than "no result
 * yet"), an object that never closes within the cap, or an ambiguous output. A keyword followed by
 * neither an opening brace nor an opening bracket is not a marker at all — a report that merely
 * mentions the feature in prose must not raise a false alarm.
 *
 * <p><b>Accepted residual risk.</b> A quoted marker can still win, but only when the report writes
 * its <em>own</em> marker in a shape neither question recognises — none of these is reachable by the
 * attacker, who controls only the quoted text. There are three such shapes, all measured:
 * <ol>
 *   <li>unanchored <em>and</em> separated from its value by a blank line (fix round 5, D1;
 *       unchanged since round 3, ~31 000 of the reviewer's ~937 000 corpus inputs). Closing it would
 *       mean counting a prose mention paragraphs away from an unrelated example block — the false
 *       alarm fix round 3 removed;</li>
 *   <li>a non-breaking space (U+00A0) or a NEL (U+0085) between the colon and the value: the
 *       decoration walk in {@link DashboardWidgetOccurrences} uses {@code Character.isWhitespace},
 *       which crosses neither, so such a marker has no value and counts for nothing. That walk is
 *       therefore a third, narrower notion of "blank" than the two the class otherwise uses (the
 *       line terminators of the blank-line test, and the parser's visible-character rule);</li>
 *   <li>{@code \r\r} between an unanchored marker and its value (2 448 cases): since round 5 uses
 *       one line definition for the anchored pattern and the blank-line test, a double carriage
 *       return is a blank line like {@code \n\n}, which round 3 happened to catch.</li>
 * </ol>
 * Everything a report normally writes — line-anchored in any decoration, mid-line, indented with
 * spaces or a non-breaking space, value on the next line, value in a fenced block — is recognised.
 */
final class DashboardWidgetMarker {

    /** A valid payload is a few hundred bytes (every contract field is capped); nothing longer is read. */
    static final int MAX_JSON_CHARS = 8192;

    /**
     * What one output holds: whether a real marker exists at all, the winner's JSON object when
     * there is exactly one readable marker, and whether the output was ambiguous.
     */
    record Scan(boolean found, String json, boolean ambiguous) {
        static final Scan NONE = new Scan(false, null, false);
    }

    private DashboardWidgetMarker() {
    }

    /** The marker state of this output — see the class javadoc for the two rules it combines. */
    static Scan scan(String output) {
        if (output == null || output.isBlank()) {
            return Scan.NONE;
        }
        List<Occurrence> occurrences = DashboardWidgetOccurrences.in(output);
        if (countEvidence(occurrences) > 1) {
            return new Scan(true, null, true);
        }
        for (Occurrence occurrence : occurrences) {
            if (!occurrence.usableAsWinner()) {
                continue;
            }
            int valueStart = occurrence.valueStart();
            if (output.charAt(valueStart) == '{') {
                return new Scan(true, readObject(output, valueStart), false);
            }
            return new Scan(true, null, false); // an array: a real marker, but never the contract
        }
        return Scan.NONE;
    }

    /**
     * How many occurrences count as evidence that this output holds more than one marker.
     * Package-private for the tests: the monotonicity property (fix round 5, D2) pins that this
     * question accepts a superset of what the winner search accepts.
     */
    static int countEvidence(String output) {
        return countEvidence(DashboardWidgetOccurrences.in(output));
    }

    private static int countEvidence(List<Occurrence> occurrences) {
        int count = 0;
        for (Occurrence occurrence : occurrences) {
            if (occurrence.countsAsEvidence()) {
                count++;
            }
        }
        return count;
    }

    /**
     * The JSON object starting at {@code start} (the caller has already confirmed an opening brace
     * there), or null when it never closes within the cap.
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
