package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/** Rolle einer Workflow-Zuordnung aendern (TODO 52bc1e8c): {@code WORKER} oder {@code STATUS}. */
public record UpdateGoalWorkflowRequest(@NotBlank String role) {}
