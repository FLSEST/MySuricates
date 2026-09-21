package com.automation.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardLayoutVersionSummaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final DashboardLayoutVersionSummary SUMMARY = new DashboardLayoutVersionSummary(
            "v1", Instant.parse("2026-09-15T10:00:00Z"), "Laptop", null, 2, 3, "abc");

    @Test
    void serializes_without_any_layout_or_content() {
        JsonNode json = MAPPER.valueToTree(SUMMARY);

        assertThat(json.has("layout")).isFalse();
        assertThat(json.has("content")).isFalse();
        assertThat(json.get("createdAt").textValue()).isEqualTo("2026-09-15T10:00:00Z");
        assertThat(json.get("name").isNull()).isTrue();
        assertThat(json.get("viewCount").intValue()).isEqualTo(3);
    }

    @Test
    void round_trips_through_json() throws Exception {
        assertThat(MAPPER.readValue(MAPPER.writeValueAsString(SUMMARY), DashboardLayoutVersionSummary.class))
                .isEqualTo(SUMMARY);
    }
}
