package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrelloListTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        TrelloList list = new TrelloList("list-1", "To Do", true, "board-1", 16384.5f);

        assertThat(list.id()).isEqualTo("list-1");
        assertThat(list.name()).isEqualTo("To Do");
        assertThat(list.closed()).isTrue();
        assertThat(list.idBoard()).isEqualTo("board-1");
        assertThat(list.pos()).isEqualTo(16384.5f);
    }

    @Test
    void should_defaultPrimitives_when_createdWithMinimalValues() {
        TrelloList list = new TrelloList(null, null, false, null, 0.0f);

        assertThat(list.id()).isNull();
        assertThat(list.name()).isNull();
        assertThat(list.closed()).isFalse();
        assertThat(list.idBoard()).isNull();
        assertThat(list.pos()).isEqualTo(0.0f);
    }

    // --- JSON deserialization ---

    @Test
    void should_deserialize_allFields() throws Exception {
        String json = """
                {"id":"list-1","name":"Doing","closed":true,"idBoard":"board-42","pos":32768.75}
                """;

        TrelloList list = objectMapper.readValue(json, TrelloList.class);

        assertThat(list.id()).isEqualTo("list-1");
        assertThat(list.name()).isEqualTo("Doing");
        assertThat(list.closed()).isTrue();
        assertThat(list.idBoard()).isEqualTo("board-42");
        assertThat(list.pos()).isEqualTo(32768.75f);
    }

    @Test
    void should_deserializeClosedFalse_when_closedIsFalse() throws Exception {
        String json = """
                {"id":"list-2","name":"Open List","closed":false,"idBoard":"board-1","pos":100.0}
                """;

        TrelloList list = objectMapper.readValue(json, TrelloList.class);

        assertThat(list.closed()).isFalse();
    }

    @Test
    void should_handleMissingFields_when_fieldsAbsent() throws Exception {
        String json = """
                {}
                """;

        TrelloList list = objectMapper.readValue(json, TrelloList.class);

        assertThat(list.id()).isNull();
        assertThat(list.name()).isNull();
        assertThat(list.closed()).isFalse();
        assertThat(list.idBoard()).isNull();
        assertThat(list.pos()).isEqualTo(0.0f);
    }

    @Test
    void should_ignoreUnknownFields_when_extraFieldsPresent() throws Exception {
        String json = """
                {"id":"list-3","name":"Done","closed":false,"idBoard":"board-5","pos":65536.0,"softLimit":"","subscribed":true}
                """;

        TrelloList list = objectMapper.readValue(json, TrelloList.class);

        assertThat(list.id()).isEqualTo("list-3");
        assertThat(list.name()).isEqualTo("Done");
        assertThat(list.closed()).isFalse();
        assertThat(list.idBoard()).isEqualTo("board-5");
        assertThat(list.pos()).isEqualTo(65536.0f);
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        TrelloList list = new TrelloList("list-4", "Backlog", true, "board-9", 8192.25f);

        String json = objectMapper.writeValueAsString(list);

        assertThat(json).contains("\"id\":\"list-4\"");
        assertThat(json).contains("\"name\":\"Backlog\"");
        assertThat(json).contains("\"closed\":true");
        assertThat(json).contains("\"idBoard\":\"board-9\"");
        assertThat(json).contains("\"pos\":");
    }

    @Test
    void should_serializeClosedFalse_when_closedIsFalse() throws Exception {
        TrelloList list = new TrelloList("list-5", "Active", false, "board-1", 1.0f);

        String json = objectMapper.writeValueAsString(list);

        assertThat(json).contains("\"closed\":false");
    }

    @Test
    void should_serializeNullFields_when_stringFieldsAreNull() throws Exception {
        TrelloList list = new TrelloList("only-id", null, false, null, 0.0f);

        String json = objectMapper.writeValueAsString(list);

        assertThat(json).contains("\"id\":\"only-id\"");
        assertThat(json).contains("\"name\":null");
        assertThat(json).contains("\"closed\":false");
        assertThat(json).contains("\"idBoard\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_serializeAndDeserialize_roundtrip() throws Exception {
        TrelloList original = new TrelloList("rt-id", "Roundtrip List", true, "board-rt", 49152.5f);

        String json = objectMapper.writeValueAsString(original);
        TrelloList deserialized = objectMapper.readValue(json, TrelloList.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void should_serializeAndDeserialize_roundtripWithFalseAndZeroPos() throws Exception {
        TrelloList original = new TrelloList("rt-2", "Minimal", false, "board-0", 0.0f);

        String json = objectMapper.writeValueAsString(original);
        TrelloList deserialized = objectMapper.readValue(json, TrelloList.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        TrelloList a = new TrelloList("same-id", "Same", true, "board-1", 100.0f);
        TrelloList b = new TrelloList("same-id", "Same", true, "board-1", 100.0f);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        TrelloList a = new TrelloList("id-a", "Name", false, "board-1", 1.0f);
        TrelloList b = new TrelloList("id-b", "Name", false, "board-1", 1.0f);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentClosed() {
        TrelloList a = new TrelloList("id-1", "Name", false, "board-1", 1.0f);
        TrelloList b = new TrelloList("id-1", "Name", true, "board-1", 1.0f);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        TrelloList list = new TrelloList("list-1", "To Do", true, "board-1", 16384.5f);

        String str = list.toString();

        assertThat(str).contains("list-1");
        assertThat(str).contains("To Do");
        assertThat(str).contains("board-1");
    }
}
