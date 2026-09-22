package com.automation.dto.response;

import java.util.Map;

/** The current shipped text of a built-in onboarding profile ({@code GET /onboarding-profiles/{id}/shipped}). */
public record ShippedOnboardingProfileResponse(
        int version,
        /** Markdown per language. */
        Map<String, String> content
) {}
