package com.automation.service.integration;

import java.util.Map;

/**
 * Describes a single action that an integration provider supports.
 */
public record IntegrationAction(
        String name,
        String description,
        Map<String, Object> inputSchema
) {}
