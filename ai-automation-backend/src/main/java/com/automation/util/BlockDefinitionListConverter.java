package com.automation.util;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

@Converter
public class BlockDefinitionListConverter implements AttributeConverter<List<BlockDefinition>, String> {

    /**
     * Tolerant on reading: a field this version does not know lands in {@link BlockDefinition#unknownFields()}
     * (any-setter) and is written back, never fails the row — so a later return to this version does not break on
     * a field a newer one stored (d3aa0b28). FAIL_ON_UNKNOWN_PROPERTIES is off as well, for anything the any-setter
     * does not cover.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public String convertToDatabaseColumn(List<BlockDefinition> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize blocks to JSON", e);
        }
    }

    @Override
    public List<BlockDefinition> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(dbData, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON to blocks", e);
        }
    }
}
