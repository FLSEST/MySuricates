package com.automation.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * Deserialisierungsziel fuer die Built-in-Skill-JSONs unter
 * {@code classpath:skills/builtin/<id>.json}. Vom JPA-Entity entkoppelt, damit
 * Jackson und Hibernate sich nicht ins Gehege kommen (Vorbild
 * {@link BuiltInTemplateDefinition}).
 *
 * <p>Der Prompt-Text steht bewusst nicht in der JSON, sondern byte-genau in der
 * Schwesterdatei {@code <id>.md}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuiltInSkillDefinition(
        String id,
        /** Pflichtfeld — {@code Integer}, damit ein fehlendes Feld nicht still als 0 durchgeht. */
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
