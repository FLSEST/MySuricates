package com.automation.dto.request;

import java.util.List;

public record ExtractMemoryRequest(
        List<String> runIds,
        String provider
) {}
