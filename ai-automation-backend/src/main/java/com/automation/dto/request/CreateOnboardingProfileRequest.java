package com.automation.dto.request;

import com.automation.entity.OnboardingProfileType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

public record CreateOnboardingProfileRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9-]+$") String key,
        @NotNull Map<String, String> name,
        Map<String, String> description,
        @NotBlank String content,
        @NotNull OnboardingProfileType type
) {}
