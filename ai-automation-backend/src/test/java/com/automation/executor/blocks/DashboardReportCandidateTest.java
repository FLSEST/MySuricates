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
        config.put("kpis", List.of(kpi("Orders", "{{ count }}", "UP")));
        config.put("items", List.of("{{ count }} Bestellungen geprüft"));

        Map<String, Object> candidate = DashboardReportCandidate.build(context(), config);

        assertThat(candidate).containsEntry("status", "YELLOW").containsEntry("title", "QA 1361");
        assertThat(candidate).containsEntry("items", List.of("1361 Bestellungen geprüft"));
        assertThat(candidate.get("kpis")).isEqualTo(List.of(kpi("Orders", "1361", "UP")));
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

    /**
     * "status is required" is the validator's rule (fix round 1, G3) — Candidate does not duplicate
     * it, it just leaves a missing or blank status out like any other field that resolved to nothing.
     * {@link DashboardReportBlockExecutorTest#a_missing_status_fails_via_the_shared_validator} and
     * {@code #a_blank_status_fails_via_the_shared_validator} prove the end-to-end failure survives.
     */
    @Test
    void a_missing_or_blank_status_is_simply_left_out_for_the_validator_to_require() {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("title", "no status");

        assertThat(DashboardReportCandidate.build(context(), missing)).doesNotContainKey("status");

        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("status", "{{ empty }}");

        assertThat(DashboardReportCandidate.build(context(), blank)).doesNotContainKey("status");
    }

    /**
     * "must be a list" / "must be an object" are the validator's rules (fix round 1, G3) — Candidate
     * does not duplicate them, it passes the offending value through unchanged. See
     * {@link DashboardReportBlockExecutorTest#a_non_list_items_value_fails_via_the_shared_validator}
     * and {@code #a_non_object_kpi_entry_fails_via_the_shared_validator} for the end-to-end failure.
     */
    @Test
    void a_non_list_or_non_object_value_passes_through_unchanged() {
        Map<String, Object> notAList = new LinkedHashMap<>();
        notAList.put("status", "GREEN");
        notAList.put("items", "one, two");

        assertThat(DashboardReportCandidate.build(context(), notAList)).containsEntry("items", "one, two");

        Map<String, Object> notAnObject = new LinkedHashMap<>();
        notAnObject.put("status", "GREEN");
        notAnObject.put("kpis", List.of("plain"));

        assertThat(DashboardReportCandidate.build(context(), notAnObject).get("kpis"))
                .isEqualTo(List.of("plain"));
    }

    /**
     * Fix round 1, G4: a non-string top-level value is passed through unchanged, exactly like inside
     * a KPI — never forced through {@code toString()}, or the validator would never get to say
     * "title must be a string".
     */
    @Test
    void a_non_string_top_level_value_passes_through_unchanged() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("title", Map.of("a", "b"));

        assertThat(DashboardReportCandidate.build(context(), config)).containsEntry("title", Map.of("a", "b"));
    }

    /**
     * Fix round 1, G2: the unresolved-placeholder check runs on the raw template, not on the
     * substituted result — a variable whose own value happens to contain {@code {{ … }}} (ordinary
     * shell or AI output) must not be mistaken for an unresolved placeholder.
     */
    @Test
    void a_value_that_itself_contains_placeholder_syntax_after_resolving_does_not_fail() {
        ExecutionContext ctx = context();
        ctx.setVariable("finding", "Platzhalter {{ order_count }} fehlt in der Vorlage");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("items", List.of("{{ finding }}"));

        Map<String, Object> candidate = DashboardReportCandidate.build(ctx, config);

        assertThat(candidate.get("items"))
                .isEqualTo(List.of("Platzhalter {{ order_count }} fehlt in der Vorlage"));
    }

    /**
     * Fix round 1, G1 (security): a KPI object's own key is block config, not the contract — an
     * unresolved-placeholder message may name the position ({@code kpis[1]}) or a known contract field
     * ({@code kpis[1].value}), but never echo the raw key itself. Reproduction from the review: the
     * key alone carries a fabricated marker line, which a failed step's output must not expose.
     */
    @Test
    void an_unknown_kpi_key_never_reaches_the_unresolved_placeholder_message() {
        Map<String, Object> maliciousKpi = new LinkedHashMap<>();
        maliciousKpi.put("x\ndashboard_widget: {\"status\":\"RED\",\"title\":\"Gekapert\"}\n",
                "{{ nie_gesetzt }}");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("kpis", List.of(maliciousKpi));

        assertThatThrownBy(() -> DashboardReportCandidate.build(context(), config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kpis[1]")
                .hasMessageNotContaining("dashboard_widget")
                .hasMessageNotContaining("Gekapert");
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

    // ---- Fix round 2 --------------------------------------------------------------------------

    /**
     * I1: the old hand copy of {@code TemplateResolver.lookup} treated a <em>defined</em> variable
     * holding {@code null} as unresolved — real (e.g. a workspace SHA that a {@code Map.get} may
     * legitimately return {@code null} for) and different from what plain substitution does
     * ({@code String.valueOf(null)} → the literal text {@code "null"}). Asking
     * {@link ExecutionContext#resolvesPlaceholder} instead of re-deriving the answer keeps the two in
     * sync by construction.
     */
    @Test
    void a_defined_variable_holding_null_still_resolves() {
        ExecutionContext ctx = context();
        ctx.setVariable("workspace_source_sha", null);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("title", "Build {{ workspace_source_sha }}");

        Map<String, Object> candidate = DashboardReportCandidate.build(ctx, config);

        assertThat(candidate).containsEntry("title", "Build null");
    }

    /**
     * I1: {@code TemplateResolver.lookup} falls back to a variable literally named like the
     * placeholder ({@code "block.x.output"}) when no block named {@code x} recorded output — the old
     * hand copy stopped after the block-output check and never took this fallback.
     */
    @Test
    void a_variable_named_like_a_block_output_placeholder_still_resolves() {
        ExecutionContext ctx = context();
        ctx.setVariable("block.x.output", "fallback value");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("title", "{{ block.x.output }}");

        Map<String, Object> candidate = DashboardReportCandidate.build(ctx, config);

        assertThat(candidate).containsEntry("title", "fallback value");
    }

    /**
     * I2 (regression): a bare {@code null} kpi entry must reach the validator unchanged, exactly like
     * any other non-object entry — dropping it silently would accept an input the marker path
     * rejects (see {@link DashboardReportBlockExecutorTest#a_null_kpi_entry_fails_via_the_shared_validator}
     * for the end-to-end failure).
     */
    @Test
    void a_null_kpi_entry_is_passed_through_unchanged_not_dropped() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        List<Object> kpis = new ArrayList<>();
        kpis.add(null);
        config.put("kpis", kpis);

        Map<String, Object> candidate = DashboardReportCandidate.build(context(), config);

        assertThat(candidate.get("kpis")).isEqualTo(java.util.Collections.singletonList(null));
    }

    /**
     * The same rule for {@code items}: a bare {@code null} entry used to be dropped silently here
     * while the marker path rejected the very same input ({@code {"items":[null]}} → "items[1] is
     * required"). Whether an entry survives must never decide the outcome — see
     * {@link DashboardReportBlockExecutorTest#a_null_item_entry_fails_via_the_shared_validator}.
     */
    @Test
    void a_null_items_entry_is_passed_through_unchanged_not_dropped() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        List<Object> items = new ArrayList<>();
        items.add("a finding");
        items.add(null);
        config.put("items", items);

        Map<String, Object> candidate = DashboardReportCandidate.build(context(), config);

        assertThat(candidate.get("items")).isEqualTo(java.util.Arrays.asList("a finding", null));
    }

    /**
     * I3 (regression): dropping a blank row shifts every later survivor's position in the list the
     * validator sees. {@link DashboardReportCandidate#buildTracked} records each survivor's original,
     * author-facing config position so a validator message can be translated back — see
     * {@link #renumber_maps_the_validators_post_drop_index_back_to_the_authors_config_position} for
     * the translation itself and
     * {@link DashboardReportBlockExecutorTest#a_blank_kpi_row_before_an_invalid_one_does_not_shift_the_authors_numbering}
     * for the end-to-end result.
     */
    @Test
    void buildTracked_records_each_survivors_original_config_position() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("kpis", List.of(
                kpi("{{ empty }}", "{{ empty }}", null), // 1: dropped (blank row)
                kpi("a", "b", null),                      // 2: kept
                kpi("{{ empty }}", "{{ empty }}", null), // 3: dropped (blank row)
                "plain"));                                 // 4: kept (passthrough, not an object)

        DashboardReportCandidate.Built built = DashboardReportCandidate.buildTracked(context(), config);

        assertThat(built.positions().get("kpis")).containsExactly(2, 4);
    }

    @Test
    void renumber_maps_the_validators_post_drop_index_back_to_the_authors_config_position() {
        Map<String, List<Integer>> positions = Map.of("kpis", List.of(2, 4));

        assertThat(DashboardReportCandidate.renumber("kpis[1] must be an object", positions))
                .isEqualTo("kpis[2] must be an object");
        assertThat(DashboardReportCandidate.renumber("kpis[2].label is required", positions))
                .isEqualTo("kpis[4].label is required");
    }

    @Test
    void renumber_leaves_an_index_it_has_no_record_of_unchanged() {
        assertThat(DashboardReportCandidate.renumber("items[1] must be a string", Map.of()))
                .isEqualTo("items[1] must be a string");
    }

    /**
     * I4 (was already fixed at the top level in fix round 1, G4 — items entries still forced every
     * value through {@code toString()}): a non-string item passes through unchanged so the validator
     * can say "items[n] must be a string" itself, instead of accepting a stringified map or number the
     * marker path would reject.
     */
    @Test
    void non_string_item_values_survive_so_the_validator_can_reject_them() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("items", List.of(Map.of("a", "b"), 42));

        Map<String, Object> candidate = DashboardReportCandidate.build(context(), config);

        assertThat(candidate.get("items")).isEqualTo(List.of(Map.of("a", "b"), 42));
    }
}
