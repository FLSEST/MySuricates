package com.automation.service.integration;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationActionTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        Map<String, Object> schema = Map.of("type", "object", "required", true);
        var action = new IntegrationAction("sendEmail", "Sends an email", schema);

        assertThat(action.name()).isEqualTo("sendEmail");
        assertThat(action.description()).isEqualTo("Sends an email");
        assertThat(action.inputSchema()).isEqualTo(schema);
    }

    @Test
    void should_acceptNullName_when_constructed() {
        var action = new IntegrationAction(null, "desc", Map.of());

        assertThat(action.name()).isNull();
        assertThat(action.description()).isEqualTo("desc");
        assertThat(action.inputSchema()).isEmpty();
    }

    @Test
    void should_acceptNullDescription_when_constructed() {
        var action = new IntegrationAction("action", null, Map.of());

        assertThat(action.name()).isEqualTo("action");
        assertThat(action.description()).isNull();
    }

    @Test
    void should_acceptNullInputSchema_when_constructed() {
        var action = new IntegrationAction("action", "desc", null);

        assertThat(action.inputSchema()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var action = new IntegrationAction(null, null, null);

        assertThat(action.name()).isNull();
        assertThat(action.description()).isNull();
        assertThat(action.inputSchema()).isNull();
    }

    @Test
    void should_acceptEmptySchema_when_constructed() {
        var action = new IntegrationAction("action", "desc", Collections.emptyMap());

        assertThat(action.inputSchema()).isEmpty();
    }

    // --- Complex nested inputSchema ---

    @Test
    void should_preserveNestedSchema_when_constructedWithComplexMap() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("to", Map.of("type", "string"))
        );
        var action = new IntegrationAction("sendEmail", "Send", schema);

        assertThat(action.inputSchema()).containsKey("type");
        assertThat(action.inputSchema()).containsKey("properties");
        assertThat(action.inputSchema().get("type")).isEqualTo("object");
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_preserveDeeplyNestedSchema_when_constructedWithThreeLevelMap() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "address", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "street", Map.of("type", "string"),
                                        "zip", Map.of("type", "integer")
                                )
                        )
                )
        );
        var action = new IntegrationAction("createContact", "Creates a contact", schema);

        assertThat(action.inputSchema().get("type")).isEqualTo("object");
        var properties = (Map<String, Object>) action.inputSchema().get("properties");
        var address = (Map<String, Object>) properties.get("address");
        assertThat(address.get("type")).isEqualTo("object");
        var addressProps = (Map<String, Object>) address.get("properties");
        assertThat(addressProps).containsKeys("street", "zip");
    }

    @Test
    void should_preserveSchemaWithMixedValueTypes_when_constructed() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("name", "email"),
                "maxProperties", 10,
                "additionalProperties", false
        );
        var action = new IntegrationAction("submit", "Submit form", schema);

        assertThat(action.inputSchema().get("type")).isEqualTo("object");
        assertThat(action.inputSchema().get("required")).isEqualTo(List.of("name", "email"));
        assertThat(action.inputSchema().get("maxProperties")).isEqualTo(10);
        assertThat(action.inputSchema().get("additionalProperties")).isEqualTo(false);
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        Map<String, Object> schema = Map.of("type", "string");
        var a = new IntegrationAction("action", "desc", schema);
        var b = new IntegrationAction("action", "desc", schema);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentName() {
        var schema = Map.<String, Object>of("type", "string");
        var a = new IntegrationAction("actionA", "desc", schema);
        var b = new IntegrationAction("actionB", "desc", schema);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDescription() {
        var schema = Map.<String, Object>of("type", "string");
        var a = new IntegrationAction("action", "desc-a", schema);
        var b = new IntegrationAction("action", "desc-b", schema);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentInputSchema() {
        var a = new IntegrationAction("action", "desc", Map.of("type", "string"));
        var b = new IntegrationAction("action", "desc", Map.of("type", "number"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothAllNull() {
        var a = new IntegrationAction(null, null, null);
        var b = new IntegrationAction(null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nullVsNonNull() {
        var a = new IntegrationAction("action", "desc", Map.of());
        var b = new IntegrationAction(null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var action = new IntegrationAction("sendEmail", "Sends an email", Map.of("type", "object"));
        var str = action.toString();

        assertThat(str).contains("sendEmail");
        assertThat(str).contains("Sends an email");
        assertThat(str).contains("type");
        assertThat(str).contains("object");
    }

    @Test
    void should_handleNullsInToString_when_fieldsAreNull() {
        var action = new IntegrationAction(null, null, null);
        var str = action.toString();

        assertThat(str).contains("null");
    }
}
