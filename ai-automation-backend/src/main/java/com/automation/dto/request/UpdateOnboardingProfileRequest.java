package com.automation.dto.request;

import java.util.Map;

public record UpdateOnboardingProfileRequest(
        Map<String, String> name,
        Map<String, String> description,
        String content
) {}
