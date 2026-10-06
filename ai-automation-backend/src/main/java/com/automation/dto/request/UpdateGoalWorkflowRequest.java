package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Change the role of a workflow assignment (TODO 52bc1e8c): {@code WORKER} or {@code STATUS}. */
public record UpdateGoalWorkflowRequest(@NotBlank String role) {}
