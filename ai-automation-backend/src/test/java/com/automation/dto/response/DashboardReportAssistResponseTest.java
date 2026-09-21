package com.automation.dto.response;

import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardReportAssistResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void carries_only_the_validated_proposal_never_raw_output() {
        assertThat(Arrays.stream(DashboardReportAssistResponse.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("runId", "runCompletedAt", "provider", "proposal");
        assertThat(DashboardReportAssistResponse.class.getRecordComponents()[3].getType()).isEqualTo(Result.class);
    }

    @Test
    void serializes_the_proposal_in_dashboard_result_shape() {
        DashboardReportAssistResponse response = new DashboardReportAssistResponse(
                "run-1", Instant.parse("2026-09-20T01:05:00Z"), "CLAUDE",
                new Result("Build", Result.Status.GREEN, null, List.of("ok"), "fine"));

        JsonNode json = MAPPER.valueToTree(response);

        assertThat(json.get("runCompletedAt").textValue()).isEqualTo("2026-09-20T01:05:00Z");
        assertThat(json.get("proposal").get("status").textValue()).isEqualTo("GREEN");
        assertThat(json.get("proposal").get("kpis").isArray()).isTrue();
        assertThat(json.get("proposal").get("kpis")).isEmpty();
        assertThat(json.get("proposal").get("items").get(0).textValue()).isEqualTo("ok");
    }

    @Test
    void run_without_end_timestamp_serializes_null() {
        JsonNode json = MAPPER.valueToTree(new DashboardReportAssistResponse(
                "run-1", null, "CLAUDE", new Result(null, Result.Status.RED, null, null, null)));

        assertThat(json.get("runCompletedAt").isNull()).isTrue();
    }
}
