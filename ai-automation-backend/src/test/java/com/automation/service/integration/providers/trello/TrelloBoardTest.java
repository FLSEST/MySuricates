package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrelloBoardTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        TrelloBoard board = new TrelloBoard("board-1", "My Board", "A description", "https://trello.com/b/abc", true, "org-1");

        assertThat(board.id()).isEqualTo("board-1");
        assertThat(board.name()).isEqualTo("My Board");
        assertThat(board.desc()).isEqualTo("A description");
        assertThat(board.url()).isEqualTo("https://trello.com/b/abc");
        assertThat(board.closed()).isTrue();
        assertThat(board.idOrganization()).isEqualTo("org-1");
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        TrelloBoard board = new TrelloBoard(null, null, null, null, false, null);

        assertThat(board.id()).isNull();
        assertThat(board.name()).isNull();
        assertThat(board.desc()).isNull();
        assertThat(board.url()).isNull();
        assertThat(board.closed()).isFalse();
        assertThat(board.idOrganization()).isNull();
    }

    @Test
    void should_defaultClosedToFalse_when_createdWithMinimalValues() {
        TrelloBoard board = new TrelloBoard("board-min", "Minimal", null, null, false, null);

        assertThat(board.id()).isEqualTo("board-min");
        assertThat(board.name()).isEqualTo("Minimal");
        assertThat(board.closed()).isFalse();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserialize_allFields() throws Exception {
        String json = """
                {"id":"board-1","name":"Dev Board","desc":"Development board","url":"https://trello.com/b/xyz","closed":true,"idOrganization":"org-42"}
                """;

        TrelloBoard board = objectMapper.readValue(json, TrelloBoard.class);

        assertThat(board.id()).isEqualTo("board-1");
        assertThat(board.name()).isEqualTo("Dev Board");
        assertThat(board.desc()).isEqualTo("Development board");
        assertThat(board.url()).isEqualTo("https://trello.com/b/xyz");
        assertThat(board.closed()).isTrue();
        assertThat(board.idOrganization()).isEqualTo("org-42");
    }

    @Test
    void should_deserializeClosedFalse_when_closedIsFalse() throws Exception {
        String json = """
                {"id":"board-2","name":"Open Board","closed":false}
                """;

        TrelloBoard board = objectMapper.readValue(json, TrelloBoard.class);

        assertThat(board.id()).isEqualTo("board-2");
        assertThat(board.name()).isEqualTo("Open Board");
        assertThat(board.closed()).isFalse();
    }

    @Test
    void should_handleMissingFields_when_fieldsAbsent() throws Exception {
        String json = """
                {}
                """;

        TrelloBoard board = objectMapper.readValue(json, TrelloBoard.class);

        assertThat(board.id()).isNull();
        assertThat(board.name()).isNull();
        assertThat(board.desc()).isNull();
        assertThat(board.url()).isNull();
        assertThat(board.closed()).isFalse();
        assertThat(board.idOrganization()).isNull();
    }

    @Test
    void should_ignoreUnknownFields_when_extraFieldsPresent() throws Exception {
        String json = """
                {"id":"board-3","name":"Extra Board","desc":"desc","url":"https://trello.com/b/extra","closed":false,"idOrganization":"org-1","prefs":{"background":"blue"},"starred":true}
                """;

        TrelloBoard board = objectMapper.readValue(json, TrelloBoard.class);

        assertThat(board.id()).isEqualTo("board-3");
        assertThat(board.name()).isEqualTo("Extra Board");
        assertThat(board.desc()).isEqualTo("desc");
        assertThat(board.url()).isEqualTo("https://trello.com/b/extra");
        assertThat(board.closed()).isFalse();
        assertThat(board.idOrganization()).isEqualTo("org-1");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        TrelloBoard board = new TrelloBoard("board-s", "Serialized", "A desc", "https://trello.com/b/ser", true, "org-s");

        String json = objectMapper.writeValueAsString(board);

        assertThat(json).contains("\"id\":\"board-s\"");
        assertThat(json).contains("\"name\":\"Serialized\"");
        assertThat(json).contains("\"desc\":\"A desc\"");
        assertThat(json).contains("\"url\":\"https://trello.com/b/ser\"");
        assertThat(json).contains("\"closed\":true");
        assertThat(json).contains("\"idOrganization\":\"org-s\"");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        TrelloBoard board = new TrelloBoard("only-id", null, null, null, false, null);

        String json = objectMapper.writeValueAsString(board);

        assertThat(json).contains("\"id\":\"only-id\"");
        assertThat(json).contains("\"name\":null");
        assertThat(json).contains("\"desc\":null");
        assertThat(json).contains("\"url\":null");
        assertThat(json).contains("\"closed\":false");
        assertThat(json).contains("\"idOrganization\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_serializeAndDeserialize_roundtrip() throws Exception {
        TrelloBoard original = new TrelloBoard("rt-id", "Roundtrip Board", "RT desc", "https://trello.com/b/rt", true, "org-rt");

        String json = objectMapper.writeValueAsString(original);
        TrelloBoard deserialized = objectMapper.readValue(json, TrelloBoard.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void should_serializeAndDeserialize_roundtripWithNulls() throws Exception {
        TrelloBoard original = new TrelloBoard("rt-null", null, null, null, false, null);

        String json = objectMapper.writeValueAsString(original);
        TrelloBoard deserialized = objectMapper.readValue(json, TrelloBoard.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        TrelloBoard a = new TrelloBoard("same-id", "Same", "desc", "url", true, "org");
        TrelloBoard b = new TrelloBoard("same-id", "Same", "desc", "url", true, "org");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        TrelloBoard a = new TrelloBoard("id-a", "Name", "desc", "url", false, "org");
        TrelloBoard b = new TrelloBoard("id-b", "Name", "desc", "url", false, "org");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentName() {
        TrelloBoard a = new TrelloBoard("same-id", "Board A", "desc", "url", false, "org");
        TrelloBoard b = new TrelloBoard("same-id", "Board B", "desc", "url", false, "org");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentClosed() {
        TrelloBoard a = new TrelloBoard("same-id", "Name", "desc", "url", true, "org");
        TrelloBoard b = new TrelloBoard("same-id", "Name", "desc", "url", false, "org");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        TrelloBoard board = new TrelloBoard("board-1", "My Board", "A description", "https://trello.com/b/abc", true, "org-1");

        String str = board.toString();

        assertThat(str).contains("board-1");
        assertThat(str).contains("My Board");
        assertThat(str).contains("A description");
        assertThat(str).contains("https://trello.com/b/abc");
        assertThat(str).contains("org-1");
    }
}
