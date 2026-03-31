package com.automation.dto.request;

import java.util.List;

public record OnboardingConfigRequest(
        String format,
        List<String> profileIds
) {}
