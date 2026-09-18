package com.automation.service.dashboard;

import com.automation.service.dashboard.DashboardWidgetMarker.Scan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A6 (fix round 1): dedicated coverage for {@link DashboardWidgetMarker}, the component most exposed
 * to hostile text — it runs on raw block output before any JSON parsing or schema check happens.
 */
class DashboardWidgetMarkerTest {

    @Test
    void no_marker_is_not_found() {
        assertThat(DashboardWidgetMarker.scan(null)).isEqualTo(Scan.NONE);
        assertThat(DashboardWidgetMarker.scan("   ")).isEqualTo(Scan.NONE);
        assertThat(DashboardWidgetMarker.scan("no marker here")).isEqualTo(Scan.NONE);
        assertThat(DashboardWidgetMarker.scan("we talked about dashboard_widget: later")).isEqualTo(Scan.NONE);
    }

    @Test
    void a_single_marker_is_found_and_not_ambiguous() {
        Scan scan = DashboardWidgetMarker.scan("dashboard_widget: {\"status\":\"GREEN\"}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void braces_inside_strings_do_not_confuse_the_brace_counter() {
        String json = "{\"title\":" + quote("{not a brace}") + "}";

        assertThat(DashboardWidgetMarker.scan("dashboard_widget: " + json).json()).isEqualTo(json);
    }

    @Test
    void an_escaped_quote_does_not_end_the_string_early() {
        String json = "{\"title\":" + quote("a \"quote\" here") + "}";

        assertThat(DashboardWidgetMarker.scan("dashboard_widget: " + json).json()).isEqualTo(json);
    }

    @Test
    void a_trailing_backslash_right_before_the_closing_quote_is_handled() {
        // The raw value ends with one literal backslash; quote() escapes it to two backslash chars,
        // so the closing quote right after is recognised as the real end of the string, not escaped.
        String json = "{\"title\":" + quote("ends with backslash\\") + "}";

        assertThat(DashboardWidgetMarker.scan("dashboard_widget: " + json).json()).isEqualTo(json);
    }

    @Test
    void unbalanced_braces_never_close() {
        Scan scan = DashboardWidgetMarker.scan("dashboard_widget: {\"title\": {\"nested\": 1}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.json()).isNull();
    }

    @Test
    void crlf_line_endings_are_tolerated() {
        Scan scan = DashboardWidgetMarker.scan("Report:\r\ndashboard_widget: {\"status\":\"GREEN\"}\r\nDone.");

        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void a_marker_at_the_very_end_of_the_output_is_found() {
        Scan scan = DashboardWidgetMarker.scan("Report.\ndashboard_widget: {\"status\":\"GREEN\"}");

        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void the_object_at_exactly_the_size_cap_is_found_one_char_more_is_rejected() {
        int overhead = "{\"t\":\"\"}".length();
        String padding = "x".repeat(DashboardWidgetMarker.MAX_JSON_CHARS - overhead);
        String atCap = "{\"t\":\"" + padding + "\"}";
        assertThat(atCap).hasSize(DashboardWidgetMarker.MAX_JSON_CHARS);

        assertThat(DashboardWidgetMarker.scan("dashboard_widget: " + atCap).json()).isEqualTo(atCap);

        String overCap = "{\"t\":\"" + padding + "x\"}";
        assertThat(overCap).hasSize(DashboardWidgetMarker.MAX_JSON_CHARS + 1);

        Scan overflowing = DashboardWidgetMarker.scan("dashboard_widget: " + overCap);
        assertThat(overflowing.found()).isTrue();
        assertThat(overflowing.json()).isNull();
    }

    // --- A2: a second marker in one output is ambiguous ---

    @Test
    void a_second_marker_in_the_same_output_is_ambiguous() {
        Scan scan = DashboardWidgetMarker.scan(
                "dashboard_widget: {\"status\":\"GREEN\"}\ndashboard_widget: {\"status\":\"RED\"}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isTrue();
        assertThat(scan.json()).isNull();
    }

    @Test
    void an_oversized_first_marker_followed_by_a_real_one_is_still_ambiguous() {
        Scan scan = DashboardWidgetMarker.scan(
                "dashboard_widget: {" + "x".repeat(20_000) + "\ndashboard_widget: {\"status\":\"RED\"}");

        assertThat(scan.ambiguous()).isTrue();
    }

    @Test
    void a_bare_mention_before_a_real_marker_does_not_make_it_ambiguous() {
        // The first occurrence has no "{" right after it, so it never counts as a real marker.
        Scan scan = DashboardWidgetMarker.scan(
                "**dashboard_widget:** wird spaeter erklaert\ndashboard_widget: {\"status\":\"GREEN\"}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    // --- A5: marker tolerance profile ---

    @Test
    void a_list_item_prefix_is_tolerated() {
        assertThat(DashboardWidgetMarker.scan("- dashboard_widget: {\"status\":\"GREEN\"}").json())
                .isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void a_blockquote_prefix_is_tolerated() {
        assertThat(DashboardWidgetMarker.scan("> dashboard_widget: {\"status\":\"GREEN\"}").json())
                .isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void indentation_is_tolerated() {
        assertThat(DashboardWidgetMarker.scan("    dashboard_widget: {\"status\":\"GREEN\"}").json())
                .isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void a_bare_fence_around_the_json_is_tolerated() {
        Scan scan = DashboardWidgetMarker.scan("dashboard_widget:\n```\n{\"status\":\"GREEN\"}\n```");

        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void a_fenced_json_block_with_a_language_tag_is_tolerated() {
        Scan scan = DashboardWidgetMarker.scan("dashboard_widget:\n```json\n{\"status\":\"GREEN\"}\n```");

        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void a_prose_mention_without_an_object_is_not_found_at_all() {
        assertThat(DashboardWidgetMarker.scan("**dashboard_widget:** wird spaeter erklaert"))
                .isEqualTo(Scan.NONE);
    }

    // --- B3 (fix round 2): ambiguity counting is unanchored ---

    @Test
    void an_unanchored_marker_elsewhere_on_a_line_still_counts_toward_ambiguity() {
        Scan scan = DashboardWidgetMarker.scan(
                "dashboard_widget: {\"status\":\"GREEN\"}\nErgebnis: dashboard_widget: {\"status\":\"RED\"}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isTrue();
        assertThat(scan.json()).isNull();
    }

    @Test
    void a_bare_unanchored_mention_without_an_object_does_not_count_toward_ambiguity() {
        // "Ergebnis: dashboard_widget:" here has no "{" or "[" after it, so it is not a real
        // occurrence at all — the sole real (anchored) marker still wins cleanly.
        Scan scan = DashboardWidgetMarker.scan(
                "dashboard_widget: {\"status\":\"GREEN\"}\nErgebnis: dashboard_widget: wird noch ergaenzt");

        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    // --- B4 (fix round 2): an array after the marker is a real, broken occurrence ---

    @Test
    void a_marker_followed_by_an_array_is_found_but_has_no_json_object() {
        Scan scan = DashboardWidgetMarker.scan("dashboard_widget: [{\"status\":\"GREEN\"}]");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.json()).isNull();
    }

    @Test
    void a_marker_followed_by_neither_brace_nor_bracket_is_still_not_found() {
        assertThat(DashboardWidgetMarker.scan("dashboard_widget: status=GREEN")).isEqualTo(Scan.NONE);
        assertThat(DashboardWidgetMarker.scan("dashboard_widget:\nstatus: GREEN")).isEqualTo(Scan.NONE);
    }

    // --- C1 (fix round 3): the unanchored and anchored patterns share one keyword sub-expression ---

    @Test
    void a_decorated_unanchored_marker_counts_toward_ambiguity_the_same_as_a_plain_one() {
        Scan scan = DashboardWidgetMarker.scan(
                "**dashboard_widget**: {\"status\":\"GREEN\"}\ndashboard_widget: {\"status\":\"RED\"}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"`dashboard_widget`", "*dashboard_widget*", "***dashboard_widget***", "dashboard-widget"})
    void every_decoration_style_counts_toward_ambiguity(String decoratedKeyword) {
        Scan scan = DashboardWidgetMarker.scan(
                "dashboard_widget: {\"status\":\"GREEN\"}\n" + decoratedKeyword + ": {\"status\":\"RED\"}");

        assertThat(scan.ambiguous()).isTrue();
    }

    @Test
    void a_single_decorated_marker_is_still_found_and_not_ambiguous() {
        Scan scan = DashboardWidgetMarker.scan("**dashboard_widget**: {\"status\":\"GREEN\"}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    // --- C2 (fix round 3): the ambiguity counter stops at a blank line ---

    @Test
    void a_prose_mention_across_a_blank_line_from_an_unrelated_object_is_not_a_real_occurrence() {
        Scan scan = DashboardWidgetMarker.scan(
                "Siehe dashboard_widget:\n\n{\"foo\":1}\n\ndashboard_widget: {\"status\":\"RED\"}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.json()).isEqualTo("{\"status\":\"RED\"}");
    }

    @Test
    void an_object_on_the_very_next_line_still_counts_toward_ambiguity() {
        // No blank line between the two markers here (unlike the case above) - both are real.
        Scan scan = DashboardWidgetMarker.scan(
                "dashboard_widget:\n{\"status\":\"GREEN\"}\ndashboard_widget: {\"status\":\"RED\"}");

        assertThat(scan.ambiguous()).isTrue();
    }

    // --- R1 (fix round 4): a line-anchored marker is real regardless of line breaks before its value;
    // an unanchored one is real only if its value is on the same line ---

    @Test
    void a_line_anchored_marker_whose_value_follows_a_blank_line_still_counts_toward_ambiguity() {
        // Round 3's regression: this first marker is line-anchored, so it must count as real even
        // though its value sits after a blank line - the counter must not silently drop it.
        Scan scan = DashboardWidgetMarker.scan(
                "Zitat:\ndashboard_widget:\n\n{\"status\":\"GREEN\"}\n\ndashboard_widget: {\"status\":\"RED\"}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n\n", "\r\n\r\n", "\n \n", "\n\t\n"})
    void every_line_break_shape_between_an_anchored_marker_and_its_value_still_counts(String separator) {
        Scan scan = DashboardWidgetMarker.scan(
                "Zitat:\ndashboard_widget:" + separator + "{\"status\":\"GREEN\"}\n\n"
                        + "dashboard_widget: {\"status\":\"RED\"}");

        assertThat(scan.ambiguous()).isTrue();
    }

    @Test
    void an_unanchored_marker_whose_value_is_on_the_same_line_still_counts_toward_ambiguity() {
        // "Ergebnis: dashboard_widget:" is not line-anchored, but its value follows immediately on
        // the same line, so it is still real.
        Scan scan = DashboardWidgetMarker.scan(
                "dashboard_widget: {\"status\":\"GREEN\"}\nErgebnis: dashboard_widget: {\"status\":\"RED\"}");

        assertThat(scan.ambiguous()).isTrue();
    }

    @Test
    void a_single_line_anchored_marker_whose_value_follows_a_blank_line_is_found_and_not_ambiguous() {
        Scan scan = DashboardWidgetMarker.scan("dashboard_widget:\n\n{\"status\":\"GREEN\"}");

        assertThat(scan.found()).isTrue();
        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void the_winner_and_the_counter_agree_on_the_round_3_regression_payload() {
        String output = "Zitat aus dem gelesenen TODO des Nutzers:\ndashboard_widget:\n\n"
                + "{\"status\":\"GREEN\",\"title\":\"Alles in Ordnung\"}\n\nMein eigener Bericht:\n"
                + "dashboard_widget: {\"status\":\"RED\",\"title\":\"12 Blocklist-Treffer\"}";

        assertThat(DashboardWidgetMarker.countEvidence(output)).isEqualTo(2);
        assertThat(DashboardWidgetMarker.scan(output).ambiguous()).isTrue();
    }

    // --- D1 (fix round 5, regression from round 4): the counter is permissive again. Round 4 counted
    // an unanchored occurrence only when its value started on the same line, so a quoted marker became
    // the sole counted one as soon as the report put its own value on the next line. Anchored now
    // always counts; unanchored counts unless a BLANK line separates the marker from its value ---

    @Test
    void a_quoted_marker_does_not_win_when_the_report_puts_its_own_value_on_the_next_line() {
        String output = "Der Nutzer schrieb:\ndashboard_widget: {\"status\":\"GREEN\",\"title\":\"Alles in Ordnung\"}\n"
                + "Ende des Zitats. Mein Ergebnis: dashboard_widget:\n"
                + "{\"status\":\"RED\",\"title\":\"12 Blocklist-Treffer\"}";

        assertThat(DashboardWidgetMarker.countEvidence(output)).isEqualTo(2);
        assertThat(DashboardWidgetMarker.scan(output).found()).isTrue();
        assertThat(DashboardWidgetMarker.scan(output).ambiguous()).isTrue();
    }

    /**
     * Every single line break between the report's own marker and its JSON — including a fenced
     * block — leaves the occurrence counted. Only a *blank* line does not (the accepted residual,
     * pinned in {@code DashboardWidgetResultParserTest}), because that is the shape a prose mention
     * of the feature has.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r", "\n```json\n", "\u2028", "\n```\n"})
    void an_unanchored_report_marker_counts_across_every_single_line_break_shape(String separator) {
        Scan scan = DashboardWidgetMarker.scan(
                "Zitat:\ndashboard_widget: {\"status\":\"GREEN\"}\n"
                        + "Mein Ergebnis: dashboard_widget:" + separator + "{\"status\":\"RED\"}");

        assertThat(scan.ambiguous()).isTrue();
    }

    /** Whatever decorates the report's own marker line, the quoted one must not become the only one. */
    @ParameterizedTest
    @ValueSource(strings = {">> ", "\u2022 ", "\u00A0", "Mein Ergebnis: ", "\u00A0\u00A0- "})
    void an_unanchored_report_marker_counts_whatever_stands_before_it(String prefix) {
        Scan scan = DashboardWidgetMarker.scan(
                "Zitat:\ndashboard_widget: {\"status\":\"GREEN\"}\n"
                        + prefix + "dashboard_widget:\n{\"status\":\"RED\"}");

        assertThat(scan.ambiguous()).isTrue();
    }

    /**
     * D4 (fix round 5): the two claims the class javadoc makes about characters. A non-breaking space
     * between the colon and the value ends the decoration walk — {@code Character.isWhitespace}
     * excludes U+00A0 — so that is not a marker at all; and U+2028/U+2029 are line terminators for
     * the blank-line test exactly as they are for the anchored pattern's {@code ^}.
     */
    @Test
    void the_line_and_whitespace_definitions_the_javadoc_states_hold() {
        assertThat(DashboardWidgetMarker.scan("dashboard_widget:\u00A0{\"status\":\"GREEN\"}"))
                .isEqualTo(Scan.NONE);
        assertThat(DashboardWidgetMarker.countEvidence(
                "Zitat: dashboard_widget:\u2028\u2029{\"status\":\"GREEN\"}")).isZero();
        assertThat(DashboardWidgetMarker.countEvidence(
                "Zitat: dashboard_widget:\u2028{\"status\":\"GREEN\"}")).isEqualTo(1);
    }

    /**
     * E2 (final fix wave): the other two shapes the javadoc now names as accepted residual risk. A
     * NEL between colon and value stops the decoration walk exactly like a non-breaking space does
     * ({@code Character.isWhitespace} crosses neither), and a double carriage return is a blank line
     * like {@code \n\n} since round 5 unified the line definition. All three need the report itself
     * to write them; an attacker controls only the quoted text.
     */
    @Test
    void the_other_two_residual_shapes_the_javadoc_names_behave_as_documented() {
        assertThat(DashboardWidgetMarker.scan("dashboard_widget:\u0085{\"status\":\"GREEN\"}"))
                .isEqualTo(Scan.NONE);
        assertThat(DashboardWidgetMarker.countEvidence(">> dashboard_widget:\r\r{\"status\":\"RED\"}")).isZero();
        // Anchored, so the same double carriage return is harmless: still a winner, still counted.
        assertThat(DashboardWidgetMarker.countEvidence("dashboard_widget:\r\r{\"status\":\"RED\"}")).isEqualTo(1);
        assertThat(DashboardWidgetMarker.scan("dashboard_widget:\r\r{\"status\":\"RED\"}").json())
                .isEqualTo("{\"status\":\"RED\"}");
    }

    @Test
    void an_unanchored_marker_whose_value_follows_a_blank_line_still_does_not_count() {
        // The C2 control, unchanged: only a *blank* line keeps an unanchored occurrence out of the
        // count, so a report documenting the feature plus an example block stays usable.
        String output = "Siehe dashboard_widget:\n\n{\"foo\":1}\n\ndashboard_widget: {\"status\":\"RED\"}";

        assertThat(DashboardWidgetMarker.countEvidence(output)).isEqualTo(1);
        assertThat(DashboardWidgetMarker.scan(output).json()).isEqualTo("{\"status\":\"RED\"}");
    }

    /**
     * E1 (final fix wave): the keyword's decoration classes are possessive, so a long contiguous run
     * of backticks or asterisks cannot make the unanchored scan backtrack quadratically. Measured
     * before the fix on this machine: 1.0 s at 16 000 characters, 3.6 s at 32 000, 13.7 s at 64 000
     * (31.4 s on the reviewer's); after it, single-digit milliseconds. The assertion's bound is
     * deliberately generous so a loaded machine cannot make it flaky — it separates "quadratic" from
     * "linear", not one implementation from another.
     */
    @ParameterizedTest
    @ValueSource(strings = {"`", "*"})
    void a_long_run_of_decoration_characters_does_not_blow_up_the_scan(String filler) {
        String output = "Bericht:\ndashboard_widget: {\"status\":\"GREEN\"}\n"
                + filler.repeat(64_000) + "\nEnde.";

        long start = System.nanoTime();
        Scan scan = DashboardWidgetMarker.scan(output);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
        assertThat(millis).as("scan of a 64000-character run of %s", filler).isLessThan(2_000);
    }

    @Test
    void a_marker_behind_a_long_decoration_run_is_still_found_and_still_counts() {
        // E1's lookbehind refuses to start a match *inside* a run of asterisks or backticks. The
        // run's first character stays a start position and the decoration class swallows the whole
        // run, so the marker end is the same one the old pattern found - verified separately against
        // the old pattern over 210 032 inputs with zero differences in the match-end sets.
        assertThat(DashboardWidgetMarker.scan("*".repeat(40) + "dashboard_widget: {\"status\":\"GREEN\"}").json())
                .isEqualTo("{\"status\":\"GREEN\"}");

        assertThat(DashboardWidgetMarker.countEvidence(
                "Zitat: " + "`".repeat(40) + "dashboard_widget: {\"status\":\"GREEN\"}\n"
                        + "dashboard_widget: {\"status\":\"RED\"}")).isEqualTo(2);
    }

    // --- B5 (fix round 2): more markdown shapes ---

    @Test
    void a_plus_prefixed_list_item_is_tolerated() {
        assertThat(DashboardWidgetMarker.scan("+ dashboard_widget: {\"status\":\"GREEN\"}").json())
                .isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void numbered_list_prefixes_up_to_two_digits_are_tolerated() {
        assertThat(DashboardWidgetMarker.scan("1. dashboard_widget: {\"status\":\"GREEN\"}").json())
                .isEqualTo("{\"status\":\"GREEN\"}");
        assertThat(DashboardWidgetMarker.scan("12) dashboard_widget: {\"status\":\"GREEN\"}").json())
                .isEqualTo("{\"status\":\"GREEN\"}");
    }

    @Test
    void a_fence_language_tag_glued_directly_to_the_json_is_tolerated() {
        Scan scan = DashboardWidgetMarker.scan("dashboard_widget:\n```json{\"status\":\"GREEN\"}\n```");

        assertThat(scan.json()).isEqualTo("{\"status\":\"GREEN\"}");
    }

    /** JSON string literal of `value`: quotes and backslashes escaped, everything non-ASCII as \\uXXXX. */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c > 0x7e) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
