package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Einen Workflow ans Ziel oder an eines seiner Arbeitspakete haengen (TODO 52bc1e8c).
 *
 * @param workPackageId optional, Arbeitspaket dieses Ziels; fehlt = am ganzen Ziel
 * @param role          optional, {@code WORKER} (Standard) oder {@code STATUS}
 */
public record AttachGoalWorkflowRequest(@NotBlank String workflowId, String workPackageId, String role) {}
