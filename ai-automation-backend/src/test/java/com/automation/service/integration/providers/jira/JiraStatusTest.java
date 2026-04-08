package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JiraStatusTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        Map<String, Object> category = Map.of("id", 3, "name", "Done", "colorName", "green");
        JiraStatus status = new JiraStatus("10001", "Closed", category);

        assertThat(status.id()).isEqualTo("10001");
        assertThat(status.name()).isEqualTo("Closed");
        assertThat(status.statusCategory()).isEqualTo(category);
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        JiraStatus status = new JiraStatus(null, null, null);

        assertThat(status.id()).isNull();
        assertThat(status.name()).isNull();
        assertThat(status.statusCategory()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {"id":"10001","name":"Done","statusCategory":{"id":3,"name":"Done","colorName":"green"}}
                """;

        JiraStatus status = objectMapper.readValue(json, JiraStatus.class);

        assertThat(status.id()).isEqualTo("10001");
        assertThat(status.name()).isEqualTo("Done");
        assertThat(status.statusCategory())
                .containsEntry("id", 3)
                .containsEntry("name", "Done")
                .containsEntry("colorName", "green");
    }

    @Test
    void should_deserializeWithNulls_when_fieldsAreMissing() throws Exception {
        String json = """
                {"id":"10001"}
                """;

        JiraStatus status = objectMapper.readValue(json, JiraStatus.class);

        assertThat(status.id()).isEqualTo("10001");
        assertThat(status.name()).isNull();
        assertThat(status.statusCategory()).isNull();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {"id":"10001","name":"Done","statusCategory":{"id":3},"self":"https://jira.example.com/status/10001","iconUrl":"https://jira.example.com/icon.png"}
                """;

        JiraStatus status = objectMapper.readValue(json, JiraStatus.class);

        assertThat(status.id()).isEqualTo("10001");
        assertThat(status.name()).isEqualTo("Done");
        assertThat(status.statusCategory()).containsEntry("id", 3);
    }

    @Test
    void should_deserializeEmptyStatusCategory_when_emptyObjectProvided() throws Exception {
        String json = """
                {"id":"10001","name":"Open","statusCategory":{}}
                """;

        JiraStatus status = objectMapper.readValue(json, JiraStatus.class);

        assertThat(status.statusCategory()).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeNullStatusCategory_when_nullProvided() throws Exception {
        String json = """
                {"id":"10001","name":"Open","statusCategory":null}
                """;

        JiraStatus status = objectMapper.readValue(json, JiraStatus.class);

        assertThat(status.statusCategory()).isNull();
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        Map<String, Object> category = Map.of("id", 3, "name", "Done");
        JiraStatus status = new JiraStatus("10001", "Done", category);

        String json = objectMapper.writeValueAsString(status);

        assertThat(json).contains("\"id\":\"10001\"");
        assertThat(json).contains("\"name\":\"Done\"");
        assertThat(json).contains("\"statusCategory\":{");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        JiraStatus status = new JiraStatus("10001", null, null);

        String json = objectMapper.writeValueAsString(status);

        assertThat(json).contains("\"id\":\"10001\"");
        assertThat(json).contains("\"name\":null");
        assertThat(json).contains("\"statusCategory\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_serializedAndDeserialized() throws Exception {
        Map<String, Object> category = Map.of("id", 3, "name", "Done", "colorName", "green");
        JiraStatus original = new JiraStatus("10001", "Closed", category);

        String json = objectMapper.writeValueAsString(original);
        JiraStatus deserialized = objectMapper.readValue(json, JiraStatus.class);

        assertThat(deserialized.id()).isEqualTo(original.id());
        assertThat(deserialized.name()).isEqualTo(original.name());
        assertThat(deserialized.statusCategory()).isEqualTo(original.statusCategory());
    }

    @Test
    void should_preserveEmptyCategory_when_roundtripped() throws Exception {
        JiraStatus original = new JiraStatus("10001", "Open", Map.of());

        String json = objectMapper.writeValueAsString(original);
        JiraStatus deserialized = objectMapper.readValue(json, JiraStatus.class);

        assertThat(deserialized.statusCategory()).isNotNull().isEmpty();
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        Map<String, Object> category = Map.of("id", 3);
        JiraStatus a = new JiraStatus("10001", "Done", category);
        JiraStatus b = new JiraStatus("10001", "Done", category);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        Map<String, Object> category = Map.of("id", 3);
        JiraStatus a = new JiraStatus("10001", "Done", category);
        JiraStatus b = new JiraStatus("10002", "Done", category);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentName() {
        Map<String, Object> category = Map.of("id", 3);
        JiraStatus a = new JiraStatus("10001", "Done", category);
        JiraStatus b = new JiraStatus("10001", "Open", category);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        JiraStatus status = new JiraStatus("10001", "Done", Map.of("id", 3));

        String str = status.toString();

        assertThat(str).contains("10001");
        assertThat(str).contains("Done");
    }
}
