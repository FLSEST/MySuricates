package com.automation.repository;

import com.automation.dto.projection.AuditReportSummaryRow;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AuditReportRepository extends JpaRepository<AuditReport, String> {
    boolean existsByWorkflowRunId(String workflowRunId);
    Optional<AuditReport> findTopByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
            String projectId, String auditKey, AuditIngestionStatus ingestionStatus);
    Optional<AuditReport> findTopByProjectIdAndAuditKeyOrderByGeneratedAtDesc(String projectId, String auditKey);
    Page<AuditReport> findByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
            String projectId, String auditKey, AuditIngestionStatus ingestionStatus, Pageable pageable);
    Page<AuditReport> findByProjectIdAndAuditKeyOrderByGeneratedAtDesc(
            String projectId, String auditKey, Pageable pageable);
    List<AuditReport> findByProjectIdOrderByGeneratedAtDesc(String projectId);

    /** Alle Reports ohne Markdown, neueste zuerst – fuer die Audit-Uebersicht (TODO 3d7e3987). */
    @Query("SELECT new com.automation.dto.projection.AuditReportSummaryRow(r.id, r.projectId, r.workflowId, "
            + "r.workflowRunId, r.auditKey, r.category, r.title, r.gateStatus, r.gateDescription, "
            + "r.ingestionStatus, r.ingestionError, r.generatedAt) FROM AuditReport r ORDER BY r.generatedAt DESC")
    List<AuditReportSummaryRow> findAllSummaryRows();
}
