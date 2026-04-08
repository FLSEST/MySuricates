package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JiraTransitionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        Map<String, Object> to = Map.of("self", "https://jira.example.com/status/3", "name", "In Progress", "id", "3");
        JiraTransition transition = new JiraTransition("21", "Start Progress", to);

        assertThat(transition.id()).isEqualTo("21");
        assertThat(transition.name()).isEqualTo("Start Progress");
        assertThat(transition.to()).isEqualTo(to);
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        JiraTransition transition = new JiraTransition(null, null, null);

        assertThat(transition.id()).isNull();
        assertThat(transition.name()).isNull();
        assertThat(transition.to()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {"id":"21","name":"Start Progress","to":{"self":"https://jira.example.com/status/3","name":"In Progress","id":"3"}}
                """;

        JiraTransition transition = objectMapper.readValue(json, JiraTransition.class);

        assertThat(transition.id()).isEqualTo("21");
        assertThat(transition.name()).isEqualTo("Start Progress");
        assertThat(transition.to())
                .containsEntry("self", "https://jira.example.com/status/3")
                .containsEntry("name", "In Progress")
                .containsEntry("id", "3");
    }

    @Test
    void should_deserializeWithNulls_when_fieldsAreMissing() throws Exception {
        String json = """
                {"id":"21"}
                """;

        JiraTransition transition = objectMapper.readValue(json, JiraTransition.class);

        assertThat(transition.id()).isEqualTo("21");
        assertThat(transition.name()).isNull();
        assertThat(transition.to()).isNull();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {"id":"21","name":"Start Progress","to":{"id":"3"},"hasScreen":false,"isGlobal":true,"isInitial":false}
                """;

        JiraTransition transition = objectMapper.readValue(json, JiraTransition.class);

        assertThat(transition.id()).isEqualTo("21");
        assertThat(transition.name()).isEqualTo("Start Progress");
        assertThat(transition.to()).containsEntry("id", "3");
    }

    @Test
    void should_deserializeEmptyTo_when_emptyObjectProvided() throws Exception {
        String json = """
                {"id":"21","name":"Start Progress","to":{}}
                """;

        JiraTransition transition = objectMapper.readValue(json, JiraTransition.class);

        assertThat(transition.to()).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeNullTo_when_nullProvided() throws Exception {
        String json = """
                {"id":"21","name":"Start Progress","to":null}
                """;

        JiraTransition transition = objectMapper.readValue(json, JiraTransition.class);

        assertThat(transition.to()).isNull();
    }

    @Test
    void should_deserializeEmptyJson_when_noFieldsPresent() throws Exception {
        String json = "{}";

        JiraTransition transition = objectMapper.readValue(json, JiraTransition.class);

        assertThat(transition.id()).isNull();
        assertThat(transition.name()).isNull();
        assertThat(transition.to()).isNull();
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        Map<String, Object> to = Map.of("id", "3", "name", "In Progress");
        JiraTransition transition = new JiraTransition("21", "Start Progress", to);

        String json = objectMapper.writeValueAsString(transition);

        assertThat(json).contains("\"id\":\"21\"");
        assertThat(json).contains("\"name\":\"Start Progress\"");
        assertThat(json).contains("\"to\":{");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        JiraTransition transition = new JiraTransition("21", null, null);

        String json = objectMapper.writeValueAsString(transition);

        assertThat(json).contains("\"id\":\"21\"");
        assertThat(json).contains("\"name\":null");
        assertThat(json).contains("\"to\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_serializedAndDeserialized() throws Exception {
        Map<String, Object> to = Map.of("id", "3", "name", "In Progress", "self", "https://jira.example.com/status/3");
        JiraTransition original = new JiraTransition("21", "Start Progress", to);

        String json = objectMapper.writeValueAsString(original);
        JiraTransition deserialized = objectMapper.readValue(json, JiraTransition.class);

        assertThat(deserialized.id()).isEqualTo(original.id());
        assertThat(deserialized.name()).isEqualTo(original.name());
        assertThat(deserialized.to()).isEqualTo(original.to());
    }

    @Test
    void should_preserveEmptyTo_when_roundtripped() throws Exception {
        JiraTransition original = new JiraTransition("21", "Start Progress", Map.of());

        String json = objectMapper.writeValueAsString(original);
        JiraTransition deserialized = objectMapper.readValue(json, JiraTransition.class);

        assertThat(deserialized.to()).isNotNull().isEmpty();
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        Map<String, Object> to = Map.of("id", "3");
        JiraTransition a = new JiraTransition("21", "Start Progress", to);
        JiraTransition b = new JiraTransition("21", "Start Progress", to);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        Map<String, Object> to = Map.of("id", "3");
        JiraTransition a = new JiraTransition("21", "Start Progress", to);
        JiraTransition b = new JiraTransition("31", "Start Progress", to);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentName() {
        Map<String, Object> to = Map.of("id", "3");
        JiraTransition a = new JiraTransition("21", "Start Progress", to);
        JiraTransition b = new JiraTransition("21", "Done", to);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        JiraTransition transition = new JiraTransition("21", "Start Progress", Map.of("id", "3"));

        String str = transition.toString();

        assertThat(str).contains("21");
        assertThat(str).contains("Start Progress");
    }
}
