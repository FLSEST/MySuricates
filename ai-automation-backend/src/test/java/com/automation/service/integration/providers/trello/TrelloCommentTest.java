package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrelloCommentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        TrelloComment.TrelloCommentData data = new TrelloComment.TrelloCommentData("Hello", null);
        TrelloComment comment = new TrelloComment("c-1", "commentCard", "2026-04-25T10:00:00Z", data);

        assertThat(comment.id()).isEqualTo("c-1");
        assertThat(comment.type()).isEqualTo("commentCard");
        assertThat(comment.date()).isEqualTo("2026-04-25T10:00:00Z");
        assertThat(comment.data()).isSameAs(data);
        assertThat(comment.data().text()).isEqualTo("Hello");
        assertThat(comment.data().card()).isNull();
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        TrelloComment comment = new TrelloComment(null, null, null, null);

        assertThat(comment.id()).isNull();
        assertThat(comment.type()).isNull();
        assertThat(comment.date()).isNull();
        assertThat(comment.data()).isNull();
    }

    @Test
    void should_holdCardReference_when_dataContainsCard() {
        TrelloCard card = new TrelloCard("card-1", "Task", "desc", null, null, false, null, null, null, false, null, null, 0f);
        TrelloComment.TrelloCommentData data = new TrelloComment.TrelloCommentData("comment text", card);
        TrelloComment comment = new TrelloComment("c-2", "commentCard", "2026-04-25", data);

        assertThat(comment.data().card()).isSameAs(card);
        assertThat(comment.data().card().id()).isEqualTo("card-1");
    }

    // --- JSON deserialization ---

    @Test
    void should_deserialize_allFields() throws Exception {
        String json = """
                {
                  "id":"c-1",
                  "type":"commentCard",
                  "date":"2026-04-25T10:00:00Z",
                  "data":{"text":"Nice work","card":{"id":"card-1","name":"Task"}}
                }
                """;

        TrelloComment comment = objectMapper.readValue(json, TrelloComment.class);

        assertThat(comment.id()).isEqualTo("c-1");
        assertThat(comment.type()).isEqualTo("commentCard");
        assertThat(comment.date()).isEqualTo("2026-04-25T10:00:00Z");
        assertThat(comment.data()).isNotNull();
        assertThat(comment.data().text()).isEqualTo("Nice work");
        assertThat(comment.data().card()).isNotNull();
        assertThat(comment.data().card().id()).isEqualTo("card-1");
    }

    @Test
    void should_handleMissingFields_when_fieldsAbsent() throws Exception {
        String json = "{}";

        TrelloComment comment = objectMapper.readValue(json, TrelloComment.class);

        assertThat(comment.id()).isNull();
        assertThat(comment.type()).isNull();
        assertThat(comment.date()).isNull();
        assertThat(comment.data()).isNull();
    }

    @Test
    void should_ignoreUnknownFields_when_extraFieldsPresent() throws Exception {
        String json = """
                {"id":"c-2","type":"commentCard","date":"2026-04-25","data":{"text":"hi","extra":"x"},"memberCreator":{"id":"m-1"},"unknownTop":42}
                """;

        TrelloComment comment = objectMapper.readValue(json, TrelloComment.class);

        assertThat(comment.id()).isEqualTo("c-2");
        assertThat(comment.type()).isEqualTo("commentCard");
        assertThat(comment.data().text()).isEqualTo("hi");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        TrelloComment.TrelloCommentData data = new TrelloComment.TrelloCommentData("Looks good", null);
        TrelloComment comment = new TrelloComment("c-3", "commentCard", "2026-04-25", data);

        String json = objectMapper.writeValueAsString(comment);

        assertThat(json).contains("\"id\":\"c-3\"");
        assertThat(json).contains("\"type\":\"commentCard\"");
        assertThat(json).contains("\"date\":\"2026-04-25\"");
        assertThat(json).contains("\"text\":\"Looks good\"");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveData_when_roundtrip() throws Exception {
        TrelloComment.TrelloCommentData data = new TrelloComment.TrelloCommentData("roundtrip", null);
        TrelloComment original = new TrelloComment("c-4", "commentCard", "2026-04-25", data);

        String json = objectMapper.writeValueAsString(original);
        TrelloComment parsed = objectMapper.readValue(json, TrelloComment.class);

        assertThat(parsed.id()).isEqualTo(original.id());
        assertThat(parsed.type()).isEqualTo(original.type());
        assertThat(parsed.date()).isEqualTo(original.date());
        assertThat(parsed.data().text()).isEqualTo(original.data().text());
    }

    // --- Nested TrelloCommentData record ---

    @Test
    void should_returnNestedFields_when_dataCreated() {
        TrelloCard card = new TrelloCard("card-9", "Test", null, null, null, false, null, null, null, false, null, null, 0f);
        TrelloComment.TrelloCommentData data = new TrelloComment.TrelloCommentData("body", card);

        assertThat(data.text()).isEqualTo("body");
        assertThat(data.card()).isSameAs(card);
    }

    @Test
    void should_allowNullNestedFields_when_dataCreatedWithNulls() {
        TrelloComment.TrelloCommentData data = new TrelloComment.TrelloCommentData(null, null);

        assertThat(data.text()).isNull();
        assertThat(data.card()).isNull();
    }
}
