package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        Map<String, Object> inputSchema = Map.of("type", "object", "required", true);
        Map<String, Object> outputSchema = Map.of("type", "string");
        var response = new CapabilityResponse("cap-1", "doSomething", "Does something", inputSchema, outputSchema, true);

        assertThat(response.id()).isEqualTo("cap-1");
        assertThat(response.name()).isEqualTo("doSomething");
        assertThat(response.description()).isEqualTo("Does something");
        assertThat(response.inputSchema()).isEqualTo(inputSchema);
        assertThat(response.outputSchema()).isEqualTo(outputSchema);
        assertThat(response.async()).isTrue();
    }

    @Test
    void should_returnFalseAsync_when_constructedWithFalse() {
        var response = new CapabilityResponse("cap-2", "sync-action", "Sync", Map.of(), Map.of(), false);

        assertThat(response.async()).isFalse();
    }

    @Test
    void should_acceptNullId_when_constructed() {
        var response = new CapabilityResponse(null, "name", "desc", Map.of(), Map.of(), false);

        assertThat(response.id()).isNull();
        assertThat(response.name()).isEqualTo("name");
    }

    @Test
    void should_acceptNullName_when_constructed() {
        var response = new CapabilityResponse("cap-1", null, "desc", Map.of(), Map.of(), false);

        assertThat(response.name()).isNull();
        assertThat(response.description()).isEqualTo("desc");
    }

    @Test
    void should_acceptNullDescription_when_constructed() {
        var response = new CapabilityResponse("cap-1", "name", null, Map.of(), Map.of(), false);

        assertThat(response.description()).isNull();
    }

    @Test
    void should_acceptNullInputSchema_when_constructed() {
        var response = new CapabilityResponse("cap-1", "name", "desc", null, Map.of(), false);

        assertThat(response.inputSchema()).isNull();
    }

    @Test
    void should_acceptNullOutputSchema_when_constructed() {
        var response = new CapabilityResponse("cap-1", "name", "desc", Map.of(), null, false);

        assertThat(response.outputSchema()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var response = new CapabilityResponse(null, null, null, null, null, false);

        assertThat(response.id()).isNull();
        assertThat(response.name()).isNull();
        assertThat(response.description()).isNull();
        assertThat(response.inputSchema()).isNull();
        assertThat(response.outputSchema()).isNull();
        assertThat(response.async()).isFalse();
    }

    @Test
    void should_acceptEmptyInputSchema_when_constructed() {
        var response = new CapabilityResponse("cap-1", "name", "desc", Collections.emptyMap(), Map.of("x", "y"), false);

        assertThat(response.inputSchema()).isEmpty();
    }

    @Test
    void should_acceptEmptyOutputSchema_when_constructed() {
        var response = new CapabilityResponse("cap-1", "name", "desc", Map.of("x", "y"), Collections.emptyMap(), false);

        assertThat(response.outputSchema()).isEmpty();
    }

    @Test
    void should_preserveNestedSchema_when_constructedWithComplexMaps() {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("query", Map.of("type", "string"))
        );
        Map<String, Object> outputSchema = Map.of(
                "type", "object",
                "properties", Map.of("result", Map.of("type", "array"))
        );
        var response = new CapabilityResponse("cap-1", "search", "Search", inputSchema, outputSchema, true);

        assertThat(response.inputSchema()).containsKey("type");
        assertThat(response.inputSchema()).containsKey("properties");
        assertThat(response.inputSchema().get("type")).isEqualTo("object");
        assertThat(response.outputSchema()).containsKey("properties");
        assertThat(response.outputSchema().get("type")).isEqualTo("object");
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        Map<String, Object> input = Map.of("type", "string");
        Map<String, Object> output = Map.of("type", "number");
        var a = new CapabilityResponse("cap-1", "name", "desc", input, output, true);
        var b = new CapabilityResponse("cap-1", "name", "desc", input, output, true);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var schema = Map.<String, Object>of("type", "string");
        var a = new CapabilityResponse("cap-1", "name", "desc", schema, schema, false);
        var b = new CapabilityResponse("cap-2", "name", "desc", schema, schema, false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentName() {
        var schema = Map.<String, Object>of("type", "string");
        var a = new CapabilityResponse("cap-1", "nameA", "desc", schema, schema, false);
        var b = new CapabilityResponse("cap-1", "nameB", "desc", schema, schema, false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDescription() {
        var schema = Map.<String, Object>of("type", "string");
        var a = new CapabilityResponse("cap-1", "name", "desc-a", schema, schema, false);
        var b = new CapabilityResponse("cap-1", "name", "desc-b", schema, schema, false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentInputSchema() {
        var output = Map.<String, Object>of("type", "string");
        var a = new CapabilityResponse("cap-1", "name", "desc", Map.of("type", "string"), output, false);
        var b = new CapabilityResponse("cap-1", "name", "desc", Map.of("type", "number"), output, false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentOutputSchema() {
        var input = Map.<String, Object>of("type", "string");
        var a = new CapabilityResponse("cap-1", "name", "desc", input, Map.of("type", "string"), false);
        var b = new CapabilityResponse("cap-1", "name", "desc", input, Map.of("type", "number"), false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentAsync() {
        var schema = Map.<String, Object>of("type", "string");
        var a = new CapabilityResponse("cap-1", "name", "desc", schema, schema, true);
        var b = new CapabilityResponse("cap-1", "name", "desc", schema, schema, false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothAllNull() {
        var a = new CapabilityResponse(null, null, null, null, null, false);
        var b = new CapabilityResponse(null, null, null, null, null, false);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new CapabilityResponse("cap-1", "doSearch", "Searches things", Map.of("type", "object"), Map.of("result", "array"), true);
        var str = response.toString();

        assertThat(str).contains("cap-1");
        assertThat(str).contains("doSearch");
        assertThat(str).contains("Searches things");
        assertThat(str).contains("type");
        assertThat(str).contains("object");
        assertThat(str).contains("result");
        assertThat(str).contains("true");
    }

    @Test
    void should_handleNullsInToString_when_fieldsAreNull() {
        var response = new CapabilityResponse(null, null, null, null, null, false);
        var str = response.toString();

        assertThat(str).contains("null");
    }
}
