package com.automation.dto.projection;

import com.automation.entity.RunStatus;

import java.time.Instant;

/**
 * Status eines Laufs ohne Schritte, Kontext und Metadaten (TODO c6ea8f5c) – fuer Karten, die nur
 * wissen muessen, ob ein Lauf lief, gelang oder scheiterte, und zu welchem Workflow er gehoert.
 */
public record RunStateRow(
        String id,
        String workflowId,
        String workflowName,
        RunStatus status,
        Instant startedAt,
        Instant completedAt
) {}
