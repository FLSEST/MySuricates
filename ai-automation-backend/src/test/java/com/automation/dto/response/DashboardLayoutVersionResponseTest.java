package com.automation.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardLayoutVersionResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void layout_is_embedded_as_json_not_as_an_escaped_string() throws Exception {
        JsonNode layout = MAPPER.readTree("{\"activeViewId\":\"main\",\"views\":[{\"id\":\"main\"}]}");
        DashboardLayoutVersionResponse response = new DashboardLayoutVersionResponse(
                "v1", Instant.parse("2026-09-15T10:00:00Z"), "Laptop", "Release", 2, 1, "abc", layout);

        String json = MAPPER.writeValueAsString(response);

        assertThat(json).contains("\"layout\":{\"activeViewId\":\"main\"");
        assertThat(MAPPER.readTree(json).get("layout").get("views").get(0).get("id").textValue()).isEqualTo("main");
    }

    @Test
    void round_trips_through_json() throws Exception {
        DashboardLayoutVersionResponse response = new DashboardLayoutVersionResponse(
                "v1", Instant.parse("2026-09-15T10:00:00Z"), "Laptop", null, 2, 1, "abc",
                MAPPER.readTree("{\"activeViewId\":\"a\",\"views\":[]}"));

        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(response), DashboardLayoutVersionResponse.class))
                .isEqualTo(response);
    }
}
