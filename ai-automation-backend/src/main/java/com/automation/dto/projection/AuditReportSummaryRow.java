package com.automation.dto.projection;

import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.GateStatus;

import java.time.Instant;

/**
 * The head of an audit report, without {@code markdown_content}. {@code GET /audits} used to load every
 * report including Markdown, just to read status and timestamps (TODO 3d7e3987); the history
 * {@code GET /audits/{key}/reports} has read the same heads since TODO cd50d011 – only the displayed
 * report needs the text, and {@code GET /audit-reports/{id}} delivers it.
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
    /** The head of an already loaded report. */
    public static AuditReportSummaryRow of(AuditReport report) {
        return new AuditReportSummaryRow(report.getId(), report.getProjectId(), report.getWorkflowId(),
                report.getWorkflowRunId(), report.getAuditKey(), report.getCategory(), report.getTitle(),
                report.getGateStatus(), report.getGateDescription(), report.getProvider(), report.getModel(),
                report.getWorkflowVersion(), report.getIngestionStatus(), report.getIngestionError(),
                report.getGeneratedAt());
    }
}
