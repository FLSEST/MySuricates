package com.automation.dto.projection;

import com.automation.entity.GoalWorkflow;

import java.time.Instant;

/**
 * A workflow assignment of a goal with name and project of the workflow and the position of the
 * work package (and its project in the goal) for sorting – from one query (TODO 52bc1e8c).
 */
public record GoalWorkflowRow(
        String id,
        String goalId,
        String workPackageId,
        String workflowId,
        String workflowName,
        String workflowProjectId,
        GoalWorkflow.Role role,
        Instant createdAt,
        Integer workPackagePosition,
        Integer projectPosition
) {}
