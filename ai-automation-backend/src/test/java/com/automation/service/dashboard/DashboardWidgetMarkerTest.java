package com.automation.service.dashboard;

import com.automation.service.dashboard.DashboardWidgetMarker.Scan;
import org.junit.jupiter.api.Test;

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
