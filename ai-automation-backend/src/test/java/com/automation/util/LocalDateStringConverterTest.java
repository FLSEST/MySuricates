package com.automation.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDateStringConverterTest {

    private final LocalDateStringConverter converter = new LocalDateStringConverter();

    @Test
    void convertsToIsoTextAndBack() {
        LocalDate date = LocalDate.of(2024, 2, 29);

        String stored = converter.convertToDatabaseColumn(date);

        assertThat(stored).isEqualTo("2024-02-29");
        assertThat(converter.convertToEntityAttribute(stored)).isEqualTo(date);
    }

    @Test
    void nullAndEmptyDatabaseValuesStayNull() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("")).isNull();
    }

    @Test
    void malformedDatabaseValueIsRejectedInsteadOfSilentlyChangingTheDate() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute("29.02.2024"))
                .isInstanceOf(DateTimeParseException.class);
    }
}
