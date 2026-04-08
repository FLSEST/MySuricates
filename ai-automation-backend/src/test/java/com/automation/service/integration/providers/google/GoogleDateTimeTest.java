package com.automation.service.integration.providers.google;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleDateTimeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_createdWithAllArgs() {
        GoogleDateTime dt = new GoogleDateTime("2026-04-08T10:00:00+02:00", "2026-04-08", "Europe/Berlin");

        assertThat(dt.dateTime()).isEqualTo("2026-04-08T10:00:00+02:00");
        assertThat(dt.date()).isEqualTo("2026-04-08");
        assertThat(dt.timeZone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void should_allowNullFields_when_createdWithNulls() {
        GoogleDateTime dt = new GoogleDateTime(null, null, null);

        assertThat(dt.dateTime()).isNull();
        assertThat(dt.date()).isNull();
        assertThat(dt.timeZone()).isNull();
    }

    // --- JSON deserialization ---

    @Test
    void should_deserializeFromJson_when_allFieldsPresent() throws Exception {
        String json = """
                {"dateTime":"2026-04-08T14:30:00Z","date":"2026-04-08","timeZone":"America/New_York"}
                """;

        GoogleDateTime dt = objectMapper.readValue(json, GoogleDateTime.class);

        assertThat(dt.dateTime()).isEqualTo("2026-04-08T14:30:00Z");
        assertThat(dt.date()).isEqualTo("2026-04-08");
        assertThat(dt.timeZone()).isEqualTo("America/New_York");
    }

    @Test
    void should_deserializeWithNulls_when_fieldsAreMissing() throws Exception {
        String json = """
                {"dateTime":"2026-04-08T09:00:00+02:00"}
                """;

        GoogleDateTime dt = objectMapper.readValue(json, GoogleDateTime.class);

        assertThat(dt.dateTime()).isEqualTo("2026-04-08T09:00:00+02:00");
        assertThat(dt.date()).isNull();
        assertThat(dt.timeZone()).isNull();
    }

    @Test
    void should_deserializeDateOnly_when_allDayEvent() throws Exception {
        String json = """
                {"date":"2026-04-08"}
                """;

        GoogleDateTime dt = objectMapper.readValue(json, GoogleDateTime.class);

        assertThat(dt.dateTime()).isNull();
        assertThat(dt.date()).isEqualTo("2026-04-08");
        assertThat(dt.timeZone()).isNull();
    }

    @Test
    void should_ignoreUnknownProperties_when_extraFieldsPresent() throws Exception {
        String json = """
                {"dateTime":"2026-04-08T10:00:00Z","date":"2026-04-08","timeZone":"UTC","etag":"abc123","kind":"calendar#event","unknownField":42}
                """;

        GoogleDateTime dt = objectMapper.readValue(json, GoogleDateTime.class);

        assertThat(dt.dateTime()).isEqualTo("2026-04-08T10:00:00Z");
        assertThat(dt.date()).isEqualTo("2026-04-08");
        assertThat(dt.timeZone()).isEqualTo("UTC");
    }

    // --- JSON serialization ---

    @Test
    void should_serializeToJson_when_allFieldsSet() throws Exception {
        GoogleDateTime dt = new GoogleDateTime("2026-04-08T10:00:00+02:00", "2026-04-08", "Europe/Berlin");

        String json = objectMapper.writeValueAsString(dt);

        assertThat(json).contains("\"dateTime\":\"2026-04-08T10:00:00+02:00\"");
        assertThat(json).contains("\"date\":\"2026-04-08\"");
        assertThat(json).contains("\"timeZone\":\"Europe/Berlin\"");
    }

    @Test
    void should_serializeNullFields_when_fieldsAreNull() throws Exception {
        GoogleDateTime dt = new GoogleDateTime("2026-04-08T10:00:00Z", null, null);

        String json = objectMapper.writeValueAsString(dt);

        assertThat(json).contains("\"dateTime\":\"2026-04-08T10:00:00Z\"");
        assertThat(json).contains("\"date\":null");
        assertThat(json).contains("\"timeZone\":null");
    }

    // --- JSON roundtrip ---

    @Test
    void should_preserveAllFields_when_serializedAndDeserialized() throws Exception {
        GoogleDateTime original = new GoogleDateTime("2026-04-08T16:00:00+02:00", "2026-04-08", "Europe/Berlin");

        String json = objectMapper.writeValueAsString(original);
        GoogleDateTime deserialized = objectMapper.readValue(json, GoogleDateTime.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void should_preservePartialFields_when_roundtrippedWithNulls() throws Exception {
        GoogleDateTime original = new GoogleDateTime(null, "2026-04-08", null);

        String json = objectMapper.writeValueAsString(original);
        GoogleDateTime deserialized = objectMapper.readValue(json, GoogleDateTime.class);

        assertThat(deserialized).isEqualTo(original);
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        GoogleDateTime a = new GoogleDateTime("2026-04-08T10:00:00Z", "2026-04-08", "UTC");
        GoogleDateTime b = new GoogleDateTime("2026-04-08T10:00:00Z", "2026-04-08", "UTC");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentDateTime() {
        GoogleDateTime a = new GoogleDateTime("2026-04-08T10:00:00Z", "2026-04-08", "UTC");
        GoogleDateTime b = new GoogleDateTime("2026-04-08T11:00:00Z", "2026-04-08", "UTC");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTimeZone() {
        GoogleDateTime a = new GoogleDateTime("2026-04-08T10:00:00Z", "2026-04-08", "UTC");
        GoogleDateTime b = new GoogleDateTime("2026-04-08T10:00:00Z", "2026-04-08", "Europe/Berlin");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldValues_when_callingToString() {
        GoogleDateTime dt = new GoogleDateTime("2026-04-08T10:00:00+02:00", "2026-04-08", "Europe/Berlin");

        String str = dt.toString();

        assertThat(str).contains("2026-04-08T10:00:00+02:00");
        assertThat(str).contains("2026-04-08");
        assertThat(str).contains("Europe/Berlin");
    }
}
