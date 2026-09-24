package com.automation.dto.projection;

import com.automation.entity.Todo;

import java.time.Instant;

/**
 * Ein TODO fuer die Relationskarte eines Ziels (TODO c6ea8f5c): Reihenfolge, Herkunft
 * (Quelle, erzeugender Lauf, Referenz), Bearbeitung (Fix-Workflow, Trigger-Abgleich) und Ergebnis
 * (letzter Fix-Lauf) – ohne die Beschreibung.
 */
public record GoalRelationRow(
        String id,
        String title,
        Todo.Status status,
        Todo.Priority priority,
        String projectId,
        String tags,
        Integer goalRank,
        Instant createdAt,
        Todo.Source source,
        String sourceAgent,
        String workflowRunId,
        String referenceType,
        String referenceId,
        String linkedWorkflowId,
        String lastFixRunId,
        int fixAttempts,
        Todo.ResolutionState resolutionState
) {}
