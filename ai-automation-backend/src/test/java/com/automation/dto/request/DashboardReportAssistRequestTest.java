package com.automation.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardReportAssistRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void reads_the_workflow_id() throws Exception {
        assertThat(MAPPER.readValue("{\"workflowId\":\"wf-1\"}", DashboardReportAssistRequest.class).workflowId())
                .isEqualTo("wf-1");
    }

    @Test
    void missing_or_null_workflow_id_reads_as_null() throws Exception {
        assertThat(MAPPER.readValue("{}", DashboardReportAssistRequest.class).workflowId()).isNull();
        assertThat(MAPPER.readValue("{\"workflowId\":null}", DashboardReportAssistRequest.class).workflowId()).isNull();
    }

    @Test
    void round_trips_through_json() throws Exception {
        DashboardReportAssistRequest request = new DashboardReportAssistRequest("wf-2");

        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(request), DashboardReportAssistRequest.class))
                .isEqualTo(request);
    }
}
