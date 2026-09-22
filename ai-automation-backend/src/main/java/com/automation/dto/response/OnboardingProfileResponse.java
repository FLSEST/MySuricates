package com.automation.dto.response;

import com.automation.entity.OnboardingProfileType;

import java.time.Instant;
import java.util.Map;

public record OnboardingProfileResponse(
        String id,
        String key,
        Map<String, String> name,
        Map<String, String> description,
        Map<String, String> content,
        OnboardingProfileType type,
        boolean builtIn,
        int sortOrder,
        int version,
        int historyCount,
        /** The text was changed by hand (built-in profiles). */
        boolean customized,
        /** A newer shipped text exists than the one this profile is based on. */
        boolean updateAvailable,
        Instant createdAt,
        Instant updatedAt
) {}
