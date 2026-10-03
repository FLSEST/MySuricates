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
     * Zeitpunkte der gueltigen Reports eines Audits vor {@code before}, neueste zuerst — nur die Spalte, kein
     * Markdown. Grundlage der Regel „eingeordnet, aber N Laeufe nicht mehr gesehen“ (TODO b2e3da6a).
     */
    @Query("SELECT r.generatedAt FROM AuditReport r WHERE r.projectId = :projectId AND r.auditKey = :auditKey "
            + "AND r.ingestionStatus = com.automation.entity.AuditIngestionStatus.VALID AND r.generatedAt < :before "
            + "ORDER BY r.generatedAt DESC")
    List<Instant> findValidGeneratedAtBefore(@Param("projectId") String projectId, @Param("auditKey") String auditKey,
                                             @Param("before") Instant before, Pageable pageable);

    String SUMMARY_ROW = "SELECT new com.automation.dto.projection.AuditReportSummaryRow(r.id, r.projectId, "
            + "r.workflowId, r.workflowRunId, r.auditKey, r.category, r.title, r.gateStatus, r.gateDescription, "
            + "r.provider, r.model, r.workflowVersion, r.ingestionStatus, r.ingestionError, r.generatedAt) ";

    /** Alle Reports ohne Markdown, neueste zuerst – fuer die Audit-Uebersicht (TODO 3d7e3987). */
    @Query(SUMMARY_ROW + "FROM AuditReport r ORDER BY r.generatedAt DESC")
    List<AuditReportSummaryRow> findAllSummaryRows();

    /**
     * Die Historie eines Audits, gueltige und ungueltige Versuche, neueste zuerst – ohne Markdown
     * (TODO cd50d011): Die Historie zeigt nur Koepfe, den Text holt der angezeigte Bericht einzeln.
     */
    @Query(value = SUMMARY_ROW + "FROM AuditReport r WHERE r.projectId = :projectId AND r.auditKey = :auditKey "
            + "ORDER BY r.generatedAt DESC",
            countQuery = "SELECT COUNT(r) FROM AuditReport r WHERE r.projectId = :projectId AND r.auditKey = :auditKey")
    Page<AuditReportSummaryRow> findSummaryRows(@Param("projectId") String projectId,
                                                @Param("auditKey") String auditKey, Pageable pageable);
}
