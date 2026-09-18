package com.automation.executor.blocks;

import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.dashboard.DashboardWidgetResultParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The deterministic dashboard-report block. It writes exactly what the marker path writes, into
 * exactly the same run-metadata slot — and it fails loudly, naming the field, instead of putting a
 * half-understood result on the dashboard.
 */
class DashboardReportBlockExecutorTest {

    private static final String SECRET = "<script>alert(1)</script>";

    private final DashboardReportBlockExecutor executor =
            new DashboardReportBlockExecutor(ModuleRegistry.allEnabled());

    private ExecutionContext context() {
        ExecutionContext ctx = ExecutionContext.builder()
                .containerId("c-1")
                .workflowRunId("run-1")
                .variables(new HashMap<>())
                .build();
        ctx.setVariable("gate", "YELLOW");
        return ctx;
    }

    private static BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("dashboard-report").config(config).build();
    }

    @Test
    void supports_dashboardReportOnly() {
        assertThat(executor.supports("dashboard-report")).isTrue();
        assertThat(executor.supports("compress")).isFalse();
        assertThat(executor.supportedTypes()).containsExactly("dashboard-report");
    }

    @Test
    void writes_the_validated_contract_into_the_run_metadata() {
        ExecutionContext ctx = context();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "{{ gate }}");
        config.put("title", "Nightly QA");
        config.put("kpis", List.of(Map.of("label", "Hunts", "value", "1361", "trend", "UP")));
        config.put("items", List.of("3 Hunts ohne Route"));
        config.put("summary", "Zwei Befunde.");

        StepResult step = executor.execute(block(config), ctx);

        assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED);
        Object stored = ctx.getRunMetadata().get(DashboardWidgetResultParser.METADATA_KEY);
        assertThat(DashboardWidgetResultParser.fromMetadata(stored).result()).isEqualTo(new Result(
                "Nightly QA", Result.Status.YELLOW,
                List.of(new Result.Kpi("Hunts", "1361", Result.Trend.UP)),
                List.of("3 Hunts ohne Route"), "Zwei Befunde."));
    }

    @Test
    void the_step_output_reports_counts_and_carries_no_contract_value_and_no_marker() {
        ExecutionContext ctx = context();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "RED");
        config.put("title", SECRET);
        config.put("items", List.of("finding one", "finding two"));

        StepResult step = executor.execute(block(config), ctx);

        assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.getOutput()).contains("RED").contains("2");
        assertThat(step.getOutput()).doesNotContain(SECRET).doesNotContain("finding one");
        assertThat(step.getOutput().toLowerCase(java.util.Locale.ROOT)).doesNotContain("dashboard_widget");
    }

    @Test
    void a_value_outside_the_contract_fails_the_step_names_the_field_and_writes_nothing() {
        ExecutionContext ctx = context();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("summary", SECRET + "s".repeat(501));

        StepResult step = executor.execute(block(config), ctx);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains("summary").doesNotContain(SECRET);
        assertThat(ctx.getRunMetadata()).doesNotContainKey(DashboardWidgetResultParser.METADATA_KEY);
    }

    @Test
    void an_unresolved_placeholder_fails_the_step() {
        ExecutionContext ctx = context();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("title", "{{ never_set }}");

        StepResult step = executor.execute(block(config), ctx);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains("title");
        assertThat(ctx.getRunMetadata()).doesNotContainKey(DashboardWidgetResultParser.METADATA_KEY);
    }

    @Test
    void the_last_block_of_a_run_wins() {
        ExecutionContext ctx = context();
        executor.execute(block(Map.of("status", "GREEN", "title", "first")), ctx);
        executor.execute(block(Map.of("status", "RED", "title", "last")), ctx);

        Object stored = ctx.getRunMetadata().get(DashboardWidgetResultParser.METADATA_KEY);
        assertThat(DashboardWidgetResultParser.fromMetadata(stored).result().title()).isEqualTo("last");
    }

    @Test
    void refuses_to_run_when_the_module_is_off() {
        ModuleRegistry off = ModuleRegistry.of(
                Map.of("preset", "full", "dashboard-report", "false"), Map.of());
        DashboardReportBlockExecutor gated = new DashboardReportBlockExecutor(off);
        ExecutionContext ctx = context();

        assertThatThrownBy(() -> gated.execute(block(Map.of("status", "GREEN")), ctx))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining(ModuleCatalog.DASHBOARD_REPORT.key());
        assertThat(ctx.getRunMetadata()).isEmpty();
    }

    @Test
    void a_step_index_is_taken_and_the_step_is_timed() {
        ExecutionContext ctx = context();

        StepResult step = executor.execute(block(Map.of("status", "GREEN")), ctx);

        assertThat(step.getStepIndex()).isZero();
        assertThat(step.getStartedAt()).isNotNull();
        assertThat(step.getCompletedAt()).isNotNull();
        assertThat(step.getDurationMs()).isNotNull();
    }

    // ---- Fix round 1 -------------------------------------------------------------------------

    /**
     * G1 (security): reproduction from the review. A KPI object's own key is arbitrary block config,
     * not the contract — this key carries a fabricated marker line plus an unresolved placeholder so
     * the block fails, and the failed step's output must not expose either the marker text or the
     * key that carried it. Without the fix, a run-end marker scan (which also scans failed steps'
     * output) would have adopted this as the run's dashboard report.
     */
    @Test
    void a_malicious_kpi_key_cannot_plant_a_marker_in_the_failed_steps_output() {
        ExecutionContext ctx = context();
        Map<String, Object> maliciousKpi = new LinkedHashMap<>();
        maliciousKpi.put("x\ndashboard_widget: {\"status\":\"RED\",\"title\":\"Gekapert\"}\n",
                "{{ nie_gesetzt }}");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("kpis", List.of(maliciousKpi));

        StepResult step = executor.execute(block(config), ctx);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput().toLowerCase(java.util.Locale.ROOT)).doesNotContain("dashboard_widget");
        assertThat(step.getOutput()).doesNotContain("Gekapert").doesNotContain("x\ndashboard_widget");
        assertThat(ctx.getRunMetadata()).doesNotContainKey(DashboardWidgetResultParser.METADATA_KEY);
    }

    /** G3: "status is required" is the validator's rule; the end-to-end failure still names the field. */
    @Test
    void a_missing_status_fails_via_the_shared_validator() {
        ExecutionContext ctx = context();

        StepResult step = executor.execute(block(Map.of("title", "no status")), ctx);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains("status");
    }

    /** G3: a status that resolves to blank is left out by the candidate and still rejected by the validator. */
    @Test
    void a_blank_status_fails_via_the_shared_validator() {
        ExecutionContext ctx = context();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "   ");

        StepResult step = executor.execute(block(config), ctx);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains("status");
    }

    /** G3: "items must be a list" is the validator's rule; the end-to-end failure still names the field. */
    @Test
    void a_non_list_items_value_fails_via_the_shared_validator() {
        ExecutionContext ctx = context();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("items", "one, two");

        StepResult step = executor.execute(block(config), ctx);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains("items");
    }

    /** G3: "kpis[1] must be an object" is the validator's rule; the end-to-end failure still names the field. */
    @Test
    void a_non_object_kpi_entry_fails_via_the_shared_validator() {
        ExecutionContext ctx = context();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("kpis", List.of("plain"));

        StepResult step = executor.execute(block(config), ctx);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).contains("kpis[1]");
    }

    /** G6: the step's input field is a constant, never the block's own (possibly sensitive) config. */
    @Test
    void the_step_input_carries_no_content() {
        ExecutionContext ctx = context();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("status", "GREEN");
        config.put("title", SECRET);

        StepResult step = executor.execute(block(config), ctx);

        assertThat(step.getInput()).isEqualTo("dashboard-report").doesNotContain(SECRET);
    }

    /**
     * G6: the module gate must be read on every call, not cached at construction — a runtime toggle
     * (Settings → Modules) must take effect on the very next run without restarting the executor bean.
     */
    @Test
    void the_module_gate_is_read_on_every_call() {
        ModuleRegistry registry = ModuleRegistry.of(Map.of("preset", "full", "ui-editable", "true"), Map.of());
        DashboardReportBlockExecutor toggled = new DashboardReportBlockExecutor(registry);
        ExecutionContext ctx = context();

        StepResult before = toggled.execute(block(Map.of("status", "GREEN")), ctx);
        assertThat(before.getStatus()).isEqualTo(StepStatus.COMPLETED);

        registry.applyUiOverrides(Map.of(ModuleCatalog.DASHBOARD_REPORT, false));

        assertThatThrownBy(() -> toggled.execute(block(Map.of("status", "GREEN")), ctx))
                .isInstanceOf(WorkflowExecutionException.class);
    }

    /** G6: a later block that fails must not wipe out a report an earlier block of the same run already wrote. */
    @Test
    void a_later_failing_report_does_not_erase_an_earlier_successful_one() {
        ExecutionContext ctx = context();
        executor.execute(block(Map.of("status", "GREEN", "title", "first success")), ctx);

        Map<String, Object> broken = new LinkedHashMap<>();
        broken.put("status", "GREEN");
        broken.put("title", "{{ never_set }}");
        StepResult second = executor.execute(block(broken), ctx);

        assertThat(second.getStatus()).isEqualTo(StepStatus.FAILED);
        Object stored = ctx.getRunMetadata().get(DashboardWidgetResultParser.METADATA_KEY);
        assertThat(DashboardWidgetResultParser.fromMetadata(stored).result().title()).isEqualTo("first success");
    }
}
