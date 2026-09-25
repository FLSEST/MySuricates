package com.automation.dto.projection;

import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;

/**
 * Ein bekannter Befund eines Audits fuer die Kontextvariable {@code audit_findings} (TODO 3643beac):
 * Identitaet, Stand und Einordnung, aber ohne Beschreibung, Beleg, Tags und Beweis-Snapshot – die
 * braucht der Prompt nicht, und jede dieser TEXT-Spalten kann Kilobytes lang sein.
 */
public record AuditFindingContextRow(
        String id,
        AuditFindingStatus status,
        AuditSeverity severity,
        String ruleKey,
        String subject,
        String title,
        String dispositionNote
) {}
