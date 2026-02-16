package com.automation.util;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;

@Converter(autoApply = true)
public class InstantStringConverter implements AttributeConverter<Instant, String> {

    @Override
    public String convertToDatabaseColumn(Instant attribute) {
        return attribute != null ? attribute.toString() : null;
    }

    @Override
    public Instant convertToEntityAttribute(String dbData) {
        return dbData != null && !dbData.isEmpty() ? Instant.parse(dbData) : null;
    }
}
