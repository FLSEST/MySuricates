package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrelloSearchResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        TrelloCard card = new TrelloCard("card-1", "Task", "desc", "https://trello.com/c/1", "https://trello.com/c/1/short", false, "board-1", "list-1", null, false, List.of(), List.of(), 1.0f);
        TrelloBoard board = new TrelloBoard("board-1", "My Board", "desc", "https://trello.com/b/1", false, "org-1");
        TrelloSearchResult result = new TrelloSearchResult(List.of(card), List.of(board));

        assertThat(result.cards()).containsExactly(card);
        assertThat(result.boards()).containsExactly(board);
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        TrelloSearchResult result = new TrelloSearchResult(null, null);

        assertThat(result.cards()).isNull();
        assertThat(result.boards()).isNull();
    }

    @Test
    void should_allowEmptyLists_when_createdWithEmptyLists() {
        TrelloSearchResult result = new TrelloSearchResult(List.of(), List.of());

        assertThat(result.cards()).isEmpty();
        assertThat(result.boards()).isEmpty();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserialize_allFields() throws Exception {
        String json = """
                {"cards":[{"id":"card-1","name":"Task","desc":"d","url":"https://trello.com/c/1","shortUrl":"https://trello.com/c/1/s","closed":false,"idBoard":"b-1","idList":"l-1","due":null,"dueComplete":false,"idMembers":[],"labels":[],"pos":1.0}],"boards":[{"id":"board-1","name":"Board","desc":"d","url":"https://trello.com/b/1","closed":false,"idOrganization":"org-1"}]}
                """;

        TrelloSearchResult result = objectMapper.readValue(json, TrelloSearchResult.class);

        assertThat(result.cards()).hasSize(1);
        assertThat(result.cards().get(0).id()).isEqualTo("card-1");
        assertThat(result.boards()).hasSize(1);
        assertThat(result.boards().get(0).id()).isEqualTo("board-1");
    }

    @Test
    void should_handleMissingFields_when_fieldsAbsent() throws Exception {
        String json = """
                {}
                """;

        TrelloSearchResult result = objectMapper.readValue(json, TrelloSearchResult.class);

        assertThat(result.cards()).isNull();
        assertThat(result.boards()).isNull();
    }

    @Test
    void should_handleEmptyArrays_when_noResults() throws Exception {
        String json = """
                {"cards":[],"boards":[]}
                """;

        TrelloSearchResult result = objectMapper.readValue(json, TrelloSearchResult.class);

        assertThat(result.cards()).isEmpty();
        assertThat(result.boards()).isEmpty();
    }

    @Test
    void should_ignoreUnknownFields_when_extraFieldsPresent() throws Exception {
        String json = """
                {"cards":[],"boards":[],"organizations":[],"members":[]}
                """;

        TrelloSearchResult result = objectMapper.readValue(json, TrelloSearchResult.class);

        assertThat(result.cards()).isEmpty();
        assertThat(result.boards()).isEmpty();
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        TrelloCard card = new TrelloCard("card-1", "Task", "desc", "https://trello.com/c/1", "https://trello.com/c/1/short", false, "board-1", "list-1", null, false, List.of(), List.of(), 1.0f);
        TrelloSearchResult result = new TrelloSearchResult(List.of(card), List.of());

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"cards\":");
        assertThat(json).contains("\"card-1\"");
        assertThat(json).contains("\"boards\":[]");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        TrelloSearchResult result = new TrelloSearchResult(null, null);

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"cards\":null");
        assertThat(json).contains("\"boards\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_serializeAndDeserialize_roundtrip() throws Exception {
        TrelloCard card = new TrelloCard("card-1", "Task", "desc", "https://trello.com/c/1", "https://trello.com/c/1/short", false, "board-1", "list-1", null, false, List.of(), List.of(), 1.0f);
        TrelloBoard board = new TrelloBoard("board-1", "Board", "desc", "https://trello.com/b/1", false, "org-1");
        TrelloSearchResult original = new TrelloSearchResult(List.of(card), List.of(board));

        String json = objectMapper.writeValueAsString(original);
        TrelloSearchResult deserialized = objectMapper.readValue(json, TrelloSearchResult.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        TrelloSearchResult a = new TrelloSearchResult(List.of(), List.of());
        TrelloSearchResult b = new TrelloSearchResult(List.of(), List.of());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentCards() {
        TrelloCard card = new TrelloCard("card-1", "Task", "desc", "https://trello.com/c/1", "https://trello.com/c/1/short", false, "board-1", "list-1", null, false, List.of(), List.of(), 1.0f);
        TrelloSearchResult a = new TrelloSearchResult(List.of(card), List.of());
        TrelloSearchResult b = new TrelloSearchResult(List.of(), List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentBoards() {
        TrelloBoard board = new TrelloBoard("board-1", "Board", "desc", "https://trello.com/b/1", false, "org-1");
        TrelloSearchResult a = new TrelloSearchResult(List.of(), List.of(board));
        TrelloSearchResult b = new TrelloSearchResult(List.of(), List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        TrelloCard card = new TrelloCard("card-1", "Task", "desc", "https://trello.com/c/1", "https://trello.com/c/1/short", false, "board-1", "list-1", null, false, List.of(), List.of(), 1.0f);
        TrelloBoard board = new TrelloBoard("board-1", "My Board", "desc", "https://trello.com/b/1", false, "org-1");
        TrelloSearchResult result = new TrelloSearchResult(List.of(card), List.of(board));

        String str = result.toString();

        assertThat(str).contains("card-1");
        assertThat(str).contains("board-1");
    }
}
