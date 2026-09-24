package com.automation.dto.projection;

import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;

/**
 * Anzahl Findings je Audit, Status und Schwere – eine GROUP-BY-Zeile statt einer Findings-Abfrage
 * je Audit mit allen Text-Spalten (TODO 3d7e3987).
 */
public record AuditFindingCountRow(
        String projectId,
        String auditKey,
        AuditFindingStatus status,
        AuditSeverity severity,
        long count
) {}
