package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrelloLabelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        TrelloLabel label = new TrelloLabel("lbl-1", "Bug", "red");

        assertThat(label.id()).isEqualTo("lbl-1");
        assertThat(label.name()).isEqualTo("Bug");
        assertThat(label.color()).isEqualTo("red");
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        TrelloLabel label = new TrelloLabel(null, null, null);

        assertThat(label.id()).isNull();
        assertThat(label.name()).isNull();
        assertThat(label.color()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserialize_allFields() throws Exception {
        String json = """
                {"id":"lbl-1","name":"Feature","color":"green"}
                """;

        TrelloLabel label = objectMapper.readValue(json, TrelloLabel.class);

        assertThat(label.id()).isEqualTo("lbl-1");
        assertThat(label.name()).isEqualTo("Feature");
        assertThat(label.color()).isEqualTo("green");
    }

    @Test
    void should_handleMissingFields_when_fieldsAbsent() throws Exception {
        String json = """
                {}
                """;

        TrelloLabel label = objectMapper.readValue(json, TrelloLabel.class);

        assertThat(label.id()).isNull();
        assertThat(label.name()).isNull();
        assertThat(label.color()).isNull();
    }

    @Test
    void should_ignoreUnknownFields_when_extraFieldsPresent() throws Exception {
        String json = """
                {"id":"lbl-2","name":"Urgent","color":"orange","idBoard":"board-99","uses":5}
                """;

        TrelloLabel label = objectMapper.readValue(json, TrelloLabel.class);

        assertThat(label.id()).isEqualTo("lbl-2");
        assertThat(label.name()).isEqualTo("Urgent");
        assertThat(label.color()).isEqualTo("orange");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        TrelloLabel label = new TrelloLabel("lbl-3", "Enhancement", "blue");

        String json = objectMapper.writeValueAsString(label);

        assertThat(json).contains("\"id\":\"lbl-3\"");
        assertThat(json).contains("\"name\":\"Enhancement\"");
        assertThat(json).contains("\"color\":\"blue\"");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        TrelloLabel label = new TrelloLabel("only-id", null, null);

        String json = objectMapper.writeValueAsString(label);

        assertThat(json).contains("\"id\":\"only-id\"");
        assertThat(json).contains("\"name\":null");
        assertThat(json).contains("\"color\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_serializeAndDeserialize_roundtrip() throws Exception {
        TrelloLabel original = new TrelloLabel("roundtrip-id", "Roundtrip Label", "purple");

        String json = objectMapper.writeValueAsString(original);
        TrelloLabel deserialized = objectMapper.readValue(json, TrelloLabel.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        TrelloLabel a = new TrelloLabel("same-id", "Same", "red");
        TrelloLabel b = new TrelloLabel("same-id", "Same", "red");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        TrelloLabel a = new TrelloLabel("id-a", "Name", "red");
        TrelloLabel b = new TrelloLabel("id-b", "Name", "red");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentName() {
        TrelloLabel a = new TrelloLabel("same-id", "Bug", "red");
        TrelloLabel b = new TrelloLabel("same-id", "Feature", "red");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        TrelloLabel label = new TrelloLabel("lbl-1", "Bug", "red");

        String str = label.toString();

        assertThat(str).contains("lbl-1");
        assertThat(str).contains("Bug");
        assertThat(str).contains("red");
    }
}
