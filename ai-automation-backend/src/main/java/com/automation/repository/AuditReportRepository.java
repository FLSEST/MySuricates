package com.automation.repository;

import com.automation.dto.projection.AuditReportSummaryRow;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuditReportRepository extends JpaRepository<AuditReport, String> {
    boolean existsByWorkflowRunId(String workflowRunId);
    Optional<AuditReport> findTopByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
            String projectId, String auditKey, AuditIngestionStatus ingestionStatus);
    Optional<AuditReport> findTopByProjectIdAndAuditKeyOrderByGeneratedAtDesc(String projectId, String auditKey);
    Page<AuditReport> findByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
            String projectId, String auditKey, AuditIngestionStatus ingestionStatus, Pageable pageable);
    List<AuditReport> findByProjectIdOrderByGeneratedAtDesc(String projectId);

    /**
     * Timestamps of the valid reports of an audit before {@code before}, newest first — only the column, no
     * Markdown. Basis of the rule "triaged, but not seen for N runs" (TODO b2e3da6a).
     */
    @Query("SELECT r.generatedAt FROM AuditReport r WHERE r.projectId = :projectId AND r.auditKey = :auditKey "
            + "AND r.ingestionStatus = com.automation.entity.AuditIngestionStatus.VALID AND r.generatedAt < :before "
            + "ORDER BY r.generatedAt DESC")
    List<Instant> findValidGeneratedAtBefore(@Param("projectId") String projectId, @Param("auditKey") String auditKey,
                                             @Param("before") Instant before, Pageable pageable);

    String SUMMARY_ROW = "SELECT new com.automation.dto.projection.AuditReportSummaryRow(r.id, r.projectId, "
            + "r.workflowId, r.workflowRunId, r.auditKey, r.category, r.title, r.gateStatus, r.gateDescription, "
            + "r.provider, r.model, r.workflowVersion, r.ingestionStatus, r.ingestionError, r.generatedAt) ";

    /** All reports without Markdown, newest first – for the audit overview (TODO 3d7e3987). */
    @Query(SUMMARY_ROW + "FROM AuditReport r ORDER BY r.generatedAt DESC")
    List<AuditReportSummaryRow> findAllSummaryRows();

    /**
     * The history of an audit, valid and invalid attempts, newest first – without Markdown
     * (TODO cd50d011): the history shows only headers, the shown report fetches its text individually.
     */
    @Query(value = SUMMARY_ROW + "FROM AuditReport r WHERE r.projectId = :projectId AND r.auditKey = :auditKey "
            + "ORDER BY r.generatedAt DESC",
            countQuery = "SELECT COUNT(r) FROM AuditReport r WHERE r.projectId = :projectId AND r.auditKey = :auditKey")
    Page<AuditReportSummaryRow> findSummaryRows(@Param("projectId") String projectId,
                                                @Param("auditKey") String auditKey, Pageable pageable);
}
