package com.automation.dto.response;

import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.dto.response.DashboardWorkflowResultResponse.Result.Kpi;
import com.automation.dto.response.DashboardWorkflowResultResponse.Result.Status;
import com.automation.dto.response.DashboardWorkflowResultResponse.Result.Trend;
import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import com.automation.dto.response.DashboardWorkflowResultResponse.RunSummary;
import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardWorkflowResultResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void result_requires_a_status() {
        assertThatThrownBy(() -> new Result("t", null, List.of(), List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Result.status must not be null");
    }

    @Test
    void result_turns_null_lists_into_empty_lists() {
        Result result = new Result(null, Status.GREEN, null, null, null);

        assertThat(result.kpis()).isEmpty();
        assertThat(result.items()).isEmpty();
    }

    @Test
    void result_lists_are_immutable_copies() {
        List<String> items = new ArrayList<>(List.of("a"));
        Result result = new Result(null, Status.RED, null, items, null);
        items.add("b");

        assertThat(result.items()).containsExactly("a");
        assertThatThrownBy(() -> result.items().add("c")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.kpis().add(new Kpi("l", "v", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void result_rejects_null_list_elements() {
        List<String> items = new ArrayList<>();
        items.add(null);

        assertThatThrownBy(() -> new Result(null, Status.GREEN, null, items, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void kpi_requires_label_and_value_but_not_trend() {
        assertThatThrownBy(() -> new Kpi(null, "1", Trend.UP)).hasMessage("Kpi.label must not be null");
        assertThatThrownBy(() -> new Kpi("Tests", null, Trend.UP)).hasMessage("Kpi.value must not be null");
        assertThat(new Kpi("Tests", "12", null).trend()).isNull();
    }

    @Test
    void enums_carry_exactly_the_contract_values() {
        assertThat(Status.values()).containsExactly(Status.GREEN, Status.YELLOW, Status.RED);
        assertThat(Trend.values()).containsExactly(Trend.UP, Trend.DOWN, Trend.FLAT);
        assertThat(ResultState.values()).containsExactly(ResultState.VALID, ResultState.INVALID, ResultState.MISSING);
    }

    @Test
    void json_round_trip_keeps_every_field() throws Exception {
        DashboardWorkflowResultResponse response = new DashboardWorkflowResultResponse(
                "wf-1", "Nightly", true,
                new RunSummary("run-1", RunStatus.COMPLETED, Instant.parse("2026-09-20T01:00:00Z"),
                        Instant.parse("2026-09-20T01:02:00Z"), 120_000L, GateStatus.YELLOW),
                ResultState.VALID,
                new Result("Build", Status.YELLOW, List.of(new Kpi("Tests", "412", Trend.DOWN)),
                        List.of("2 flaky"), "Mostly fine"));

        String json = MAPPER.writeValueAsString(response);
        DashboardWorkflowResultResponse back = MAPPER.readValue(json, DashboardWorkflowResultResponse.class);

        assertThat(json).contains("\"startedAt\":\"2026-09-20T01:00:00Z\"", "\"trend\":\"DOWN\"", "\"gateStatus\":\"YELLOW\"");
        assertThat(back).isEqualTo(response);
    }

    @Test
    void unavailable_workflow_serializes_empty_run_and_result() throws Exception {
        DashboardWorkflowResultResponse response =
                new DashboardWorkflowResultResponse("wf-x", null, false, null, ResultState.MISSING, null);

        JsonNode node = MAPPER.valueToTree(response);

        assertThat(node.get("available").booleanValue()).isFalse();
        assertThat(node.get("run").isNull()).isTrue();
        assertThat(node.get("result").isNull()).isTrue();
    }

    @Test
    void deserializing_a_result_without_lists_yields_empty_lists() throws Exception {
        Result result = MAPPER.readValue("{\"status\":\"GREEN\"}", Result.class);

        assertThat(result.kpis()).isEmpty();
        assertThat(result.items()).isEmpty();
    }
}
