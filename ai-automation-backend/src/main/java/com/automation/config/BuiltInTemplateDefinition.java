package com.automation.config;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Deserialization target for built-in template JSON files.
 * Decoupled from the JPA entity to avoid Jackson/JPA conflicts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuiltInTemplateDefinition(
        String id,
        String icon,
        Map<String, String> name,
        Map<String, String> description,
        String category,
        int sortOrder,
        String version,
        String model,
        String instructions,
        List<BlockDefinition> blocks
) {}
