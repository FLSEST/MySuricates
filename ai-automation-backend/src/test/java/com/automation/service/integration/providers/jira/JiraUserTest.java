package com.automation.service.integration.providers.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JiraUserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        JiraUser user = new JiraUser("abc123", "John Doe", "john@example.com");

        assertThat(user.accountId()).isEqualTo("abc123");
        assertThat(user.displayName()).isEqualTo("John Doe");
        assertThat(user.emailAddress()).isEqualTo("john@example.com");
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        JiraUser user = new JiraUser(null, null, null);

        assertThat(user.accountId()).isNull();
        assertThat(user.displayName()).isNull();
        assertThat(user.emailAddress()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {"accountId":"abc123","displayName":"Alice","emailAddress":"alice@example.com"}
                """;

        JiraUser user = objectMapper.readValue(json, JiraUser.class);

        assertThat(user.accountId()).isEqualTo("abc123");
        assertThat(user.displayName()).isEqualTo("Alice");
        assertThat(user.emailAddress()).isEqualTo("alice@example.com");
    }

    @Test
    void should_deserializeWithNulls_when_fieldsAreMissing() throws Exception {
        String json = """
                {"accountId":"abc123"}
                """;

        JiraUser user = objectMapper.readValue(json, JiraUser.class);

        assertThat(user.accountId()).isEqualTo("abc123");
        assertThat(user.displayName()).isNull();
        assertThat(user.emailAddress()).isNull();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {"accountId":"abc123","displayName":"User","emailAddress":"user@example.com","self":"https://jira.example.com/user","active":true,"timeZone":"Europe/Berlin"}
                """;

        JiraUser user = objectMapper.readValue(json, JiraUser.class);

        assertThat(user.accountId()).isEqualTo("abc123");
        assertThat(user.displayName()).isEqualTo("User");
        assertThat(user.emailAddress()).isEqualTo("user@example.com");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        JiraUser user = new JiraUser("xyz789", "Tester", "tester@example.com");

        String json = objectMapper.writeValueAsString(user);

        assertThat(json).contains("\"accountId\":\"xyz789\"");
        assertThat(json).contains("\"displayName\":\"Tester\"");
        assertThat(json).contains("\"emailAddress\":\"tester@example.com\"");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        JiraUser user = new JiraUser("only-id", null, null);

        String json = objectMapper.writeValueAsString(user);

        assertThat(json).contains("\"accountId\":\"only-id\"");
        assertThat(json).contains("\"displayName\":null");
        assertThat(json).contains("\"emailAddress\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_serializedAndDeserialized() throws Exception {
        JiraUser original = new JiraUser("roundtrip-id", "Roundtrip User", "roundtrip@example.com");

        String json = objectMapper.writeValueAsString(original);
        JiraUser deserialized = objectMapper.readValue(json, JiraUser.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        JiraUser a = new JiraUser("same-id", "Same", "same@example.com");
        JiraUser b = new JiraUser("same-id", "Same", "same@example.com");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentAccountId() {
        JiraUser a = new JiraUser("id-a", "Name", "email@example.com");
        JiraUser b = new JiraUser("id-b", "Name", "email@example.com");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDisplayName() {
        JiraUser a = new JiraUser("same-id", "Alice", "email@example.com");
        JiraUser b = new JiraUser("same-id", "Bob", "email@example.com");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        JiraUser user = new JiraUser("abc123", "User", "user@example.com");

        String str = user.toString();

        assertThat(str).contains("abc123");
        assertThat(str).contains("User");
        assertThat(str).contains("user@example.com");
    }
}
