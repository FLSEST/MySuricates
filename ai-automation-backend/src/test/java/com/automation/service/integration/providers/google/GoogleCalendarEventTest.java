package com.automation.service.integration.providers.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleCalendarEventTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        GoogleDateTime start = new GoogleDateTime("2026-04-25T10:00:00Z", null, "UTC");
        GoogleDateTime end = new GoogleDateTime("2026-04-25T11:00:00Z", null, "UTC");
        GoogleAttendee attendee = new GoogleAttendee("user@example.com", "accepted", "Test User");

        GoogleCalendarEvent event = new GoogleCalendarEvent(
                "evt-123",
                "Team Meeting",
                "Weekly sync",
                "Conference Room A",
                "confirmed",
                start,
                end,
                List.of(attendee),
                "https://calendar.google.com/event?eid=abc"
        );

        assertThat(event.id()).isEqualTo("evt-123");
        assertThat(event.summary()).isEqualTo("Team Meeting");
        assertThat(event.description()).isEqualTo("Weekly sync");
        assertThat(event.location()).isEqualTo("Conference Room A");
        assertThat(event.status()).isEqualTo("confirmed");
        assertThat(event.start()).isEqualTo(start);
        assertThat(event.end()).isEqualTo(end);
        assertThat(event.attendees()).containsExactly(attendee);
        assertThat(event.htmlLink()).isEqualTo("https://calendar.google.com/event?eid=abc");
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        GoogleCalendarEvent event = new GoogleCalendarEvent(
                null, null, null, null, null, null, null, null, null);

        assertThat(event.id()).isNull();
        assertThat(event.summary()).isNull();
        assertThat(event.description()).isNull();
        assertThat(event.location()).isNull();
        assertThat(event.status()).isNull();
        assertThat(event.start()).isNull();
        assertThat(event.end()).isNull();
        assertThat(event.attendees()).isNull();
        assertThat(event.htmlLink()).isNull();
    }

    @Test
    void should_supportEmptyAttendees_when_listIsEmpty() {
        GoogleCalendarEvent event = new GoogleCalendarEvent(
                "evt-1", "Solo", null, null, "confirmed", null, null, List.of(), null);

        assertThat(event.attendees()).isEmpty();
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        GoogleCalendarEvent a = new GoogleCalendarEvent(
                "id", "sum", "desc", "loc", "confirmed", null, null, null, "link");
        GoogleCalendarEvent b = new GoogleCalendarEvent(
                "id", "sum", "desc", "loc", "confirmed", null, null, null, "link");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {
                  "id":"evt-42",
                  "summary":"Project Kickoff",
                  "description":"Initial planning meeting",
                  "location":"Zoom",
                  "status":"confirmed",
                  "start":{"dateTime":"2026-05-01T09:00:00Z","timeZone":"UTC"},
                  "end":{"dateTime":"2026-05-01T10:00:00Z","timeZone":"UTC"},
                  "attendees":[{"email":"alice@example.com","responseStatus":"accepted","displayName":"Alice"}],
                  "htmlLink":"https://calendar.google.com/event?eid=xyz"
                }
                """;

        GoogleCalendarEvent event = objectMapper.readValue(json, GoogleCalendarEvent.class);

        assertThat(event.id()).isEqualTo("evt-42");
        assertThat(event.summary()).isEqualTo("Project Kickoff");
        assertThat(event.description()).isEqualTo("Initial planning meeting");
        assertThat(event.location()).isEqualTo("Zoom");
        assertThat(event.status()).isEqualTo("confirmed");
        assertThat(event.start()).isNotNull();
        assertThat(event.start().dateTime()).isEqualTo("2026-05-01T09:00:00Z");
        assertThat(event.end()).isNotNull();
        assertThat(event.end().dateTime()).isEqualTo("2026-05-01T10:00:00Z");
        assertThat(event.attendees()).hasSize(1);
        assertThat(event.attendees().get(0).email()).isEqualTo("alice@example.com");
        assertThat(event.htmlLink()).isEqualTo("https://calendar.google.com/event?eid=xyz");
    }

    @Test
    void should_deserializeWithNulls_when_optionalFieldsMissing() throws Exception {
        String json = """
                {"id":"evt-min","summary":"Minimal Event"}
                """;

        GoogleCalendarEvent event = objectMapper.readValue(json, GoogleCalendarEvent.class);

        assertThat(event.id()).isEqualTo("evt-min");
        assertThat(event.summary()).isEqualTo("Minimal Event");
        assertThat(event.description()).isNull();
        assertThat(event.location()).isNull();
        assertThat(event.status()).isNull();
        assertThat(event.start()).isNull();
        assertThat(event.end()).isNull();
        assertThat(event.attendees()).isNull();
        assertThat(event.htmlLink()).isNull();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {
                  "id":"evt-1",
                  "summary":"Meeting",
                  "kind":"calendar#event",
                  "etag":"\\"3123456789\\"",
                  "created":"2026-04-01T10:00:00.000Z",
                  "updated":"2026-04-02T10:00:00.000Z",
                  "creator":{"email":"creator@example.com"},
                  "organizer":{"email":"organizer@example.com"},
                  "iCalUID":"abc@google.com",
                  "sequence":0,
                  "reminders":{"useDefault":true}
                }
                """;

        GoogleCalendarEvent event = objectMapper.readValue(json, GoogleCalendarEvent.class);

        assertThat(event.id()).isEqualTo("evt-1");
        assertThat(event.summary()).isEqualTo("Meeting");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        GoogleCalendarEvent event = new GoogleCalendarEvent(
                "evt-99",
                "Demo",
                "A demo event",
                "Office",
                "confirmed",
                null,
                null,
                null,
                "https://link.example/evt-99"
        );

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"id\":\"evt-99\"");
        assertThat(json).contains("\"summary\":\"Demo\"");
        assertThat(json).contains("\"description\":\"A demo event\"");
        assertThat(json).contains("\"location\":\"Office\"");
        assertThat(json).contains("\"status\":\"confirmed\"");
        assertThat(json).contains("\"htmlLink\":\"https://link.example/evt-99\"");
    }
}
