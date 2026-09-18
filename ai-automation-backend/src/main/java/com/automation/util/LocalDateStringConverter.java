package com.automation.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.LocalDate;

/**
 * Stores a {@link LocalDate} as an ISO string, the same way {@link InstantStringConverter} handles
 * instants. The community SQLite dialect cannot read a mapped {@code LocalDate} back ("Error
 * parsing date"), and ISO text sorts chronologically, so range queries keep working.
 *
 * <p>{@code autoApply = false} deliberately: this is the first {@code LocalDate} in the model, and
 * applying it to every future one silently is a decision the next author should make knowingly.
 */
@Converter(autoApply = false)
public class LocalDateStringConverter implements AttributeConverter<LocalDate, String> {

    @Override
    public String convertToDatabaseColumn(LocalDate attribute) {
        return attribute != null ? attribute.toString() : null;
    }

    @Override
    public LocalDate convertToEntityAttribute(String dbData) {
        return dbData != null && !dbData.isEmpty() ? LocalDate.parse(dbData) : null;
    }
}
