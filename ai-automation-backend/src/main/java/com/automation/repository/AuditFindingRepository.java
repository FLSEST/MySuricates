package com.automation.repository;

import com.automation.dto.projection.AuditFindingCountRow;
import com.automation.entity.AuditFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

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
}
