package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrelloCardTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFieldValues_when_recordIsConstructed() {
        TrelloLabel label = new TrelloLabel("lab1", "urgent", "red");
        TrelloCard card = new TrelloCard(
                "card-1",
                "My Card",
                "A description",
                "https://trello.com/c/card-1/my-card",
                "https://trello.com/c/card-1",
                false,
                "board-1",
                "list-1",
                "2026-04-25T10:00:00.000Z",
                false,
                List.of("member-1", "member-2"),
                List.of(label),
                1.5f
        );

        assertThat(card.id()).isEqualTo("card-1");
        assertThat(card.name()).isEqualTo("My Card");
        assertThat(card.desc()).isEqualTo("A description");
        assertThat(card.url()).isEqualTo("https://trello.com/c/card-1/my-card");
        assertThat(card.shortUrl()).isEqualTo("https://trello.com/c/card-1");
        assertThat(card.closed()).isFalse();
        assertThat(card.idBoard()).isEqualTo("board-1");
        assertThat(card.idList()).isEqualTo("list-1");
        assertThat(card.due()).isEqualTo("2026-04-25T10:00:00.000Z");
        assertThat(card.dueComplete()).isFalse();
        assertThat(card.idMembers()).containsExactly("member-1", "member-2");
        assertThat(card.labels()).containsExactly(label);
        assertThat(card.pos()).isEqualTo(1.5f);
    }

    @Test
    void should_acceptNullValues_when_optionalFieldsAreMissing() {
        TrelloCard card = new TrelloCard(
                "card-2", "Empty", null, "u", "su",
                true, "b", "l", null, false, null, null, 0.0f
        );

        assertThat(card.desc()).isNull();
        assertThat(card.due()).isNull();
        assertThat(card.idMembers()).isNull();
        assertThat(card.labels()).isNull();
        assertThat(card.closed()).isTrue();
    }

    @Test
    void should_acceptEmptyLists_when_noMembersOrLabels() {
        TrelloCard card = new TrelloCard(
                "card-3", "n", "d", "u", "su",
                false, "b", "l", null, false, List.of(), List.of(), 0.5f
        );

        assertThat(card.idMembers()).isEmpty();
        assertThat(card.labels()).isEmpty();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {
                  "id": "card-1",
                  "name": "My Card",
                  "desc": "A description",
                  "url": "https://trello.com/c/card-1/my-card",
                  "shortUrl": "https://trello.com/c/card-1",
                  "closed": false,
                  "idBoard": "board-1",
                  "idList": "list-1",
                  "due": "2026-04-25T10:00:00.000Z",
                  "dueComplete": false,
                  "idMembers": ["m1", "m2"],
                  "labels": [{"id":"lab1","name":"urgent","color":"red"}],
                  "pos": 1.5
                }
                """;

        TrelloCard card = objectMapper.readValue(json, TrelloCard.class);

        assertThat(card.id()).isEqualTo("card-1");
        assertThat(card.name()).isEqualTo("My Card");
        assertThat(card.desc()).isEqualTo("A description");
        assertThat(card.shortUrl()).isEqualTo("https://trello.com/c/card-1");
        assertThat(card.closed()).isFalse();
        assertThat(card.idBoard()).isEqualTo("board-1");
        assertThat(card.idList()).isEqualTo("list-1");
        assertThat(card.due()).isEqualTo("2026-04-25T10:00:00.000Z");
        assertThat(card.dueComplete()).isFalse();
        assertThat(card.idMembers()).containsExactly("m1", "m2");
        assertThat(card.labels()).hasSize(1);
        assertThat(card.labels().get(0).name()).isEqualTo("urgent");
        assertThat(card.pos()).isEqualTo(1.5f);
    }

    @Test
    void should_ignoreUnknownFields_when_deserializingExtraJsonProperties() throws Exception {
        String json = """
                {
                  "id": "card-x",
                  "name": "Unknown Fields",
                  "desc": null,
                  "url": "u",
                  "shortUrl": "su",
                  "closed": false,
                  "idBoard": "b",
                  "idList": "l",
                  "due": null,
                  "dueComplete": false,
                  "idMembers": [],
                  "labels": [],
                  "pos": 0.0,
                  "unknownField": "ignored",
                  "anotherUnknown": 42
                }
                """;

        TrelloCard card = objectMapper.readValue(json, TrelloCard.class);

        assertThat(card.id()).isEqualTo("card-x");
        assertThat(card.name()).isEqualTo("Unknown Fields");
        assertThat(card.idMembers()).isEmpty();
        assertThat(card.labels()).isEmpty();
    }

    @Test
    void should_handleNullableFields_when_deserializingMinimalJson() throws Exception {
        String json = """
                {
                  "id": "card-min",
                  "name": "Minimal",
                  "desc": null,
                  "url": "u",
                  "shortUrl": "su",
                  "closed": true,
                  "idBoard": "b",
                  "idList": "l",
                  "due": null,
                  "dueComplete": false,
                  "idMembers": null,
                  "labels": null,
                  "pos": 0.0
                }
                """;

        TrelloCard card = objectMapper.readValue(json, TrelloCard.class);

        assertThat(card.desc()).isNull();
        assertThat(card.due()).isNull();
        assertThat(card.idMembers()).isNull();
        assertThat(card.labels()).isNull();
        assertThat(card.closed()).isTrue();
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsPopulated() throws Exception {
        TrelloLabel label = new TrelloLabel("lab1", "urgent", "red");
        TrelloCard card = new TrelloCard(
                "card-1", "My Card", "Desc", "url", "shortUrl",
                false, "board-1", "list-1", "2026-04-25T10:00:00.000Z",
                true, List.of("m1"), List.of(label), 2.5f
        );

        String json = objectMapper.writeValueAsString(card);

        assertThat(json).contains("\"id\":\"card-1\"");
        assertThat(json).contains("\"name\":\"My Card\"");
        assertThat(json).contains("\"desc\":\"Desc\"");
        assertThat(json).contains("\"shortUrl\":\"shortUrl\"");
        assertThat(json).contains("\"closed\":false");
        assertThat(json).contains("\"idBoard\":\"board-1\"");
        assertThat(json).contains("\"idList\":\"list-1\"");
        assertThat(json).contains("\"due\":\"2026-04-25T10:00:00.000Z\"");
        assertThat(json).contains("\"dueComplete\":true");
        assertThat(json).contains("\"idMembers\":[\"m1\"]");
        assertThat(json).contains("\"pos\":2.5");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_jsonRoundtripIsPerformed() throws Exception {
        TrelloLabel label = new TrelloLabel("lab1", "urgent", "red");
        TrelloCard original = new TrelloCard(
                "card-1", "Roundtrip", "A description", "url", "shortUrl",
                false, "board-1", "list-1", "2026-04-25T10:00:00.000Z",
                true, List.of("m1", "m2"), List.of(label), 3.75f
        );

        String json = objectMapper.writeValueAsString(original);
        TrelloCard restored = objectMapper.readValue(json, TrelloCard.class);

        assertThat(restored).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_allFieldsMatch() {
        TrelloLabel label = new TrelloLabel("lab1", "urgent", "red");
        TrelloCard a = new TrelloCard("id", "n", "d", "u", "su", false,
                "b", "l", "due", false, List.of("m"), List.of(label), 1.0f);
        TrelloCard b = new TrelloCard("id", "n", "d", "u", "su", false,
                "b", "l", "due", false, List.of("m"), List.of(label), 1.0f);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_idDiffers() {
        TrelloCard a = new TrelloCard("id-1", "n", "d", "u", "su", false,
                "b", "l", null, false, null, null, 0.0f);
        TrelloCard b = new TrelloCard("id-2", "n", "d", "u", "su", false,
                "b", "l", null, false, null, null, 0.0f);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_includeFieldValues_when_toStringIsCalled() {
        TrelloCard card = new TrelloCard("card-1", "My Card", "d", "u", "su",
                false, "b", "l", null, false, null, null, 1.5f);

        String str = card.toString();

        assertThat(str).contains("card-1");
        assertThat(str).contains("My Card");
    }
}
