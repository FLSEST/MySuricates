package com.automation.dto.response;

import com.automation.entity.GoalWorkflow;

import java.time.Instant;

/**
 * Ein Workflow am Ziel oder an einem Arbeitspaket (TODO 52bc1e8c). {@code guard: true} ist der
 * Ziel-Waechter – ein eingeschalteter Waechter steht als erster Eintrag mit {@code id = "guard"} und
 * Rolle STATUS in der Liste; er wird ueber seinen Schalter verwaltet, nicht ueber diese Zuordnungen.
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
