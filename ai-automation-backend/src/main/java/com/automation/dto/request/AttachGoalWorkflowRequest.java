package com.automation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Attach a workflow to the goal or to one of its work packages (TODO 52bc1e8c).
 *
 * @param workPackageId optional, work package of this goal; missing = on the whole goal
 * @param role          optional, {@code WORKER} (default) or {@code STATUS}
 */
public record AttachGoalWorkflowRequest(@NotBlank String workflowId, String workPackageId, String role) {}
