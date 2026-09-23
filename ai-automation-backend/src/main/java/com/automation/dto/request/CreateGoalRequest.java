package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Neues Ziel (TODO dc0566f8).
 *
 * @param targetDate optional, ISO-Datum ({@code 2026-10-15}) oder -Zeitpunkt
 */
public record CreateGoalRequest(
        @NotBlank String title,
        String description,
        String targetDate
) {}
