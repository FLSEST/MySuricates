package com.automation.dto.projection;

import com.automation.entity.GoalWorkflow;

import java.time.Instant;

/**
 * Eine Workflow-Zuordnung eines Ziels mit Name und Projekt des Workflows und der Position des
 * Arbeitspakets (und seines Projekts im Ziel) fuer die Sortierung – aus einer Abfrage (TODO 52bc1e8c).
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
