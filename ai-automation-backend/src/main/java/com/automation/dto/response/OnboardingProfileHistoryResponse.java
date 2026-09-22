package com.automation.dto.response;

import java.time.Instant;
import java.util.Map;

public record OnboardingProfileHistoryResponse(
        String id,
        int version,
        Map<String, String> content,
        String changedBy,
        Instant changedAt
) {}
