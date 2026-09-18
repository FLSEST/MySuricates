package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Turning block config into the contract candidate: templates resolved, blanks dropped, an
 * unresolved placeholder reported loudly. The candidate is only ever handed to
 * DashboardWidgetResultParser.validateOrThrow — no limit, no character rule and no field name is
 * decided here.
 */
class DashboardReportCandidateTest {

    private ExecutionContext context() {
        ExecutionContext ctx = ExecutionContext.builder()
                .containerId("c-1")
                .workflowRunId("run-1")
                .variables(new HashMap<>())
                .build();
        ctx.setVariable("gate", "YELLOW");
        ctx.setVariable("count", "1361");
        ctx.setVariable("empty", "");
        return ctx;
    }

    private static Map<String, Object> kpi(Object label, Object value, Object trend) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("value", value);
        if (trend != null) {
            map.put("trend", trend);
        }
        return map;
    }

    @Test
    void resolves_templates_in_every_contract_field() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "{{ gate }}");
        config.put("title", "QA {{ count }}");
        config.put("summary", "Zwei Befunde.");
        config.put("kpis", List.of(kpi("Hunts", "{{ count }}", "UP")));
        config.put("items", List.of("{{ count }} Hunts geprüft"));

        Map<String, Object> candidate = DashboardReportCandidate.build(context(), config);

        assertThat(candidate).containsEntry("status", "YELLOW").containsEntry("title", "QA 1361");
        assertThat(candidate).containsEntry("items", List.of("1361 Hunts geprüft"));
        assertThat(candidate.get("kpis")).isEqualTo(List.of(kpi("Hunts", "1361", "UP")));
    }

    @Test
    void drops_what_resolves_to_nothing_instead_of_failing() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("title", "{{ empty }}");
        config.put("summary", "   ");
        config.put("items", List.of("kept", "{{ empty }}", ""));

        Map<String, Object> candidate = DashboardReportCandidate.build(context(), config);

        assertThat(candidate).containsOnlyKeys("status", "items");
        assertThat(candidate).containsEntry("items", List.of("kept"));
    }

    /**
     * A KPI row is only dropped when it is completely empty — the editor's freshly added row. A half
     * filled one survives so the validator can say which half is missing, and a typo in a key
     * survives so the validator can say the key is unknown.
     */
    @Test
    void drops_only_a_completely_empty_kpi_row() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("kpis", List.of(
                kpi("a", "b", null),
                kpi("{{ empty }}", "{{ empty }}", null),
                kpi("{{ empty }}", "9", null),
                kpi("c", "{{ empty }}", null)));

        Map<String, Object> candidate = DashboardReportCandidate.build(context(), config);

        assertThat(candidate.get("kpis")).isEqualTo(List.of(
                kpi("a", "b", null),
                Map.of("value", "9"),
                Map.of("label", "c")));
    }

    @Test
    void drops_a_list_that_becomes_empty() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("items", List.of("{{ empty }}"));
        config.put("kpis", List.of(kpi("{{ empty }}", "{{ empty }}", null)));

        assertThat(DashboardReportCandidate.build(context(), config)).containsOnlyKeys("status");
    }

    @Test
    void an_unresolved_placeholder_fails_and_names_the_field() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "GREEN");
        summary.put("summary", "Ergebnis: {{ unknown_variable }}");

        assertThatThrownBy(() -> DashboardReportCandidate.build(context(), summary))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary")
                .hasMessageContaining("placeholder");

        Map<String, Object> inKpi = new LinkedHashMap<>();
        inKpi.put("status", "GREEN");
        inKpi.put("kpis", List.of(kpi("a", "b", null), kpi("Fehler", "{{ nope }}", null)));

        assertThatThrownBy(() -> DashboardReportCandidate.build(context(), inKpi))
                .hasMessageContaining("kpis[2].value");

        Map<String, Object> inItem = new LinkedHashMap<>();
        inItem.put("status", "GREEN");
        inItem.put("items", List.of("ok", "{{ nope }}"));

        assertThatThrownBy(() -> DashboardReportCandidate.build(context(), inItem))
                .hasMessageContaining("items[2]");
    }

    @Test
    void a_missing_or_blank_status_fails() {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("title", "no status");

        assertThatThrownBy(() -> DashboardReportCandidate.build(context(), missing))
                .hasMessageContaining("status");

        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("status", "{{ empty }}");

        assertThatThrownBy(() -> DashboardReportCandidate.build(context(), blank))
                .hasMessageContaining("status");
    }

    @Test
    void kpis_and_items_must_be_lists_and_kpi_entries_must_be_objects() {
        Map<String, Object> notAList = new LinkedHashMap<>();
        notAList.put("status", "GREEN");
        notAList.put("items", "one, two");

        assertThatThrownBy(() -> DashboardReportCandidate.build(context(), notAList))
                .hasMessageContaining("items");

        Map<String, Object> notAnObject = new LinkedHashMap<>();
        notAnObject.put("status", "GREEN");
        notAnObject.put("kpis", List.of("plain"));

        assertThatThrownBy(() -> DashboardReportCandidate.build(context(), notAnObject))
                .hasMessageContaining("kpis[1]");
    }

    @Test
    void ignores_platform_keys_at_the_top_but_passes_unknown_kpi_keys_through() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("timeout_seconds", 300);
        config.put("provider", "CLAUDE");
        Map<String, Object> typo = kpi("a", "b", null);
        typo.put("valeu", "typo");
        config.put("kpis", List.of(typo));

        Map<String, Object> candidate = DashboardReportCandidate.build(context(), config);

        assertThat(candidate).containsOnlyKeys("status", "kpis");
        assertThat(candidate.get("kpis")).isEqualTo(List.of(typo));
    }

    @Test
    void non_string_kpi_values_survive_so_the_validator_can_reject_them() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        List<Map<String, Object>> kpis = new ArrayList<>();
        kpis.add(kpi("a", 7, null));
        config.put("kpis", kpis);

        Map<String, Object> candidate = DashboardReportCandidate.build(context(), config);

        assertThat(candidate.get("kpis")).isEqualTo(List.of(kpi("a", 7, null)));
    }
}
