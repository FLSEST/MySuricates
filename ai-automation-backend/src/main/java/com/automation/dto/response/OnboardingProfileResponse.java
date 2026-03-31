package com.automation.dto.response;

import com.automation.entity.OnboardingProfileType;

import java.time.Instant;
import java.util.Map;

public record OnboardingProfileResponse(
        String id,
        String key,
        Map<String, String> name,
        Map<String, String> description,
        String content,
        OnboardingProfileType type,
        boolean builtIn,
        int sortOrder,
        int version,
        int historyCount,
        Instant createdAt,
        Instant updatedAt
) {}
