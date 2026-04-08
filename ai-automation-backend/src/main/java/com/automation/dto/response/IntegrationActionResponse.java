package com.automation.dto.response;

import java.util.Map;

public record IntegrationActionResponse(
        String name,
        String description,
        Map<String, Object> inputSchema,
        boolean available
) {
    public IntegrationActionResponse(String name, String description, Map<String, Object> inputSchema) {
        this(name, description, inputSchema, true);
    }
}
