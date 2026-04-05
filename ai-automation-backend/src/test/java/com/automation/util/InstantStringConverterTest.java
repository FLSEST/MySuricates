package com.automation.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstantStringConverterTest {

    private InstantStringConverter converter;

    @BeforeEach
    void setUp() {
        converter = new InstantStringConverter();
    }

    // --- convertToDatabaseColumn ---

    @Test
    void should_returnNull_when_attributeIsNull() {
        String result = converter.convertToDatabaseColumn(null);

        assertThat(result).isNull();
    }

    @Test
    void should_returnIsoString_when_attributeIsInstant() {
        Instant instant = Instant.parse("2026-04-05T12:30:00Z");

        String result = converter.convertToDatabaseColumn(instant);

        assertThat(result).isEqualTo("2026-04-05T12:30:00Z");
    }

    @Test
    void should_returnIsoStringWithNanos_when_attributeHasNanoPrecision() {
        Instant instant = Instant.parse("2026-04-05T12:30:00.123456789Z");

        String result = converter.convertToDatabaseColumn(instant);

        assertThat(result).isEqualTo("2026-04-05T12:30:00.123456789Z");
    }

    @Test
    void should_returnEpochString_when_attributeIsEpoch() {
        Instant epoch = Instant.EPOCH;

        String result = converter.convertToDatabaseColumn(epoch);

        assertThat(result).isEqualTo("1970-01-01T00:00:00Z");
    }

    // --- convertToEntityAttribute ---

    @Test
    void should_returnNull_when_dbDataIsNull() {
        Instant result = converter.convertToEntityAttribute(null);

        assertThat(result).isNull();
    }

    @Test
    void should_returnNull_when_dbDataIsEmpty() {
        Instant result = converter.convertToEntityAttribute("");

        assertThat(result).isNull();
    }

    @Test
    void should_parseInstant_when_dbDataIsValidIsoString() {
        Instant result = converter.convertToEntityAttribute("2026-04-05T12:30:00Z");

        assertThat(result).isEqualTo(Instant.parse("2026-04-05T12:30:00Z"));
    }

    @Test
    void should_parseInstantWithNanos_when_dbDataHasNanoPrecision() {
        Instant result = converter.convertToEntityAttribute("2026-04-05T12:30:00.123456789Z");

        assertThat(result).isEqualTo(Instant.parse("2026-04-05T12:30:00.123456789Z"));
    }

    @Test
    void should_throwDateTimeParseException_when_dbDataIsMalformed() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("not-a-date"))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void should_throwDateTimeParseException_when_dbDataIsRandomText() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("hello world"))
                .isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void should_throwDateTimeParseException_when_dbDataIsPartialDate() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("2026-04-05"))
                .isInstanceOf(DateTimeParseException.class);
    }

    // --- Roundtrip ---

    @Test
    void should_roundtrip_when_typicalInstant() {
        Instant original = Instant.parse("2026-04-05T12:30:00Z");

        String dbColumn = converter.convertToDatabaseColumn(original);
        Instant restored = converter.convertToEntityAttribute(dbColumn);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void should_roundtripWithNanos_when_nanoPrecisionInstant() {
        Instant original = Instant.parse("2026-04-05T12:30:00.123456789Z");

        String dbColumn = converter.convertToDatabaseColumn(original);
        Instant restored = converter.convertToEntityAttribute(dbColumn);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void should_roundtripNull_when_nullInput() {
        String dbColumn = converter.convertToDatabaseColumn(null);
        Instant result = converter.convertToEntityAttribute(dbColumn);

        assertThat(dbColumn).isNull();
        assertThat(result).isNull();
    }

    // --- Edge cases ---

    @Test
    void should_parseInstantWithMillis_when_dbDataHasMilliPrecision() {
        Instant result = converter.convertToEntityAttribute("2026-04-05T12:30:00.123Z");

        assertThat(result).isEqualTo(Instant.parse("2026-04-05T12:30:00.123Z"));
    }

    @Test
    void should_parseMinInstant_when_dbDataIsMinValue() {
        Instant min = Instant.MIN;

        String dbColumn = converter.convertToDatabaseColumn(min);
        Instant restored = converter.convertToEntityAttribute(dbColumn);

        assertThat(restored).isEqualTo(min);
    }

    @Test
    void should_parseMaxInstant_when_dbDataIsMaxValue() {
        Instant max = Instant.MAX;

        String dbColumn = converter.convertToDatabaseColumn(max);
        Instant restored = converter.convertToEntityAttribute(dbColumn);

        assertThat(restored).isEqualTo(max);
    }
}
