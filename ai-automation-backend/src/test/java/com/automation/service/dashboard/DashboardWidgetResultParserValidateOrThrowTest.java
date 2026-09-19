package com.automation.service.dashboard;

import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The second way into the ONE contract check. The dashboard-report block holds the contract as
 * fields, not as marker text — it must reach exactly the same rules as the marker path, and its
 * exception must name the offending field without ever echoing the value (block output and config
 * values are AI-reachable text; an error message is not a place to put them).
 */
class DashboardWidgetResultParserValidateOrThrowTest {

    /** A value that must never appear in any message this class produces. */
    private static final String SECRET = "<script>alert(1)</script>";

    private static Map<String, Object> minimal() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", "GREEN");
        return map;
    }

    private static Map<String, Object> kpi(String label, String value, String trend) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("value", value);
        if (trend != null) {
            map.put("trend", trend);
        }
        return map;
    }

    @Test
    void accepts_the_same_full_contract_the_marker_path_accepts() {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("title", "Nightly QA");
        candidate.put("status", "yellow");
        candidate.put("kpis", List.of(kpi("Hunts", "1361", "UP"), kpi("Fehler", "12", null)));
        candidate.put("items", List.of("3 Hunts ohne Route", "1 Blocklist-Treffer"));
        candidate.put("summary", "Zwei Befunde, keiner blockierend.");

        Result viaFields = DashboardWidgetResultParser.validateOrThrow(candidate);
        Result viaMarker = DashboardWidgetResultParser.parse("""
                dashboard_widget: {"title": "Nightly QA", "status": "yellow",
                  "kpis": [{"label": "Hunts", "value": "1361", "trend": "UP"}, {"label": "Fehler", "value": "12"}],
                  "items": ["3 Hunts ohne Route", "1 Blocklist-Treffer"],
                  "summary": "Zwei Befunde, keiner blockierend."}
                """).result();

        assertThat(viaFields).isEqualTo(viaMarker);
        assertThat(viaFields.status()).isEqualTo(Result.Status.YELLOW);
    }

    @Test
    void accepts_the_minimal_contract() {
        Result result = DashboardWidgetResultParser.validateOrThrow(minimal());

        assertThat(result).isEqualTo(new Result(null, Result.Status.GREEN, List.of(), List.of(), null));
    }

    @Test
    void markup_stays_plain_text_inside_the_contract() {
        Map<String, Object> candidate = minimal();
        candidate.put("items", List.of(SECRET, "![pwn](https://example.invalid/p.png)"));

        Result result = DashboardWidgetResultParser.validateOrThrow(candidate);

        assertThat(result.items()).containsExactly(SECRET, "![pwn](https://example.invalid/p.png)");
    }

    @Test
    void names_the_field_and_never_the_value_for_a_text_that_is_too_long() {
        Map<String, Object> candidate = minimal();
        candidate.put("title", SECRET + "x".repeat(81));

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title")
                .hasMessageNotContaining(SECRET)
                .hasMessageNotContaining("script");
    }

    @Test
    void names_the_indexed_field_inside_items_and_kpis() {
        Map<String, Object> tooLongItem = minimal();
        tooLongItem.put("items", List.of("ok", "also ok", SECRET + "y".repeat(201)));

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(tooLongItem))
                .hasMessageContaining("items[3]")
                .hasMessageNotContaining(SECRET);

        Map<String, Object> badKpiValue = minimal();
        badKpiValue.put("kpis", List.of(kpi("a", "b", null), kpi("c", SECRET + "z".repeat(41), null)));

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(badKpiValue))
                .hasMessageContaining("kpis[2].value")
                .hasMessageNotContaining(SECRET);

        Map<String, Object> badTrend = minimal();
        badTrend.put("kpis", List.of(kpi("a", "b", "SIDEWAYS")));

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(badTrend))
                .hasMessageContaining("kpis[1].trend")
                .hasMessageNotContaining("SIDEWAYS");
    }

    @Test
    void rejects_an_unknown_field_at_both_levels() {
        Map<String, Object> topLevel = minimal();
        topLevel.put("extra", 1);

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(topLevel))
                .hasMessageContaining("dashboard_widget")
                .hasMessageContaining("unknown field");

        Map<String, Object> insideKpi = minimal();
        Map<String, Object> kpi = kpi("a", "b", null);
        kpi.put("valeu", "typo");
        insideKpi.put("kpis", List.of(kpi));

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(insideKpi))
                .hasMessageContaining("kpis[1]")
                .hasMessageContaining("unknown field");
    }

    @Test
    void rejects_a_missing_status_a_wrong_status_and_a_non_object() {
        Map<String, Object> noStatus = new LinkedHashMap<>();
        noStatus.put("title", "no status");

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(noStatus))
                .hasMessageContaining("status");

        Map<String, Object> wrongStatus = new LinkedHashMap<>();
        wrongStatus.put("status", "BLUE");

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(wrongStatus))
                .hasMessageContaining("status")
                .hasMessageNotContaining("BLUE");

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(List.of("not an object")))
                .hasMessageContaining("dashboard_widget");
        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(null))
                .hasMessageContaining("dashboard_widget");
        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow("just a string"))
                .hasMessageContaining("dashboard_widget");
    }

    @Test
    void keeps_every_limit_of_the_marker_path() {
        Map<String, Object> sevenKpis = minimal();
        List<Map<String, Object>> kpis = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            kpis.add(kpi("l" + i, "v" + i, null));
        }
        sevenKpis.put("kpis", kpis);
        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(sevenKpis))
                .hasMessageContaining("kpis");

        Map<String, Object> elevenItems = minimal();
        elevenItems.put("items", java.util.Collections.nCopies(11, "item"));
        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(elevenItems))
                .hasMessageContaining("items");

        Map<String, Object> numberAsValue = minimal();
        numberAsValue.put("kpis", List.of(Map.of("label", "a", "value", 7)));
        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(numberAsValue))
                .hasMessageContaining("kpis[1].value");

        Map<String, Object> zeroWidth = minimal();
        zeroWidth.put("summary", "zero​width");
        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(zeroWidth))
                .hasMessageContaining("summary");

        Map<String, Object> blank = minimal();
        blank.put("title", "   ");
        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(blank))
                .hasMessageContaining("title");
    }

    /** The result of validateOrThrow must round-trip through the metadata form unchanged. */
    @Test
    void the_result_goes_into_the_metadata_the_marker_path_writes() {
        Map<String, Object> candidate = minimal();
        candidate.put("title", "Report");
        candidate.put("kpis", List.of(kpi("a", "b", "FLAT")));

        Result result = DashboardWidgetResultParser.validateOrThrow(candidate);
        Map<String, Object> metadata = DashboardWidgetResultParser.toMetadata(result);

        assertThat(metadata).containsOnlyKeys("status", "title", "kpis");
        assertThat(DashboardWidgetResultParser.fromMetadata(metadata).result()).isEqualTo(result);
    }

    // --- Fix round 1: MAPPER.valueToTree ignored StreamWriteConstraints (TokenBuffer has no nesting
    // guard), so a pathologically deep candidate threw an uncaught StackOverflowError instead of the
    // documented IllegalArgumentException. Both entry points must now stay inside the contract. ---

    private static Object deeplyNested(int depth, Object leafValue) {
        Map<String, Object> deepest = new LinkedHashMap<>();
        Map<String, Object> root = deepest;
        for (int i = 1; i < depth; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            deepest.put("nested", next);
            deepest = next;
        }
        deepest.put("leaf", leafValue);
        return root;
    }

    @Test
    void rejects_a_pathologically_nested_candidate_without_a_stack_overflow() {
        Object nested = deeplyNested(10_000, SECRET);

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(nested))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dashboard_widget")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void fromMetadata_reads_the_same_pathologically_nested_value_as_invalid_not_a_crash() {
        Object nested = deeplyNested(10_000, SECRET);

        assertThat(DashboardWidgetResultParser.fromMetadata(nested).state()).isEqualTo(ResultState.INVALID);
    }

    @Test
    void keeps_a_normally_nested_candidate_valid_after_the_depth_guard() {
        Map<String, Object> candidate = minimal();
        candidate.put("kpis", List.of(kpi("a", "b", "UP")));

        Result result = DashboardWidgetResultParser.validateOrThrow(candidate);

        assertThat(result.kpis()).containsExactly(new Result.Kpi("a", "b", Result.Trend.UP));
    }

    @Test
    void also_rejects_a_pathologically_nested_candidate_at_100_000_levels() {
        Object nested = deeplyNested(100_000, SECRET);

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(nested))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dashboard_widget")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void rejects_a_self_referential_candidate_instead_of_looping_or_crashing() {
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("status", "GREEN");
        self.put("nested", self);

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(self))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dashboard_widget");
    }

    // --- Fix round 2, H1: the write-then-read round trip normalizes NaN/Infinity/byte[] into an
    // accepted string instead of the pre-round-1 "must be a string" rejection. Ordinary Boolean/
    // Integer/Double/BigDecimal candidates were never affected (verified against 2.18.5: they already
    // round-trip to a non-textual node) and need no test here. ---

    @Test
    void rejects_a_title_that_is_nan_instead_of_accepting_the_string_nan() {
        Map<String, Object> candidate = minimal();
        candidate.put("title", Double.NaN);

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dashboard_widget could not be read") // documented exception: no field named
                .hasMessageNotContaining("NaN");
    }

    @Test
    void rejects_a_kpi_value_that_is_infinity_instead_of_accepting_the_string_infinity() {
        Map<String, Object> candidate = minimal();
        Map<String, Object> kpiWithInfinity = new LinkedHashMap<>();
        kpiWithInfinity.put("label", "a");
        kpiWithInfinity.put("value", Double.POSITIVE_INFINITY);
        candidate.put("kpis", List.of(kpiWithInfinity));

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dashboard_widget could not be read") // documented exception: no field named
                .hasMessageNotContaining("Infinity");
    }

    @Test
    void rejects_a_title_that_is_a_byte_array_instead_of_accepting_its_base64_encoding() {
        Map<String, Object> candidate = minimal();
        candidate.put("title", new byte[] {65, 66, 67}); // would base64-encode to the accepted string "QUJD"

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dashboard_widget could not be read") // documented exception: no field named
                .hasMessageNotContaining("QUJD");
    }

    // --- Fix round 2, H2: the field path's read-side string-length budget used to be the marker
    // scan's 8192-char budget, so an oversized field value hit a generic "could not be read" instead of
    // the contract's own, field-naming rejection — and {{ previous_report }} routinely exceeds 8192. ---

    @Test
    void names_summary_for_an_oversized_value_instead_of_a_generic_read_error() {
        Map<String, Object> candidate = minimal();
        candidate.put("summary", "x".repeat(9_000)); // over the marker scan's old 8192 budget

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary")
                .hasMessageNotContaining("could not be read");
    }

    @Test
    void names_the_indexed_item_for_an_oversized_element_instead_of_a_generic_read_error() {
        Map<String, Object> candidate = minimal();
        candidate.put("items", List.of("ok", "y".repeat(9_000)));

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("items[2]")
                .hasMessageNotContaining("could not be read");
    }

    @Test
    void still_rejects_an_absurdly_large_value_as_unreadable() {
        Map<String, Object> candidate = minimal();
        candidate.put("summary", "z".repeat(3_000_000)); // past even the field path's own generous budget

        assertThatThrownBy(() -> DashboardWidgetResultParser.validateOrThrow(candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dashboard_widget");
    }
}
