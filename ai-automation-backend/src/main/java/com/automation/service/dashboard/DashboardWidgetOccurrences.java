package com.automation.service.dashboard;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every {@code dashboard_widget:} keyword occurrence in one block output, together with the facts
 * the two questions about it are decided from.
 *
 * <p><b>Two questions, deliberately not one predicate (fix round 5).</b> Rounds 2 to 4 kept trying
 * to make "is there another marker?" and "which marker do we read?" the same rule. They are not the
 * same rule:
 * <ul>
 *   <li><b>Is there evidence of another marker?</b> — {@link Occurrence#countsAsEvidence()},
 *       deliberately permissive. Its job is to notice that a second marker <em>might</em> exist and
 *       let the whole output fail closed, because quoted, attacker-controlled text must never be
 *       able to out-vote the report's own verdict.</li>
 *   <li><b>Is this a usable marker?</b> — {@link Occurrence#usableAsWinner()}, precise. Its job is
 *       to pick exactly the one occurrence whose JSON is read.</li>
 * </ul>
 * Round 4 collapsed both into one predicate and the guard inherited the winner's precision: an
 * unanchored occurrence counted only when its value started on the same line, so a quoted marker
 * became the sole counted one as soon as the report put its own value on the next line (48 674 cases
 * of the reviewer's corpus). The rule is now the union of rounds 3 and 4: <b>a line-anchored
 * occurrence always counts; an unanchored one counts unless a blank line separates it from its
 * value.</b>
 *
 * <p><b>Superset by construction.</b> Both questions share one <em>candidate</em> notion — the same
 * keyword spelling ({@link #MARKER_KEYWORD}), the same decoration walk, the same "is the next thing
 * an opening brace or bracket" test — and the counter can only ever accept more than the winner:
 * {@link #in} enumerates the anchored candidates as part of the same list, and
 * {@code usableAsWinner()} implies {@code countsAsEvidence()} for every occurrence, since an
 * anchored occurrence with a value satisfies both. A future change that makes the winner accept more
 * without the counter following breaks that implication, which the monotonicity property in
 * {@code DashboardWidgetResultParserTest} pins.
 *
 * <p><b>What a "line" is.</b> One definition serves both halves: the line terminators Java's regex
 * engine ends a line at for a MULTILINE {@code ^} — {@code \n}, {@code \r\n}, {@code \r}, U+0085,
 * U+2028 and U+2029 — are exactly the characters {@link #crossesBlankLine} counts as line breaks
 * (before round 5 the anchored pattern knew all six while the line test knew only {@code \n} and
 * {@code \r}; every mismatch happened to land on the fail-closed side, but the definition is single
 * now). A blank line is crossed when two of them follow each other with nothing but non-terminator
 * whitespace in between.
 *
 * <p><b>Tolerated shapes.</b> Around the keyword: leading indentation, a list, blockquote or
 * numbered-list prefix ({@code -}, {@code *}, {@code +}, {@code >}, {@code 1.}, {@code 1)}, up to
 * two digits), a heading, and bold or backtick decoration touching the word; the hyphenated spelling
 * and any case. Between the colon and the value: whitespace, bold asterisks and a fence with an
 * optional language tag. Not tolerated, and both questions agree on it: whitespace before the colon
 * ({@code dashboard_widget : ...} is not a marker, dropped in round 3), and a U+00A0 before the
 * value — the decoration walk uses {@code Character.isWhitespace}, which excludes the non-breaking
 * space, so a marker whose value it separates is not a marker at all. An NBSP-<em>indented</em>
 * marker line, by contrast, is an ordinary unanchored occurrence.
 */
final class DashboardWidgetOccurrences {

    /**
     * The marker keyword: bold/backtick decoration touching the word on either side, the hyphenated
     * spelling, any case, colon directly after the word. The single source of truth for "what is the
     * keyword" — both patterns below are built from it so they cannot drift apart on that question.
     */
    private static final String MARKER_KEYWORD = "[*`]*dashboard[_-]widget[*`]*:";

    /** Line-anchored, after optional indentation, a list/blockquote/numbered prefix and a heading. */
    private static final Pattern ANCHORED_MARKER = Pattern.compile(
            "^[ \\t]*(?:[-*>+]\\s+|\\d{1,2}[.)]\\s+)?(?:#{1,6}\\s+)?" + MARKER_KEYWORD,
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** The same keyword anywhere, line-anchored or not. */
    private static final Pattern ANY_MARKER = Pattern.compile(MARKER_KEYWORD, Pattern.CASE_INSENSITIVE);

    /**
     * One keyword occurrence. {@code valueStart} is where its JSON value begins after the decoration
     * walk, or -1 when the keyword is followed by neither an opening brace nor an opening bracket —
     * a prose mention, which is no marker at all rather than a broken one.
     */
    record Occurrence(int keywordEnd, boolean anchored, int valueStart, boolean blankLineBeforeValue) {

        boolean hasValue() {
            return valueStart >= 0;
        }

        /** "Is this a usable marker?" — precise: only a line-anchored occurrence may be read. */
        boolean usableAsWinner() {
            return hasValue() && anchored;
        }

        /**
         * "Is there evidence of another marker?" — permissive, and never narrower than
         * {@link #usableAsWinner()}: everything with a value counts, except an unanchored occurrence
         * whose value sits beyond a blank line (a report documenting the feature in prose, with an
         * unrelated example block paragraphs later, must not raise a false ambiguity alarm).
         */
        boolean countsAsEvidence() {
            return hasValue() && (anchored || !blankLineBeforeValue);
        }
    }

    private DashboardWidgetOccurrences() {
    }

    /**
     * Every keyword occurrence of {@code output}, in text order. The list is the union of both
     * patterns' matches, so it can only be a superset of the winner candidates, whatever the two
     * patterns do.
     */
    static List<Occurrence> in(String output) {
        if (output == null || output.isEmpty()) {
            return List.of();
        }
        Set<Integer> anchoredEnds = keywordEnds(ANCHORED_MARKER, output);
        Set<Integer> allEnds = new TreeSet<>(keywordEnds(ANY_MARKER, output));
        allEnds.addAll(anchoredEnds);
        List<Occurrence> occurrences = new ArrayList<>(allEnds.size());
        for (int keywordEnd : allEnds) {
            occurrences.add(occurrenceAt(output, keywordEnd, anchoredEnds.contains(keywordEnd)));
        }
        return occurrences;
    }

    /** A hash set, not a list: {@link #in} looks every end up (fix round 5, D3 — scan was quadratic). */
    private static Set<Integer> keywordEnds(Pattern pattern, String output) {
        Set<Integer> ends = new HashSet<>();
        Matcher matcher = pattern.matcher(output);
        while (matcher.find()) {
            ends.add(matcher.end());
        }
        return ends;
    }

    private static Occurrence occurrenceAt(String output, int keywordEnd, boolean anchored) {
        int pos = skipDecoration(output, keywordEnd);
        char value = pos < output.length() ? output.charAt(pos) : ' ';
        if (value != '{' && value != '[') {
            return new Occurrence(keywordEnd, anchored, -1, false);
        }
        return new Occurrence(keywordEnd, anchored, pos, crossesBlankLine(output, keywordEnd, pos));
    }

    /**
     * Skips markdown decoration between the marker's colon and its value: whitespace, bold
     * asterisks, and a fence's backticks together with an optional short language tag right after
     * them (e.g. {@code ```json}). A bare word not glued to a fence is left alone — the point is to
     * tolerate fence syntax, not to swallow prose.
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
     * whitespace or (fix round 2) directly by the value, so a tag glued to the JSON on one line
     * works too. Returns {@code pos} unchanged for anything else.
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

    /** Whether {@code [start, end)} holds two line terminators with only blank space in between. */
    private static boolean crossesBlankLine(String text, int start, int end) {
        boolean lineEnded = false;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (isLineTerminator(c)) {
                if (lineEnded) {
                    return true;
                }
                lineEnded = true;
                if (c == '\r' && i + 1 < end && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (!Character.isWhitespace(c)) {
                lineEnded = false;
            }
        }
        return false;
    }

    /** Exactly the terminators a MULTILINE {@code ^} starts a new line after — see the class javadoc. */
    private static boolean isLineTerminator(char c) {
        return c == '\n' || c == '\r' || c == '' || c == ' ' || c == ' ';
    }
}
