package com.automation.dto.projection;

import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.GateStatus;

import java.time.Instant;

/**
 * Ein Audit-Report ohne {@code markdown_content} (TODO 3d7e3987) – genau die Felder, die die
 * Audit-Uebersicht fuer ihre Zusammenfassung braucht. {@code GET /audits} lud vorher jeden Report
 * samt Markdown, nur um Status und Zeitpunkte zu lesen.
 */
public record AuditReportSummaryRow(
        String id,
        String projectId,
        String workflowId,
        String workflowRunId,
        String auditKey,
        String category,
        String title,
        GateStatus gateStatus,
        String gateDescription,
        AuditIngestionStatus ingestionStatus,
        String ingestionError,
        Instant generatedAt
) {}
