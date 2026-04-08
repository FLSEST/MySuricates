package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JiraIssueTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        Map<String, Object> fields = Map.of("summary", "Fix bug", "priority", Map.of("name", "High"));
        JiraIssue issue = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", fields);

        assertThat(issue.id()).isEqualTo("10001");
        assertThat(issue.key()).isEqualTo("PROJ-42");
        assertThat(issue.self()).isEqualTo("https://jira.example.com/rest/api/2/issue/10001");
        assertThat(issue.fields()).isEqualTo(fields);
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        JiraIssue issue = new JiraIssue(null, null, null, null);

        assertThat(issue.id()).isNull();
        assertThat(issue.key()).isNull();
        assertThat(issue.self()).isNull();
        assertThat(issue.fields()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {"id":"10001","key":"PROJ-42","self":"https://jira.example.com/rest/api/2/issue/10001","fields":{"summary":"Fix bug","status":{"name":"Open"}}}
                """;

        JiraIssue issue = objectMapper.readValue(json, JiraIssue.class);

        assertThat(issue.id()).isEqualTo("10001");
        assertThat(issue.key()).isEqualTo("PROJ-42");
        assertThat(issue.self()).isEqualTo("https://jira.example.com/rest/api/2/issue/10001");
        assertThat(issue.fields())
                .containsEntry("summary", "Fix bug")
                .containsKey("status");
    }

    @Test
    void should_deserializeWithNulls_when_fieldsAreMissing() throws Exception {
        String json = """
                {"id":"10001"}
                """;

        JiraIssue issue = objectMapper.readValue(json, JiraIssue.class);

        assertThat(issue.id()).isEqualTo("10001");
        assertThat(issue.key()).isNull();
        assertThat(issue.self()).isNull();
        assertThat(issue.fields()).isNull();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {"id":"10001","key":"PROJ-42","self":"https://jira.example.com/rest/api/2/issue/10001","fields":{"summary":"Fix bug"},"expand":"renderedFields","changelog":{"maxResults":0}}
                """;

        JiraIssue issue = objectMapper.readValue(json, JiraIssue.class);

        assertThat(issue.id()).isEqualTo("10001");
        assertThat(issue.key()).isEqualTo("PROJ-42");
        assertThat(issue.fields()).containsEntry("summary", "Fix bug");
    }

    @Test
    void should_deserializeEmptyFields_when_emptyObjectProvided() throws Exception {
        String json = """
                {"id":"10001","key":"PROJ-42","self":"https://jira.example.com/rest/api/2/issue/10001","fields":{}}
                """;

        JiraIssue issue = objectMapper.readValue(json, JiraIssue.class);

        assertThat(issue.fields()).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeNullFields_when_nullProvided() throws Exception {
        String json = """
                {"id":"10001","key":"PROJ-42","self":"https://jira.example.com/rest/api/2/issue/10001","fields":null}
                """;

        JiraIssue issue = objectMapper.readValue(json, JiraIssue.class);

        assertThat(issue.fields()).isNull();
    }

    @Test
    void should_deserializeEmptyJson_when_noFieldsPresent() throws Exception {
        String json = "{}";

        JiraIssue issue = objectMapper.readValue(json, JiraIssue.class);

        assertThat(issue.id()).isNull();
        assertThat(issue.key()).isNull();
        assertThat(issue.self()).isNull();
        assertThat(issue.fields()).isNull();
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        Map<String, Object> fields = Map.of("summary", "Fix bug");
        JiraIssue issue = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", fields);

        String json = objectMapper.writeValueAsString(issue);

        assertThat(json).contains("\"id\":\"10001\"");
        assertThat(json).contains("\"key\":\"PROJ-42\"");
        assertThat(json).contains("\"self\":\"https://jira.example.com/rest/api/2/issue/10001\"");
        assertThat(json).contains("\"fields\":{");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        JiraIssue issue = new JiraIssue("10001", null, null, null);

        String json = objectMapper.writeValueAsString(issue);

        assertThat(json).contains("\"id\":\"10001\"");
        assertThat(json).contains("\"key\":null");
        assertThat(json).contains("\"self\":null");
        assertThat(json).contains("\"fields\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_serializedAndDeserialized() throws Exception {
        Map<String, Object> fields = Map.of("summary", "Fix bug", "description", "Detailed description");
        JiraIssue original = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", fields);

        String json = objectMapper.writeValueAsString(original);
        JiraIssue deserialized = objectMapper.readValue(json, JiraIssue.class);

        assertThat(deserialized.id()).isEqualTo(original.id());
        assertThat(deserialized.key()).isEqualTo(original.key());
        assertThat(deserialized.self()).isEqualTo(original.self());
        assertThat(deserialized.fields()).isEqualTo(original.fields());
    }

    @Test
    void should_preserveEmptyFields_when_roundtripped() throws Exception {
        JiraIssue original = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", Map.of());

        String json = objectMapper.writeValueAsString(original);
        JiraIssue deserialized = objectMapper.readValue(json, JiraIssue.class);

        assertThat(deserialized.fields()).isNotNull().isEmpty();
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        Map<String, Object> fields = Map.of("summary", "Fix bug");
        JiraIssue a = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", fields);
        JiraIssue b = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", fields);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        Map<String, Object> fields = Map.of("summary", "Fix bug");
        JiraIssue a = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", fields);
        JiraIssue b = new JiraIssue("10002", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", fields);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentKey() {
        Map<String, Object> fields = Map.of("summary", "Fix bug");
        JiraIssue a = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", fields);
        JiraIssue b = new JiraIssue("10001", "PROJ-99", "https://jira.example.com/rest/api/2/issue/10001", fields);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        JiraIssue issue = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", Map.of("summary", "Fix bug"));

        String str = issue.toString();

        assertThat(str).contains("10001");
        assertThat(str).contains("PROJ-42");
    }
}
