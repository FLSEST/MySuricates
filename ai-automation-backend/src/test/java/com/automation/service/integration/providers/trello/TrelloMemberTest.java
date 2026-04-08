package com.automation.service.integration.providers.trello;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrelloMemberTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        TrelloMember member = new TrelloMember("mem-1", "John Doe", "johndoe", "https://trello.com/avatar/johndoe.png");

        assertThat(member.id()).isEqualTo("mem-1");
        assertThat(member.fullName()).isEqualTo("John Doe");
        assertThat(member.username()).isEqualTo("johndoe");
        assertThat(member.avatarUrl()).isEqualTo("https://trello.com/avatar/johndoe.png");
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        TrelloMember member = new TrelloMember(null, null, null, null);

        assertThat(member.id()).isNull();
        assertThat(member.fullName()).isNull();
        assertThat(member.username()).isNull();
        assertThat(member.avatarUrl()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserialize_allFields() throws Exception {
        String json = """
                {"id":"mem-1","fullName":"Jane Smith","username":"janesmith","avatarUrl":"https://trello.com/avatar/jane.png"}
                """;

        TrelloMember member = objectMapper.readValue(json, TrelloMember.class);

        assertThat(member.id()).isEqualTo("mem-1");
        assertThat(member.fullName()).isEqualTo("Jane Smith");
        assertThat(member.username()).isEqualTo("janesmith");
        assertThat(member.avatarUrl()).isEqualTo("https://trello.com/avatar/jane.png");
    }

    @Test
    void should_handleMissingFields_when_fieldsAbsent() throws Exception {
        String json = """
                {}
                """;

        TrelloMember member = objectMapper.readValue(json, TrelloMember.class);

        assertThat(member.id()).isNull();
        assertThat(member.fullName()).isNull();
        assertThat(member.username()).isNull();
        assertThat(member.avatarUrl()).isNull();
    }

    @Test
    void should_ignoreUnknownFields_when_extraFieldsPresent() throws Exception {
        String json = """
                {"id":"mem-2","fullName":"Bob Builder","username":"bob","avatarUrl":"https://trello.com/avatar/bob.png","bio":"Builds stuff","idOrganizations":["org-1"]}
                """;

        TrelloMember member = objectMapper.readValue(json, TrelloMember.class);

        assertThat(member.id()).isEqualTo("mem-2");
        assertThat(member.fullName()).isEqualTo("Bob Builder");
        assertThat(member.username()).isEqualTo("bob");
        assertThat(member.avatarUrl()).isEqualTo("https://trello.com/avatar/bob.png");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        TrelloMember member = new TrelloMember("mem-3", "Alice Wonder", "alice", "https://trello.com/avatar/alice.png");

        String json = objectMapper.writeValueAsString(member);

        assertThat(json).contains("\"id\":\"mem-3\"");
        assertThat(json).contains("\"fullName\":\"Alice Wonder\"");
        assertThat(json).contains("\"username\":\"alice\"");
        assertThat(json).contains("\"avatarUrl\":\"https://trello.com/avatar/alice.png\"");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        TrelloMember member = new TrelloMember("only-id", null, null, null);

        String json = objectMapper.writeValueAsString(member);

        assertThat(json).contains("\"id\":\"only-id\"");
        assertThat(json).contains("\"fullName\":null");
        assertThat(json).contains("\"username\":null");
        assertThat(json).contains("\"avatarUrl\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_serializeAndDeserialize_roundtrip() throws Exception {
        TrelloMember original = new TrelloMember("roundtrip-id", "Roundtrip User", "roundtrip", "https://trello.com/avatar/roundtrip.png");

        String json = objectMapper.writeValueAsString(original);
        TrelloMember deserialized = objectMapper.readValue(json, TrelloMember.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        TrelloMember a = new TrelloMember("same-id", "Same Name", "same", "https://same.png");
        TrelloMember b = new TrelloMember("same-id", "Same Name", "same", "https://same.png");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        TrelloMember a = new TrelloMember("id-a", "Name", "user", "https://avatar.png");
        TrelloMember b = new TrelloMember("id-b", "Name", "user", "https://avatar.png");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentUsername() {
        TrelloMember a = new TrelloMember("same-id", "Same Name", "user-a", "https://avatar.png");
        TrelloMember b = new TrelloMember("same-id", "Same Name", "user-b", "https://avatar.png");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        TrelloMember member = new TrelloMember("mem-1", "John Doe", "johndoe", "https://trello.com/avatar/johndoe.png");

        String str = member.toString();

        assertThat(str).contains("mem-1");
        assertThat(str).contains("John Doe");
        assertThat(str).contains("johndoe");
        assertThat(str).contains("https://trello.com/avatar/johndoe.png");
    }
}
