package com.automation.service.integration;

import java.util.List;
import java.util.Map;

/**
 * Describes a single action that an integration provider supports.
 */
public record IntegrationAction(
        String name,
        String description,
        Map<String, Object> inputSchema,
        List<String> requiredScopes
) {
    /** Constructor for actions without specific scope requirements. */
    public IntegrationAction(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, List.of());
    }
}
