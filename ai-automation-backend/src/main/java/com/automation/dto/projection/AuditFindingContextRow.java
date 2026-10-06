package com.automation.dto.projection;

import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;

/**
 * A known finding of an audit for the context variable {@code audit_findings} (TODO 3643beac):
 * identity, state and classification, but without description, evidence, tags and proof snapshot – the
 * prompt does not need them, and each of these TEXT columns can be kilobytes long.
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
