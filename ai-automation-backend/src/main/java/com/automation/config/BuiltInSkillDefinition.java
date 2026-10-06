package com.automation.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Deserialization target for the built-in skill JSONs under
 * {@code classpath:skills/builtin/<id>.json}. Decoupled from the JPA entity so that
 * Jackson and Hibernate do not get in each other's way (modelled on
 * {@link BuiltInTemplateDefinition}).
 *
 * <p>The prompt text is deliberately not in the JSON but byte-exact in the
 * sibling file {@code <id>.md}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuiltInSkillDefinition(
        String id,
        /** Required field — {@code Integer}, so that a missing field does not silently pass as 0. */
        Integer sortOrder,
        String version,
        String name,
        String icon,
        String description,
        String category,
        List<CapabilityDefinition> capabilities
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CapabilityDefinition(
            String name,
            String description,
            String handlerClass,
            boolean async,
            boolean writes,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema
    ) {}
}
