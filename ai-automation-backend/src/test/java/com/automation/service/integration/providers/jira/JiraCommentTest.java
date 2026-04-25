package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JiraCommentTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    // === Record Accessors ===

    @Test
    void should_return_all_fields_when_constructed_with_all_args() {
        Map<String, Object> body = Map.of("type", "doc", "content", "Hello");
        Map<String, Object> author = Map.of("accountId", "user-123", "displayName", "Alice");

        JiraComment comment = new JiraComment("10001", body, author, "2026-01-01T10:00:00.000+0000", "2026-01-02T11:00:00.000+0000");

        assertThat(comment.id()).isEqualTo("10001");
        assertThat(comment.body()).isEqualTo(body);
        assertThat(comment.author()).isEqualTo(author);
        assertThat(comment.created()).isEqualTo("2026-01-01T10:00:00.000+0000");
        assertThat(comment.updated()).isEqualTo("2026-01-02T11:00:00.000+0000");
    }

    @Test
    void should_allow_null_fields_when_constructed_with_nulls() {
        JiraComment comment = new JiraComment(null, null, null, null, null);

        assertThat(comment.id()).isNull();
        assertThat(comment.body()).isNull();
        assertThat(comment.author()).isNull();
        assertThat(comment.created()).isNull();
        assertThat(comment.updated()).isNull();
    }

    @Test
    void should_allow_empty_maps_for_body_and_author() {
        Map<String, Object> emptyBody = new HashMap<>();
        Map<String, Object> emptyAuthor = new HashMap<>();

        JiraComment comment = new JiraComment("1", emptyBody, emptyAuthor, "now", "now");

        assertThat(comment.body()).isEmpty();
        assertThat(comment.author()).isEmpty();
    }

    // === JSON Deserialization ===

    @Test
    void should_deserialize_when_all_fields_present() throws Exception {
        String json = """
                {
                    "id": "10001",
                    "body": {"type": "doc", "version": 1},
                    "author": {"accountId": "user-123", "displayName": "Alice"},
                    "created": "2026-01-01T10:00:00.000+0000",
                    "updated": "2026-01-02T11:00:00.000+0000"
                }
                """;

        JiraComment comment = objectMapper.readValue(json, JiraComment.class);

        assertThat(comment.id()).isEqualTo("10001");
        assertThat(comment.body()).containsEntry("type", "doc").containsEntry("version", 1);
        assertThat(comment.author()).containsEntry("accountId", "user-123").containsEntry("displayName", "Alice");
        assertThat(comment.created()).isEqualTo("2026-01-01T10:00:00.000+0000");
        assertThat(comment.updated()).isEqualTo("2026-01-02T11:00:00.000+0000");
    }

    @Test
    void should_deserialize_when_fields_are_missing() throws Exception {
        String json = "{\"id\": \"10001\"}";

        JiraComment comment = objectMapper.readValue(json, JiraComment.class);

        assertThat(comment.id()).isEqualTo("10001");
        assertThat(comment.body()).isNull();
        assertThat(comment.author()).isNull();
        assertThat(comment.created()).isNull();
        assertThat(comment.updated()).isNull();
    }

    @Test
    void should_deserialize_when_json_is_empty_object() throws Exception {
        String json = "{}";

        JiraComment comment = objectMapper.readValue(json, JiraComment.class);

        assertThat(comment.id()).isNull();
        assertThat(comment.body()).isNull();
        assertThat(comment.author()).isNull();
        assertThat(comment.created()).isNull();
        assertThat(comment.updated()).isNull();
    }

    @Test
    void should_ignore_unknown_properties_when_deserializing() throws Exception {
        String json = """
                {
                    "id": "10001",
                    "unknownField": "should be ignored",
                    "anotherUnknown": 42
                }
                """;

        JiraComment comment = objectMapper.readValue(json, JiraComment.class);

        assertThat(comment.id()).isEqualTo("10001");
    }

    @Test
    void should_deserialize_when_body_and_author_are_empty_objects() throws Exception {
        String json = """
                {
                    "id": "10001",
                    "body": {},
                    "author": {}
                }
                """;

        JiraComment comment = objectMapper.readValue(json, JiraComment.class);

        assertThat(comment.body()).isEmpty();
        assertThat(comment.author()).isEmpty();
    }

    @Test
    void should_deserialize_when_body_and_author_are_null() throws Exception {
        String json = """
                {
                    "id": "10001",
                    "body": null,
                    "author": null
                }
                """;

        JiraComment comment = objectMapper.readValue(json, JiraComment.class);

        assertThat(comment.body()).isNull();
        assertThat(comment.author()).isNull();
    }

    // === JSON Serialization ===

    @Test
    void should_serialize_all_fields() throws Exception {
        JiraComment comment = new JiraComment(
                "10001",
                Map.of("type", "doc"),
                Map.of("accountId", "user-123"),
                "2026-01-01T10:00:00.000+0000",
                "2026-01-02T11:00:00.000+0000"
        );

        String json = objectMapper.writeValueAsString(comment);

        assertThat(json).contains("\"id\":\"10001\"");
        assertThat(json).contains("\"type\":\"doc\"");
        assertThat(json).contains("\"accountId\":\"user-123\"");
        assertThat(json).contains("\"created\":\"2026-01-01T10:00:00.000+0000\"");
        assertThat(json).contains("\"updated\":\"2026-01-02T11:00:00.000+0000\"");
    }

    @Test
    void should_serialize_null_fields_as_null() throws Exception {
        JiraComment comment = new JiraComment(null, null, null, null, null);

        String json = objectMapper.writeValueAsString(comment);

        assertThat(json).contains("\"id\":null");
        assertThat(json).contains("\"body\":null");
        assertThat(json).contains("\"author\":null");
        assertThat(json).contains("\"created\":null");
        assertThat(json).contains("\"updated\":null");
    }

    // === Roundtrip ===

    @Test
    void should_roundtrip_through_json() throws Exception {
        JiraComment original = new JiraComment(
                "10001",
                Map.of("type", "doc", "version", 1),
                Map.of("accountId", "user-123"),
                "2026-01-01T10:00:00.000+0000",
                "2026-01-02T11:00:00.000+0000"
        );

        String json = objectMapper.writeValueAsString(original);
        JiraComment deserialized = objectMapper.readValue(json, JiraComment.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void should_roundtrip_with_empty_maps() throws Exception {
        JiraComment original = new JiraComment("1", Map.of(), Map.of(), "now", "now");

        String json = objectMapper.writeValueAsString(original);
        JiraComment deserialized = objectMapper.readValue(json, JiraComment.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // === equals / hashCode / toString ===

    @Test
    void should_be_equal_when_all_fields_match() {
        JiraComment a = new JiraComment("1", Map.of("k", "v"), Map.of("a", "b"), "c", "u");
        JiraComment b = new JiraComment("1", Map.of("k", "v"), Map.of("a", "b"), "c", "u");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_not_be_equal_when_id_differs() {
        JiraComment a = new JiraComment("1", Map.of(), Map.of(), "c", "u");
        JiraComment b = new JiraComment("2", Map.of(), Map.of(), "c", "u");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_include_all_fields_in_toString() {
        JiraComment comment = new JiraComment("10001", Map.of("k", "v"), Map.of("a", "b"), "2026-01-01", "2026-01-02");

        String str = comment.toString();

        assertThat(str).contains("10001");
        assertThat(str).contains("2026-01-01");
        assertThat(str).contains("2026-01-02");
    }
}
