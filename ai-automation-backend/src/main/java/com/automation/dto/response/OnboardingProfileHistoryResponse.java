package com.automation.dto.response;

import java.time.Instant;

public record OnboardingProfileHistoryResponse(
        String id,
        int version,
        String content,
        String changedBy,
        Instant changedAt
) {}
