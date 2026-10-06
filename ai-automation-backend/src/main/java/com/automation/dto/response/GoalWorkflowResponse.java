package com.automation.dto.response;

import com.automation.entity.GoalWorkflow;

import java.time.Instant;

/**
 * A workflow on the goal or on a work package (TODO 52bc1e8c). {@code guard: true} is the
 * goal guard - an enabled guard is the first entry with {@code id = "guard"} and
 * role STATUS in the list; it is managed via its switch, not via these assignments.
 */
public record GoalWorkflowResponse(
        String id,
        String goalId,
        String workPackageId,
        String workflowId,
        String workflowName,
        String workflowProjectId,
        GoalWorkflow.Role role,
        boolean guard,
        Instant createdAt
) {}
