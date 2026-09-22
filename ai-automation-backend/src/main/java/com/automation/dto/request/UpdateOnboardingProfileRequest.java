package com.automation.dto.request;

import java.util.Map;

public record UpdateOnboardingProfileRequest(
        Map<String, String> name,
        Map<String, String> description,
        /** Markdown per language; only the languages sent are replaced. */
        Map<String, String> content
) {}
