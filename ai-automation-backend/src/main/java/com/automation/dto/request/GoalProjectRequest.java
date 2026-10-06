package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Assign a project to the goal ({@code POST /goals/{id}/projects}, TODO c60bf914). */
public record GoalProjectRequest(@NotBlank String projectId) {}
