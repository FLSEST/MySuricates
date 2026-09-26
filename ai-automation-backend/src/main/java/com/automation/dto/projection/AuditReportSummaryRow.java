package com.automation.dto.projection;

import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.GateStatus;

import java.time.Instant;

/**
 * Der Kopf eines Audit-Reports, ohne {@code markdown_content}. {@code GET /audits} lud vorher jeden
 * Report samt Markdown, nur um Status und Zeitpunkte zu lesen (TODO 3d7e3987); die Historie
 * {@code GET /audits/{key}/reports} liest seit TODO cd50d011 dieselben Koepfe – den Text braucht nur
 * der angezeigte Bericht, und den liefert {@code GET /audit-reports/{id}}.
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
        String provider,
        String model,
        String workflowVersion,
        AuditIngestionStatus ingestionStatus,
        String ingestionError,
        Instant generatedAt
) {
    /** Der Kopf eines schon geladenen Reports. */
    public static AuditReportSummaryRow of(AuditReport report) {
        return new AuditReportSummaryRow(report.getId(), report.getProjectId(), report.getWorkflowId(),
                report.getWorkflowRunId(), report.getAuditKey(), report.getCategory(), report.getTitle(),
                report.getGateStatus(), report.getGateDescription(), report.getProvider(), report.getModel(),
                report.getWorkflowVersion(), report.getIngestionStatus(), report.getIngestionError(),
                report.getGeneratedAt());
    }
}
