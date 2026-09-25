package com.automation.repository;

import com.automation.dto.projection.AuditFindingContextRow;
import com.automation.dto.projection.AuditFindingCountRow;
import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuditFindingRepository extends JpaRepository<AuditFinding, String>, JpaSpecificationExecutor<AuditFinding> {
    Optional<AuditFinding> findByProjectIdAndAuditKeyAndFingerprint(String projectId, String auditKey, String fingerprint);
    List<AuditFinding> findByProjectIdAndAuditKey(String projectId, String auditKey);

    /** Projektion {@code [projectId, auditKey]} — laedt nicht die TEXT-Blobs aller Findings. */
    @Query("select distinct f.projectId, f.auditKey from AuditFinding f")
    List<Object[]> findDistinctScopes();

    /** Findings je Audit, Status und Schwere in einer Abfrage – fuer die Audit-Uebersicht (TODO 3d7e3987). */
    @Query("SELECT new com.automation.dto.projection.AuditFindingCountRow(f.projectId, f.auditKey, f.status, "
            + "f.severity, COUNT(f)) FROM AuditFinding f GROUP BY f.projectId, f.auditKey, f.status, f.severity")
    List<AuditFindingCountRow> countByScopeStatusAndSeverity();

    /**
     * Die bekannten Befunde eines Audits fuer {@code audit_findings} (TODO 3643beac) – ohne die TEXT-Blobs
     * (Beschreibung, Beleg, Tags, Beweis-Snapshot) und ohne Befunde mit als unbekannt markiertem {@code ruleKey}:
     * Fuehrte ein Prompt so einen Key weiter, verwuerfe die Ingestion den ganzen Report. Nie markierten Altbestand
     * filtert {@code AuditFindingsContext} gegen den Katalog.
     */
    @Query("SELECT new com.automation.dto.projection.AuditFindingContextRow(f.id, f.status, f.severity, f.ruleKey, "
            + "f.subject, f.title, f.dispositionNote) FROM AuditFinding f WHERE f.projectId = :projectId "
            + "AND f.auditKey = :auditKey AND f.status IN :statuses AND f.unknownRuleKey = false")
    List<AuditFindingContextRow> findContextRows(@Param("projectId") String projectId,
                                                 @Param("auditKey") String auditKey,
                                                 @Param("statuses") Collection<AuditFindingStatus> statuses);
}
