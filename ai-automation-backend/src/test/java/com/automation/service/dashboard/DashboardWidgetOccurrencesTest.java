package com.automation.service.dashboard;

import com.automation.service.dashboard.DashboardWidgetOccurrences.Occurrence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardWidgetOccurrencesTest {

    private static Occurrence single(String output) {
        List<Occurrence> occurrences = DashboardWidgetOccurrences.in(output);
        assertThat(occurrences).hasSize(1);
        return occurrences.get(0);
    }

    @Test
    void null_and_empty_output_have_no_occurrences() {
        assertThat(DashboardWidgetOccurrences.in(null)).isEmpty();
        assertThat(DashboardWidgetOccurrences.in("")).isEmpty();
        assertThat(DashboardWidgetOccurrences.in("nothing to see here")).isEmpty();
    }

    @Test
    void line_anchored_marker_with_value_is_a_winner_and_evidence() {
        Occurrence occurrence = single("dashboard_widget: {\"status\":\"GREEN\"}");

        assertThat(occurrence.anchored()).isTrue();
        assertThat(occurrence.keywordEnd()).isEqualTo("dashboard_widget:".length());
        assertThat(occurrence.valueStart()).isEqualTo("dashboard_widget: ".length());
        assertThat(occurrence.blankLineBeforeValue()).isFalse();
        assertThat(occurrence.usableAsWinner()).isTrue();
        assertThat(occurrence.countsAsEvidence()).isTrue();
    }

    @Test
    void array_value_counts_as_a_value_too() {
        assertThat(single("dashboard_widget: [1]").hasValue()).isTrue();
    }

    @Test
    void prose_mention_without_brace_is_no_marker() {
        Occurrence occurrence = single("dashboard_widget: see the docs");

        assertThat(occurrence.hasValue()).isFalse();
        assertThat(occurrence.valueStart()).isEqualTo(-1);
        assertThat(occurrence.usableAsWinner()).isFalse();
        assertThat(occurrence.countsAsEvidence()).isFalse();
    }

    @Test
    void keyword_at_end_of_output_has_no_value() {
        assertThat(single("dashboard_widget:").hasValue()).isFalse();
    }

    @Test
    void unanchored_marker_counts_as_evidence_but_never_wins() {
        Occurrence occurrence = single("The report says dashboard_widget: {\"status\":\"RED\"}");

        assertThat(occurrence.anchored()).isFalse();
        assertThat(occurrence.countsAsEvidence()).isTrue();
        assertThat(occurrence.usableAsWinner()).isFalse();
    }

    @Test
    void unanchored_marker_beyond_a_blank_line_is_no_evidence() {
        Occurrence occurrence = single("Explained: dashboard_widget:\n\n{\"example\":true}");

        assertThat(occurrence.hasValue()).isTrue();
        assertThat(occurrence.blankLineBeforeValue()).isTrue();
        assertThat(occurrence.countsAsEvidence()).isFalse();
    }

    @Test
    void anchored_marker_beyond_a_blank_line_still_counts_and_wins() {
        Occurrence occurrence = single("dashboard_widget:\n  \n{\"status\":\"GREEN\"}");

        assertThat(occurrence.blankLineBeforeValue()).isTrue();
        assertThat(occurrence.countsAsEvidence()).isTrue();
        assertThat(occurrence.usableAsWinner()).isTrue();
    }

    @Test
    void crlf_is_a_single_line_break_not_a_blank_line() {
        Occurrence occurrence = single("quoted dashboard_widget:\r\n{\"a\":1}");

        assertThat(occurrence.blankLineBeforeValue()).isFalse();
        assertThat(occurrence.countsAsEvidence()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n\r\n", "\r\r", "\u2028\u2028", "\u2029 \u2029", "\u2028\n"})
    void every_regex_line_terminator_can_form_a_blank_line(String blank) {
        Occurrence occurrence = single("quoted dashboard_widget:" + blank + "{\"a\":1}");

        assertThat(occurrence.blankLineBeforeValue()).isTrue();
        assertThat(occurrence.countsAsEvidence()).isFalse();
    }

    @Test
    void next_line_character_stops_the_decoration_walk_like_a_no_break_space() {
        // U+0085 ends a line for the anchor but is not Character.isWhitespace, so no value follows.
        assertThat(single("dashboard_widget:\u0085{}").hasValue()).isFalse();
        assertThat(single("intro\u0085dashboard_widget: {}").anchored()).isTrue();
    }

    @Test
    void unicode_line_separator_starts_a_new_line_for_the_anchor() {
        assertThat(single("intro\u2028dashboard_widget: {}").anchored()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "- dashboard_widget: {}",
            "> dashboard_widget: {}",
            "12. dashboard_widget: {}",
            "3) dashboard_widget: {}",
            "## dashboard_widget: {}",
            "  **Dashboard-Widget**: {}",
            "`DASHBOARD_WIDGET`: {}",
            "***dashboard_widget: {}"
    })
    void tolerated_line_prefixes_and_decorations_stay_anchored(String output) {
        Occurrence occurrence = single(output);

        assertThat(occurrence.anchored()).isTrue();
        assertThat(occurrence.usableAsWinner()).isTrue();
    }

    @Test
    void three_digit_list_number_is_not_an_anchor_prefix() {
        assertThat(single("123. dashboard_widget: {}").anchored()).isFalse();
    }

    @Test
    void fence_with_language_tag_between_colon_and_value_is_skipped() {
        String output = "dashboard_widget: ```json\n{\"status\":\"GREEN\"}\n```";

        Occurrence occurrence = single(output);

        assertThat(occurrence.valueStart()).isEqualTo(output.indexOf('{'));
        assertThat(occurrence.blankLineBeforeValue()).isFalse();
    }

    @Test
    void language_tag_glued_to_the_value_is_skipped() {
        String output = "dashboard_widget: ```json{\"a\":1}```";

        assertThat(single(output).valueStart()).isEqualTo(output.indexOf('{'));
    }

    @Test
    void bold_asterisks_before_the_value_are_skipped() {
        String output = "dashboard_widget: **{\"a\":1}**";

        assertThat(single(output).valueStart()).isEqualTo(output.indexOf('{'));
    }

    @Test
    void bare_word_between_colon_and_value_is_not_swallowed() {
        assertThat(single("dashboard_widget: json {\"a\":1}").hasValue()).isFalse();
    }

    @Test
    void whitespace_before_the_colon_is_not_a_marker() {
        assertThat(DashboardWidgetOccurrences.in("dashboard_widget : {}")).isEmpty();
    }

    @Test
    void no_break_space_before_the_value_is_not_a_value() {
        assertThat(single("dashboard_widget:\u00A0{}").hasValue()).isFalse();
    }

    @Test
    void no_break_space_indentation_makes_an_unanchored_occurrence() {
        Occurrence occurrence = single("\u00A0dashboard_widget: {}");

        assertThat(occurrence.anchored()).isFalse();
        assertThat(occurrence.countsAsEvidence()).isTrue();
    }

    @Test
    void several_occurrences_come_back_in_text_order() {
        String output = "dashboard_widget: {\"a\":1}\nquoted: dashboard_widget: {\"b\":2}\ndashboard_widget: nope";

        List<Occurrence> occurrences = DashboardWidgetOccurrences.in(output);

        assertThat(occurrences).extracting(Occurrence::keywordEnd).isSorted().hasSize(3);
        assertThat(occurrences).extracting(Occurrence::anchored).containsExactly(true, false, true);
        assertThat(occurrences).extracting(Occurrence::hasValue).containsExactly(true, true, false);
    }

    @Test
    void every_winner_is_also_evidence() {
        List<String> corpus = List.of(
                "dashboard_widget: {}",
                "dashboard_widget:\n\n{}",
                "x dashboard_widget: {}",
                "x dashboard_widget:\n\n{}",
                "- `dashboard-widget`: ```json\n{}",
                "dashboard_widget: text");
        for (String output : corpus) {
            for (Occurrence occurrence : DashboardWidgetOccurrences.in(output)) {
                if (occurrence.usableAsWinner()) {
                    assertThat(occurrence.countsAsEvidence()).as(output).isTrue();
                }
            }
        }
    }

    @Test
    void long_asterisk_run_is_scanned_quickly() {
        String output = "*".repeat(64_000) + "dashboard_widget: {}";

        long start = System.nanoTime();
        List<Occurrence> occurrences = DashboardWidgetOccurrences.in(output);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(occurrences).hasSize(1);
        // Quadratic scanning took more than 10 s here before the lookbehind (final fix wave, E1).
        assertThat(millis).isLessThan(3_000);
    }
}
