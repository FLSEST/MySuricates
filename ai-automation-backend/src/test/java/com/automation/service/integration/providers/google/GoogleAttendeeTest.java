package com.automation.service.integration.providers.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleAttendeeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        GoogleAttendee attendee = new GoogleAttendee("user@example.com", "accepted", "Test User");

        assertThat(attendee.email()).isEqualTo("user@example.com");
        assertThat(attendee.responseStatus()).isEqualTo("accepted");
        assertThat(attendee.displayName()).isEqualTo("Test User");
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        GoogleAttendee attendee = new GoogleAttendee(null, null, null);

        assertThat(attendee.email()).isNull();
        assertThat(attendee.responseStatus()).isNull();
        assertThat(attendee.displayName()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {"email":"alice@example.com","responseStatus":"tentative","displayName":"Alice"}
                """;

        GoogleAttendee attendee = objectMapper.readValue(json, GoogleAttendee.class);

        assertThat(attendee.email()).isEqualTo("alice@example.com");
        assertThat(attendee.responseStatus()).isEqualTo("tentative");
        assertThat(attendee.displayName()).isEqualTo("Alice");
    }

    @Test
    void should_deserializeWithNulls_when_fieldsAreMissing() throws Exception {
        String json = """
                {"email":"bob@example.com"}
                """;

        GoogleAttendee attendee = objectMapper.readValue(json, GoogleAttendee.class);

        assertThat(attendee.email()).isEqualTo("bob@example.com");
        assertThat(attendee.responseStatus()).isNull();
        assertThat(attendee.displayName()).isNull();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {"email":"user@example.com","responseStatus":"declined","displayName":"User","self":true,"organizer":false,"additionalGuests":2}
                """;

        GoogleAttendee attendee = objectMapper.readValue(json, GoogleAttendee.class);

        assertThat(attendee.email()).isEqualTo("user@example.com");
        assertThat(attendee.responseStatus()).isEqualTo("declined");
        assertThat(attendee.displayName()).isEqualTo("User");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        GoogleAttendee attendee = new GoogleAttendee("test@example.com", "needsAction", "Tester");

        String json = objectMapper.writeValueAsString(attendee);

        assertThat(json).contains("\"email\":\"test@example.com\"");
        assertThat(json).contains("\"responseStatus\":\"needsAction\"");
        assertThat(json).contains("\"displayName\":\"Tester\"");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        GoogleAttendee attendee = new GoogleAttendee("only@email.com", null, null);

        String json = objectMapper.writeValueAsString(attendee);

        assertThat(json).contains("\"email\":\"only@email.com\"");
        assertThat(json).contains("\"responseStatus\":null");
        assertThat(json).contains("\"displayName\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_serializedAndDeserialized() throws Exception {
        GoogleAttendee original = new GoogleAttendee("roundtrip@example.com", "accepted", "Roundtrip User");

        String json = objectMapper.writeValueAsString(original);
        GoogleAttendee deserialized = objectMapper.readValue(json, GoogleAttendee.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        GoogleAttendee a = new GoogleAttendee("same@example.com", "accepted", "Same");
        GoogleAttendee b = new GoogleAttendee("same@example.com", "accepted", "Same");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentEmail() {
        GoogleAttendee a = new GoogleAttendee("a@example.com", "accepted", "Name");
        GoogleAttendee b = new GoogleAttendee("b@example.com", "accepted", "Name");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentResponseStatus() {
        GoogleAttendee a = new GoogleAttendee("user@example.com", "accepted", "Name");
        GoogleAttendee b = new GoogleAttendee("user@example.com", "declined", "Name");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        GoogleAttendee attendee = new GoogleAttendee("user@example.com", "accepted", "User");

        String str = attendee.toString();

        assertThat(str).contains("user@example.com");
        assertThat(str).contains("accepted");
        assertThat(str).contains("User");
    }
}
