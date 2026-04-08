package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationActionResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        Map<String, Object> schema = Map.of("type", "object", "required", true);
        var response = new IntegrationActionResponse("sendEmail", "Sends an email", schema);

        assertThat(response.name()).isEqualTo("sendEmail");
        assertThat(response.description()).isEqualTo("Sends an email");
        assertThat(response.inputSchema()).isEqualTo(schema);
    }

    @Test
    void should_acceptNullName_when_constructed() {
        var response = new IntegrationActionResponse(null, "desc", Map.of());

        assertThat(response.name()).isNull();
        assertThat(response.description()).isEqualTo("desc");
        assertThat(response.inputSchema()).isEmpty();
    }

    @Test
    void should_acceptNullDescription_when_constructed() {
        var response = new IntegrationActionResponse("action", null, Map.of());

        assertThat(response.name()).isEqualTo("action");
        assertThat(response.description()).isNull();
    }

    @Test
    void should_acceptNullInputSchema_when_constructed() {
        var response = new IntegrationActionResponse("action", "desc", null);

        assertThat(response.inputSchema()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var response = new IntegrationActionResponse(null, null, null);

        assertThat(response.name()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.inputSchema()).isNull();
    }

    @Test
    void should_acceptEmptySchema_when_constructed() {
        var response = new IntegrationActionResponse("action", "desc", Collections.emptyMap());

        assertThat(response.inputSchema()).isEmpty();
    }

    @Test
    void should_preserveNestedSchema_when_constructedWithComplexMap() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("to", Map.of("type", "string"))
        );
        var response = new IntegrationActionResponse("sendEmail", "Send", schema);

        assertThat(response.inputSchema()).containsKey("type");
        assertThat(response.inputSchema()).containsKey("properties");
        assertThat(response.inputSchema().get("type")).isEqualTo("object");
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        Map<String, Object> schema = Map.of("type", "string");
        var a = new IntegrationActionResponse("action", "desc", schema);
        var b = new IntegrationActionResponse("action", "desc", schema);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentName() {
        var schema = Map.<String, Object>of("type", "string");
        var a = new IntegrationActionResponse("actionA", "desc", schema);
        var b = new IntegrationActionResponse("actionB", "desc", schema);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDescription() {
        var schema = Map.<String, Object>of("type", "string");
        var a = new IntegrationActionResponse("action", "desc-a", schema);
        var b = new IntegrationActionResponse("action", "desc-b", schema);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentInputSchema() {
        var a = new IntegrationActionResponse("action", "desc", Map.of("type", "string"));
        var b = new IntegrationActionResponse("action", "desc", Map.of("type", "number"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothAllNull() {
        var a = new IntegrationActionResponse(null, null, null);
        var b = new IntegrationActionResponse(null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new IntegrationActionResponse("sendEmail", "Sends an email", Map.of("type", "object"));
        var str = response.toString();

        assertThat(str).contains("sendEmail");
        assertThat(str).contains("Sends an email");
        assertThat(str).contains("type");
        assertThat(str).contains("object");
    }

    @Test
    void should_handleNullsInToString_when_fieldsAreNull() {
        var response = new IntegrationActionResponse(null, null, null);
        var str = response.toString();

        assertThat(str).contains("null");
    }
}
