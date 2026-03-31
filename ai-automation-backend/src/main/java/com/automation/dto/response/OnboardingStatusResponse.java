package com.automation.dto.response;

import java.util.List;

public record OnboardingStatusResponse(
        boolean enabled,
        String format,
        List<String> profileIds,
        boolean fileExists,
        String filePath
) {}
