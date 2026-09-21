package com.automation.service.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardWidgetJsonTest {

    private static String nestedArrays(int depth) {
        return "[".repeat(depth) + "]".repeat(depth);
    }

    private static Object nestedMaps(int depth) {
        Map<String, Object> root = new HashMap<>();
        Map<String, Object> current = root;
        for (int i = 1; i < depth; i++) {
            Map<String, Object> next = new HashMap<>();
            current.put("n", next);
            current = next;
        }
        return root;
    }

    @Test
    void marker_path_reads_plain_json() throws IOException {
        JsonNode node = DashboardWidgetJson.markerTreeOf("{\"status\":\"GREEN\",\"items\":[\"a\"]}");

        assertThat(node.get("status").textValue()).isEqualTo("GREEN");
        assertThat(node.get("items").get(0).textValue()).isEqualTo("a");
    }

    @Test
    void marker_path_rejects_duplicate_keys_and_trailing_tokens() {
        assertThatThrownBy(() -> DashboardWidgetJson.markerTreeOf("{\"status\":\"GREEN\",\"status\":\"RED\"}"))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> DashboardWidgetJson.markerTreeOf("{\"status\":\"GREEN\"} {}"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void marker_path_bounds_nesting_depth() throws IOException {
        assertThat(DashboardWidgetJson.markerTreeOf(nestedArrays(DashboardWidgetJson.MAX_NESTING_DEPTH))).isNotNull();
        assertThatThrownBy(() -> DashboardWidgetJson.markerTreeOf(nestedArrays(DashboardWidgetJson.MAX_NESTING_DEPTH + 1)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void marker_path_keeps_the_marker_scan_string_budget() {
        String tooLong = "{\"summary\":\"" + "x".repeat(DashboardWidgetMarker.MAX_JSON_CHARS + 1) + "\"}";

        assertThatThrownBy(() -> DashboardWidgetJson.markerTreeOf(tooLong)).isInstanceOf(IOException.class);
    }

    @Test
    void field_path_converts_a_map_candidate_to_a_tree() {
        JsonNode node = DashboardWidgetJson.fieldTreeOf(Map.of("status", "GREEN", "kpis", List.of(Map.of("label", "x"))));

        assertThat(node.get("status").textValue()).isEqualTo("GREEN");
        assertThat(node.get("kpis").get(0).get("label").textValue()).isEqualTo("x");
    }

    @Test
    void field_path_keeps_numbers_non_textual() {
        JsonNode node = DashboardWidgetJson.fieldTreeOf(Map.of("value", 42));

        assertThat(node.get("value").isTextual()).isFalse();
        assertThat(node.get("value").intValue()).isEqualTo(42);
    }

    @Test
    void field_path_allows_strings_far_beyond_the_marker_budget() {
        String longSummary = "x".repeat(DashboardWidgetMarker.MAX_JSON_CHARS * 2);

        assertThat(DashboardWidgetJson.fieldTreeOf(Map.of("summary", longSummary)).get("summary").textValue())
                .hasSize(longSummary.length());
    }

    @Test
    void field_path_rejects_non_finite_doubles_instead_of_quoting_them() {
        assertThatThrownBy(() -> DashboardWidgetJson.fieldTreeOf(Map.of("value", Double.NaN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dashboard_widget could not be read");
        assertThatThrownBy(() -> DashboardWidgetJson.fieldTreeOf(Map.of("value", Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void field_path_rejects_byte_arrays_instead_of_base64_strings() {
        assertThatThrownBy(() -> DashboardWidgetJson.fieldTreeOf(Map.of("value", new byte[]{1, 2, 3})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dashboard_widget could not be read");
    }

    @Test
    void field_path_bounds_nesting_depth_with_an_illegal_argument_not_an_error() {
        assertThat(DashboardWidgetJson.fieldTreeOf(nestedMaps(10))).isNotNull();
        assertThatThrownBy(() -> DashboardWidgetJson.fieldTreeOf(nestedMaps(10_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dashboard_widget could not be read");
    }

    @Test
    void field_path_message_never_echoes_the_candidate() {
        assertThatThrownBy(() -> DashboardWidgetJson.fieldTreeOf(Map.of("secret-value", Double.NaN)))
                .hasMessageNotContaining("secret-value");
    }
}
