package com.automation.dto.response;

import java.util.Map;

public record CapabilityResponse(
        String id,
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        boolean async
) {}
