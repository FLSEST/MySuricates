package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Ein Projekt dem Ziel zuordnen ({@code POST /goals/{id}/projects}, TODO c60bf914). */
public record GoalProjectRequest(@NotBlank String projectId) {}
