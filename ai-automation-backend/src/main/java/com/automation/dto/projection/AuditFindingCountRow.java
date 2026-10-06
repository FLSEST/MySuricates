package com.automation.dto.projection;

import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;

/**
 * Number of findings per audit, status and severity – one GROUP-BY row instead of a findings query
 * per audit with all text columns (TODO 3d7e3987).
 */
public record AuditFindingCountRow(
        String projectId,
        String auditKey,
        AuditFindingStatus status,
        AuditSeverity severity,
        long count
) {}
