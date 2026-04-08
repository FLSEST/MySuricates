package com.automation.service.integration.providers.google;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleEventListTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        GoogleCalendarEvent event = new GoogleCalendarEvent("e1", "Meeting", null, null, "confirmed", null, null, null, null);
        GoogleEventList list = new GoogleEventList(List.of(event), "token123", "My Calendar");

        assertThat(list.items()).hasSize(1);
        assertThat(list.items().getFirst().id()).isEqualTo("e1");
        assertThat(list.nextPageToken()).isEqualTo("token123");
        assertThat(list.summary()).isEqualTo("My Calendar");
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        GoogleEventList list = new GoogleEventList(null, null, null);

        assertThat(list.items()).isNull();
        assertThat(list.nextPageToken()).isNull();
        assertThat(list.summary()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_populatedItemsList() throws Exception {
        String json = """
                {
                  "items": [
                    {"id":"evt1","summary":"Standup","status":"confirmed"},
                    {"id":"evt2","summary":"Lunch","status":"tentative"}
                  ],
                  "nextPageToken": "abc123",
                  "summary": "Work Calendar"
                }
                """;

        GoogleEventList list = objectMapper.readValue(json, GoogleEventList.class);

        assertThat(list.items()).hasSize(2);
        assertThat(list.items().get(0).id()).isEqualTo("evt1");
        assertThat(list.items().get(0).summary()).isEqualTo("Standup");
        assertThat(list.items().get(1).id()).isEqualTo("evt2");
        assertThat(list.items().get(1).summary()).isEqualTo("Lunch");
        assertThat(list.nextPageToken()).isEqualTo("abc123");
        assertThat(list.summary()).isEqualTo("Work Calendar");
    }

    @Test
    void should_deserializeEmptyList_when_itemsArrayIsEmpty() throws Exception {
        String json = """
                {"items":[],"nextPageToken":null,"summary":"Empty Calendar"}
                """;

        GoogleEventList list = objectMapper.readValue(json, GoogleEventList.class);

        assertThat(list.items()).isEmpty();
        assertThat(list.nextPageToken()).isNull();
        assertThat(list.summary()).isEqualTo("Empty Calendar");
    }

    @Test
    void should_deserializeWithNulls_when_fieldsAreMissing() throws Exception {
        String json = "{}";

        GoogleEventList list = objectMapper.readValue(json, GoogleEventList.class);

        assertThat(list.items()).isNull();
        assertThat(list.nextPageToken()).isNull();
        assertThat(list.summary()).isNull();
    }

    @Test
    void should_deserializeWithNullItems_when_itemsExplicitlyNull() throws Exception {
        String json = """
                {"items":null,"nextPageToken":"tok","summary":null}
                """;

        GoogleEventList list = objectMapper.readValue(json, GoogleEventList.class);

        assertThat(list.items()).isNull();
        assertThat(list.nextPageToken()).isEqualTo("tok");
        assertThat(list.summary()).isNull();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {"items":[],"nextPageToken":"p1","summary":"Cal","kind":"calendar#events","etag":"xyz","accessRole":"owner","defaultReminders":[]}
                """;

        GoogleEventList list = objectMapper.readValue(json, GoogleEventList.class);

        assertThat(list.items()).isEmpty();
        assertThat(list.nextPageToken()).isEqualTo("p1");
        assertThat(list.summary()).isEqualTo("Cal");
    }

    @Test
    void should_deserializeNestedEvents_when_eventsHaveFullDetails() throws Exception {
        String json = """
                {
                  "items": [
                    {
                      "id": "full1",
                      "summary": "Team Sync",
                      "description": "Weekly sync",
                      "location": "Room A",
                      "status": "confirmed",
                      "start": {"dateTime": "2026-04-08T10:00:00+02:00", "timeZone": "Europe/Berlin"},
                      "end": {"dateTime": "2026-04-08T11:00:00+02:00", "timeZone": "Europe/Berlin"},
                      "attendees": [{"email": "alice@example.com", "responseStatus": "accepted", "displayName": "Alice"}],
                      "htmlLink": "https://calendar.google.com/event?eid=full1"
                    }
                  ],
                  "summary": "Primary"
                }
                """;

        GoogleEventList list = objectMapper.readValue(json, GoogleEventList.class);

        assertThat(list.items()).hasSize(1);
        GoogleCalendarEvent event = list.items().getFirst();
        assertThat(event.id()).isEqualTo("full1");
        assertThat(event.summary()).isEqualTo("Team Sync");
        assertThat(event.start().dateTime()).isEqualTo("2026-04-08T10:00:00+02:00");
        assertThat(event.attendees()).hasSize(1);
        assertThat(event.attendees().getFirst().email()).isEqualTo("alice@example.com");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        GoogleCalendarEvent event = new GoogleCalendarEvent("s1", "Serialize Test", null, null, "confirmed", null, null, null, null);
        GoogleEventList list = new GoogleEventList(List.of(event), "nextTok", "Serialized Cal");

        String json = objectMapper.writeValueAsString(list);

        assertThat(json).contains("\"nextPageToken\":\"nextTok\"");
        assertThat(json).contains("\"summary\":\"Serialized Cal\"");
        assertThat(json).contains("\"items\":");
        assertThat(json).contains("\"id\":\"s1\"");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        GoogleEventList list = new GoogleEventList(null, null, null);

        String json = objectMapper.writeValueAsString(list);

        assertThat(json).contains("\"items\":null");
        assertThat(json).contains("\"nextPageToken\":null");
        assertThat(json).contains("\"summary\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_serializedAndDeserialized() throws Exception {
        GoogleAttendee attendee = new GoogleAttendee("rt@example.com", "accepted", "RT User");
        GoogleDateTime start = new GoogleDateTime("2026-04-08T09:00:00+02:00", null, "Europe/Berlin");
        GoogleDateTime end = new GoogleDateTime("2026-04-08T10:00:00+02:00", null, "Europe/Berlin");
        GoogleCalendarEvent event = new GoogleCalendarEvent("rt1", "Roundtrip", "desc", "Office", "confirmed", start, end, List.of(attendee), "https://link");
        GoogleEventList original = new GoogleEventList(List.of(event), "rtToken", "RT Calendar");

        String json = objectMapper.writeValueAsString(original);
        GoogleEventList deserialized = objectMapper.readValue(json, GoogleEventList.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void should_preserveEmptyItems_when_roundtrippedWithEmptyList() throws Exception {
        GoogleEventList original = new GoogleEventList(List.of(), null, "Empty");

        String json = objectMapper.writeValueAsString(original);
        GoogleEventList deserialized = objectMapper.readValue(json, GoogleEventList.class);

        assertThat(deserialized.items()).isEmpty();
        assertThat(deserialized.summary()).isEqualTo("Empty");
        assertThat(deserialized.nextPageToken()).isNull();
    }

    @Test
    void should_preserveNullFields_when_roundtrippedWithNulls() throws Exception {
        GoogleEventList original = new GoogleEventList(null, null, null);

        String json = objectMapper.writeValueAsString(original);
        GoogleEventList deserialized = objectMapper.readValue(json, GoogleEventList.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        GoogleCalendarEvent event = new GoogleCalendarEvent("eq1", "Same", null, null, null, null, null, null, null);
        GoogleEventList a = new GoogleEventList(List.of(event), "tok", "Cal");
        GoogleEventList b = new GoogleEventList(List.of(event), "tok", "Cal");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentNextPageToken() {
        GoogleEventList a = new GoogleEventList(List.of(), "tok1", "Cal");
        GoogleEventList b = new GoogleEventList(List.of(), "tok2", "Cal");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentSummary() {
        GoogleEventList a = new GoogleEventList(List.of(), null, "Cal A");
        GoogleEventList b = new GoogleEventList(List.of(), null, "Cal B");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentItems() {
        GoogleCalendarEvent e1 = new GoogleCalendarEvent("x1", "X", null, null, null, null, null, null, null);
        GoogleCalendarEvent e2 = new GoogleCalendarEvent("x2", "Y", null, null, null, null, null, null, null);
        GoogleEventList a = new GoogleEventList(List.of(e1), null, null);
        GoogleEventList b = new GoogleEventList(List.of(e2), null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        GoogleEventList list = new GoogleEventList(List.of(), "myToken", "My Cal");

        String str = list.toString();

        assertThat(str).contains("myToken");
        assertThat(str).contains("My Cal");
    }
}
