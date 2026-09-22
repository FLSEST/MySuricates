package com.automation.dto.request;

import java.util.List;

public record OnboardingConfigRequest(
        String format,
        List<String> profileIds,
        /** Language of the written text, {@code de} or {@code en}; unset keeps the project's current one. */
        String language
) {}
