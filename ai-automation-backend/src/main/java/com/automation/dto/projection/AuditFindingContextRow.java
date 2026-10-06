package com.automation.dto.projection;

import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;

/**
 * A known finding of an audit for the context variable {@code audit_findings} (TODO 3643beac):
 * identity, state, evidence and classification, but without description, tags and proof snapshot – the
 * prompt does not need them, and each of these TEXT columns can be kilobytes long. The evidence is in (TODO
 * 5fc15f50): a finding carried on from an earlier run keeps its concrete line references instead of being
 * rewritten as "unchanged"; the block shortens it like every other field.
 */
public record AuditFindingContextRow(
        String id,
        AuditFindingStatus status,
        AuditSeverity severity,
        String ruleKey,
        String subject,
        String title,
        String evidence,
        String dispositionNote
) {}
