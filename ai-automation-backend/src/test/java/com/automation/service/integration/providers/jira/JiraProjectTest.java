package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JiraProjectTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        JiraProject project = new JiraProject("10001", "PROJ", "My Project", "software");

        assertThat(project.id()).isEqualTo("10001");
        assertThat(project.key()).isEqualTo("PROJ");
        assertThat(project.name()).isEqualTo("My Project");
        assertThat(project.projectTypeKey()).isEqualTo("software");
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        JiraProject project = new JiraProject(null, null, null, null);

        assertThat(project.id()).isNull();
        assertThat(project.key()).isNull();
        assertThat(project.name()).isNull();
        assertThat(project.projectTypeKey()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {"id":"10001","key":"PROJ","name":"My Project","projectTypeKey":"software"}
                """;

        JiraProject project = objectMapper.readValue(json, JiraProject.class);

        assertThat(project.id()).isEqualTo("10001");
        assertThat(project.key()).isEqualTo("PROJ");
        assertThat(project.name()).isEqualTo("My Project");
        assertThat(project.projectTypeKey()).isEqualTo("software");
    }

    @Test
    void should_deserializeWithNulls_when_fieldsAreMissing() throws Exception {
        String json = """
                {"id":"10001"}
                """;

        JiraProject project = objectMapper.readValue(json, JiraProject.class);

        assertThat(project.id()).isEqualTo("10001");
        assertThat(project.key()).isNull();
        assertThat(project.name()).isNull();
        assertThat(project.projectTypeKey()).isNull();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {"id":"10001","key":"PROJ","name":"My Project","projectTypeKey":"software","self":"https://jira.example.com/project/10001","avatarUrls":{},"simplified":false}
                """;

        JiraProject project = objectMapper.readValue(json, JiraProject.class);

        assertThat(project.id()).isEqualTo("10001");
        assertThat(project.key()).isEqualTo("PROJ");
        assertThat(project.name()).isEqualTo("My Project");
        assertThat(project.projectTypeKey()).isEqualTo("software");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        JiraProject project = new JiraProject("10001", "PROJ", "My Project", "software");

        String json = objectMapper.writeValueAsString(project);

        assertThat(json).contains("\"id\":\"10001\"");
        assertThat(json).contains("\"key\":\"PROJ\"");
        assertThat(json).contains("\"name\":\"My Project\"");
        assertThat(json).contains("\"projectTypeKey\":\"software\"");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        JiraProject project = new JiraProject("10001", null, null, null);

        String json = objectMapper.writeValueAsString(project);

        assertThat(json).contains("\"id\":\"10001\"");
        assertThat(json).contains("\"key\":null");
        assertThat(json).contains("\"name\":null");
        assertThat(json).contains("\"projectTypeKey\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_serializedAndDeserialized() throws Exception {
        JiraProject original = new JiraProject("10001", "PROJ", "My Project", "software");

        String json = objectMapper.writeValueAsString(original);
        JiraProject deserialized = objectMapper.readValue(json, JiraProject.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        JiraProject a = new JiraProject("10001", "PROJ", "My Project", "software");
        JiraProject b = new JiraProject("10001", "PROJ", "My Project", "software");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        JiraProject a = new JiraProject("10001", "PROJ", "My Project", "software");
        JiraProject b = new JiraProject("10002", "PROJ", "My Project", "software");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentKey() {
        JiraProject a = new JiraProject("10001", "PROJ", "My Project", "software");
        JiraProject b = new JiraProject("10001", "OTHER", "My Project", "software");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        JiraProject project = new JiraProject("10001", "PROJ", "My Project", "software");

        String str = project.toString();

        assertThat(str).contains("10001");
        assertThat(str).contains("PROJ");
        assertThat(str).contains("My Project");
        assertThat(str).contains("software");
    }
}
