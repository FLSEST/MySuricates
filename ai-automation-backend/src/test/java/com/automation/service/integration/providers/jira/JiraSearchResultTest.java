package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JiraSearchResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        JiraIssue issue = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", Map.of("summary", "Fix bug"));
        List<JiraIssue> issues = List.of(issue);
        JiraSearchResult result = new JiraSearchResult(issues, 100, 50, 0);

        assertThat(result.issues()).isEqualTo(issues);
        assertThat(result.total()).isEqualTo(100);
        assertThat(result.maxResults()).isEqualTo(50);
        assertThat(result.startAt()).isEqualTo(0);
    }

    @Test
    void should_allowNullIssues_when_createdWithNull() {
        JiraSearchResult result = new JiraSearchResult(null, 0, 0, 0);

        assertThat(result.issues()).isNull();
        assertThat(result.total()).isZero();
        assertThat(result.maxResults()).isZero();
        assertThat(result.startAt()).isZero();
    }

    @Test
    void should_allowEmptyIssues_when_createdWithEmptyList() {
        JiraSearchResult result = new JiraSearchResult(Collections.emptyList(), 0, 25, 0);

        assertThat(result.issues()).isNotNull().isEmpty();
        assertThat(result.maxResults()).isEqualTo(25);
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {"issues":[{"id":"10001","key":"PROJ-42","self":"https://jira.example.com/rest/api/2/issue/10001","fields":{"summary":"Fix bug"}}],"total":1,"maxResults":50,"startAt":0}
                """;

        JiraSearchResult result = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(result.issues()).hasSize(1);
        assertThat(result.issues().get(0).key()).isEqualTo("PROJ-42");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.maxResults()).isEqualTo(50);
        assertThat(result.startAt()).isZero();
    }

    @Test
    void should_deserializeMultipleIssues_when_listContainsMany() throws Exception {
        String json = """
                {"issues":[{"id":"10001","key":"PROJ-1"},{"id":"10002","key":"PROJ-2"},{"id":"10003","key":"PROJ-3"}],"total":100,"maxResults":3,"startAt":0}
                """;

        JiraSearchResult result = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(result.issues()).hasSize(3);
        assertThat(result.issues()).extracting(JiraIssue::key).containsExactly("PROJ-1", "PROJ-2", "PROJ-3");
        assertThat(result.total()).isEqualTo(100);
    }

    @Test
    void should_deserializeWithDefaults_when_fieldsAreMissing() throws Exception {
        String json = """
                {"issues":[]}
                """;

        JiraSearchResult result = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(result.issues()).isNotNull().isEmpty();
        assertThat(result.total()).isZero();
        assertThat(result.maxResults()).isZero();
        assertThat(result.startAt()).isZero();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {"issues":[],"total":5,"maxResults":50,"startAt":0,"expand":"schema,names","warningMessages":["some warning"]}
                """;

        JiraSearchResult result = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(result.total()).isEqualTo(5);
        assertThat(result.maxResults()).isEqualTo(50);
        assertThat(result.startAt()).isZero();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void should_deserializeEmptyJson_when_noFieldsPresent() throws Exception {
        String json = "{}";

        JiraSearchResult result = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(result.issues()).isNull();
        assertThat(result.total()).isZero();
        assertThat(result.maxResults()).isZero();
        assertThat(result.startAt()).isZero();
    }

    @Test
    void should_deserializeNullIssues_when_nullProvided() throws Exception {
        String json = """
                {"issues":null,"total":0,"maxResults":50,"startAt":0}
                """;

        JiraSearchResult result = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(result.issues()).isNull();
        assertThat(result.maxResults()).isEqualTo(50);
    }

    @Test
    void should_deserializeEmptyIssues_when_emptyArrayProvided() throws Exception {
        String json = """
                {"issues":[],"total":0,"maxResults":25,"startAt":0}
                """;

        JiraSearchResult result = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(result.issues()).isNotNull().isEmpty();
        assertThat(result.maxResults()).isEqualTo(25);
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        JiraIssue issue = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", Map.of("summary", "Fix bug"));
        JiraSearchResult result = new JiraSearchResult(List.of(issue), 1, 50, 0);

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"total\":1");
        assertThat(json).contains("\"maxResults\":50");
        assertThat(json).contains("\"startAt\":0");
        assertThat(json).contains("\"issues\":[");
        assertThat(json).contains("\"key\":\"PROJ-42\"");
    }

    @Test
    void should_serializeNullIssues_when_issuesAreNull() throws Exception {
        JiraSearchResult result = new JiraSearchResult(null, 0, 50, 0);

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"issues\":null");
        assertThat(json).contains("\"total\":0");
        assertThat(json).contains("\"maxResults\":50");
    }

    @Test
    void should_serializeEmptyIssues_when_issuesAreEmpty() throws Exception {
        JiraSearchResult result = new JiraSearchResult(Collections.emptyList(), 0, 25, 10);

        String json = objectMapper.writeValueAsString(result);

        assertThat(json).contains("\"issues\":[]");
        assertThat(json).contains("\"startAt\":10");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_serializedAndDeserialized() throws Exception {
        JiraIssue issue = new JiraIssue("10001", "PROJ-42", "https://jira.example.com/rest/api/2/issue/10001", Map.of("summary", "Fix bug"));
        JiraSearchResult original = new JiraSearchResult(List.of(issue), 100, 50, 25);

        String json = objectMapper.writeValueAsString(original);
        JiraSearchResult deserialized = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(deserialized.total()).isEqualTo(original.total());
        assertThat(deserialized.maxResults()).isEqualTo(original.maxResults());
        assertThat(deserialized.startAt()).isEqualTo(original.startAt());
        assertThat(deserialized.issues()).hasSize(1);
        assertThat(deserialized.issues().get(0).key()).isEqualTo("PROJ-42");
    }

    @Test
    void should_preserveEmptyIssues_when_roundtripped() throws Exception {
        JiraSearchResult original = new JiraSearchResult(Collections.emptyList(), 0, 50, 0);

        String json = objectMapper.writeValueAsString(original);
        JiraSearchResult deserialized = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(deserialized.issues()).isNotNull().isEmpty();
        assertThat(deserialized.total()).isZero();
        assertThat(deserialized.maxResults()).isEqualTo(50);
    }

    @Test
    void should_preserveNullIssues_when_roundtripped() throws Exception {
        JiraSearchResult original = new JiraSearchResult(null, 0, 25, 0);

        String json = objectMapper.writeValueAsString(original);
        JiraSearchResult deserialized = objectMapper.readValue(json, JiraSearchResult.class);

        assertThat(deserialized.issues()).isNull();
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        JiraIssue issue = new JiraIssue("10001", "PROJ-42", null, null);
        JiraSearchResult a = new JiraSearchResult(List.of(issue), 1, 50, 0);
        JiraSearchResult b = new JiraSearchResult(List.of(issue), 1, 50, 0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentTotal() {
        JiraSearchResult a = new JiraSearchResult(Collections.emptyList(), 10, 50, 0);
        JiraSearchResult b = new JiraSearchResult(Collections.emptyList(), 20, 50, 0);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentMaxResults() {
        JiraSearchResult a = new JiraSearchResult(Collections.emptyList(), 10, 50, 0);
        JiraSearchResult b = new JiraSearchResult(Collections.emptyList(), 10, 100, 0);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        JiraSearchResult result = new JiraSearchResult(Collections.emptyList(), 42, 50, 10);

        String str = result.toString();

        assertThat(str).contains("42");
        assertThat(str).contains("50");
        assertThat(str).contains("10");
    }
}
