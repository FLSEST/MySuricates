package com.automation.service.dashboard;

import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import com.automation.service.dashboard.DashboardWidgetResultParser.ParseResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardWidgetResultParserTest {

    private static final String FULL = """
            Report of the run.

            dashboard_widget: {"title": "Nightly QA", "status": "YELLOW",
              "kpis": [{"label": "Hunts", "value": "1361", "trend": "UP"}, {"label": "Fehler", "value": "12"}],
              "items": ["3 Hunts ohne Route", "1 Blocklist-Treffer"],
              "summary": "Zwei Befunde, keiner blockierend."}

            Done.
            """;

    @Test
    void reads_a_full_contract_from_a_marker_line() {
        ParseResult parsed = DashboardWidgetResultParser.parse(FULL);

        assertThat(parsed.state()).isEqualTo(ResultState.VALID);
        assertThat(parsed.result()).isEqualTo(new Result("Nightly QA", Result.Status.YELLOW,
                List.of(new Result.Kpi("Hunts", "1361", Result.Trend.UP), new Result.Kpi("Fehler", "12", null)),
                List.of("3 Hunts ohne Route", "1 Blocklist-Treffer"),
                "Zwei Befunde, keiner blockierend."));
    }

    @Test
    void accepts_the_minimal_contract_and_markdown_decorated_markers() {
        assertThat(DashboardWidgetResultParser.parse("**dashboard_widget:** {\"status\":\"green\"}").result())
                .isEqualTo(new Result(null, Result.Status.GREEN, List.of(), List.of(), null));
        assertThat(DashboardWidgetResultParser.parse("### `dashboard-widget`: {\"status\":\"RED\"}").state())
                .isEqualTo(ResultState.VALID);
    }

    @Test
    void without_a_marker_nothing_is_reported() {
        assertThat(DashboardWidgetResultParser.parse("gate_status: GREEN").state()).isEqualTo(ResultState.MISSING);
        assertThat(DashboardWidgetResultParser.parse(null).state()).isEqualTo(ResultState.MISSING);
        assertThat(DashboardWidgetResultParser.parse("   ").state()).isEqualTo(ResultState.MISSING);
        // The word alone, without a marker line, is not a marker.
        assertThat(DashboardWidgetResultParser.parse("we talked about dashboard_widget: later").state())
                .isEqualTo(ResultState.MISSING);
    }

    /**
     * Injection fixtures (spec E3): markup, links, images and entities are ordinary text inside the
     * contract — they are stored unchanged and rendered as text nodes. Nothing is filtered here,
     * because filtering markup is exactly the arms race the design avoids.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "<script>alert(1)</script>",
            "<img src=x onerror=alert(1)>",
            "javascript:alert(1)",
            "![pwn](https://example.invalid/p.png)",
            "[klick](https://example.invalid)",
            "&lt;b&gt;fett&lt;/b&gt;",
            "</div><iframe src=https://example.invalid>",
    })
    void markup_stays_plain_text_inside_the_contract(String hostile) {
        ParseResult parsed = DashboardWidgetResultParser.parse(
                "dashboard_widget: {\"status\":\"RED\",\"items\":[" + quote(hostile) + "]}");

        assertThat(parsed.state()).isEqualTo(ResultState.VALID);
        assertThat(parsed.result().items()).containsExactly(hostile);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"BLUE\"}",
            "{\"status\":\"GREEN\",\"extra\":1}",
            "{\"title\":\"no status\"}",
            "{\"status\":\"GREEN\",\"kpis\":[{\"label\":\"a\",\"value\":\"b\",\"oops\":1}]}",
            "{\"status\":\"GREEN\",\"kpis\":[{\"label\":\"a\"}]}",
            "{\"status\":\"GREEN\",\"kpis\":\"not an array\"}",
            "{\"status\":\"GREEN\",\"items\":[{\"nested\":true}]}",
            "{\"status\":\"GREEN\",\"summary\":42}",
            "{\"status\":\"GREEN\",\"kpis\":[{\"label\":\"a\",\"value\":7}]}",
            "{\"status\":\"GREEN\",\"title\":\"\"}",
            "{\"status\":\"GREEN\",\"items\":[\"ok\",\"\"]}",
            "[\"not an object\"]",
            "{\"status\":\"GREEN\"",
            "not json at all",
    })
    void a_broken_contract_is_invalid_not_missing(String payload) {
        assertThat(DashboardWidgetResultParser.parse("dashboard_widget: " + payload).state())
                .isEqualTo(ResultState.INVALID);
    }

    @Test
    void rejects_oversized_fields_counts_and_payloads() {
        assertThat(state("{\"status\":\"GREEN\",\"title\":" + quote("t".repeat(81)) + "}")).isEqualTo(ResultState.INVALID);
        assertThat(state("{\"status\":\"GREEN\",\"title\":" + quote("t".repeat(80)) + "}")).isEqualTo(ResultState.VALID);
        assertThat(state("{\"status\":\"GREEN\",\"summary\":" + quote("s".repeat(501)) + "}")).isEqualTo(ResultState.INVALID);
        assertThat(state("{\"status\":\"GREEN\",\"items\":[" + quote("i".repeat(201)) + "]}")).isEqualTo(ResultState.INVALID);
        assertThat(state("{\"status\":\"GREEN\",\"kpis\":[{\"label\":" + quote("l".repeat(41)) + ",\"value\":\"v\"}]}"))
                .isEqualTo(ResultState.INVALID);
        assertThat(state("{\"status\":\"GREEN\",\"kpis\":[" + kpis(7) + "]}")).isEqualTo(ResultState.INVALID);
        assertThat(state("{\"status\":\"GREEN\",\"kpis\":[" + kpis(6) + "]}")).isEqualTo(ResultState.VALID);
        assertThat(state("{\"status\":\"GREEN\",\"items\":[" + items(11) + "]}")).isEqualTo(ResultState.INVALID);
        assertThat(state("{\"status\":\"GREEN\",\"items\":[" + items(10) + "]}")).isEqualTo(ResultState.VALID);
        // Far beyond the scan cap: the parser stops reading instead of building a huge tree.
        assertThat(state("{\"status\":\"GREEN\",\"summary\":" + quote("x".repeat(20_000)) + "}"))
                .isEqualTo(ResultState.INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"line\nbreak", "tab\tstop", "zero​width", "bidi‮override", "bom﻿text",
            "privateuse", "bell"})
    void rejects_control_and_invisible_characters(String text) {
        assertThat(state("{\"status\":\"GREEN\",\"items\":[" + quote(text) + "]}")).isEqualTo(ResultState.INVALID);
    }

    @Test
    void the_last_step_with_a_marker_wins() {
        ParseResult parsed = DashboardWidgetResultParser.parseRun(List.of(
                "dashboard_widget: {\"status\":\"GREEN\",\"title\":\"first\"}",
                "no marker here",
                "dashboard_widget: {\"status\":\"RED\",\"title\":\"last\"}"));

        assertThat(parsed.result().title()).isEqualTo("last");
        assertThat(DashboardWidgetResultParser.parseRun(List.of("a", "b")).state()).isEqualTo(ResultState.MISSING);
        // A later broken marker replaces an earlier good one: the run's last word counts.
        assertThat(DashboardWidgetResultParser.parseRun(List.of(
                "dashboard_widget: {\"status\":\"GREEN\"}", "dashboard_widget: {\"status\":\"PINK\"}")).state())
                .isEqualTo(ResultState.INVALID);
    }

    @Test
    void metadata_round_trip_keeps_the_contract_and_drops_empty_parts() {
        Result result = DashboardWidgetResultParser.parse(FULL).result();

        Map<String, Object> metadata = DashboardWidgetResultParser.toMetadata(result);

        assertThat(metadata).containsOnlyKeys("status", "title", "kpis", "items", "summary");
        assertThat(DashboardWidgetResultParser.fromMetadata(metadata).result()).isEqualTo(result);
        assertThat(DashboardWidgetResultParser.toMetadata(new Result(null, Result.Status.GREEN, List.of(), List.of(), null)))
                .containsExactly(Map.entry("status", "GREEN"));
    }

    @Test
    void stored_metadata_is_validated_again_on_the_way_out() {
        assertThat(DashboardWidgetResultParser.fromMetadata(null).state()).isEqualTo(ResultState.MISSING);
        assertThat(DashboardWidgetResultParser.fromMetadata("just a string").state()).isEqualTo(ResultState.INVALID);
        assertThat(DashboardWidgetResultParser.fromMetadata(Map.of("status", "GREEN", "extra", 1)).state())
                .isEqualTo(ResultState.INVALID);
        assertThat(DashboardWidgetResultParser.fromMetadata(Map.of("status", "GREEN", "items", List.of("ok"))).state())
                .isEqualTo(ResultState.VALID);
    }

    private static ResultState state(String payload) {
        return DashboardWidgetResultParser.parse("dashboard_widget: " + payload).state();
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

    private static String kpis(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "{\"label\":\"l\",\"value\":\"v\"}"));
    }

    private static String items(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "\"item\""));
    }
}
