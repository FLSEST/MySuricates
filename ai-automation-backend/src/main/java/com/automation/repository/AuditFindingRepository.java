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

    /** Projection {@code [projectId, auditKey]} — does not load the TEXT blobs of all findings. */
    @Query("select distinct f.projectId, f.auditKey from AuditFinding f")
    List<Object[]> findDistinctScopes();

    /** Findings per audit, status and severity in one query – for the audit overview (TODO 3d7e3987). */
    @Query("SELECT new com.automation.dto.projection.AuditFindingCountRow(f.projectId, f.auditKey, f.status, "
            + "f.severity, COUNT(f)) FROM AuditFinding f GROUP BY f.projectId, f.auditKey, f.status, f.severity")
    List<AuditFindingCountRow> countByScopeStatusAndSeverity();

    /**
     * The known findings of an audit for {@code audit_findings} (TODO 3643beac) – without the TEXT blobs
     * (description, evidence, tags, evidence snapshot). Findings with an unknown {@code ruleKey} are filtered by
     * {@code AuditFindingsContext} against the catalog, not by this query: the {@code unknownRuleKey} marker
     * is only reset when reported again and would otherwise hide a finding whose key is now
     * in the catalog (TODO 1cf996df).
     */
    @Query("SELECT new com.automation.dto.projection.AuditFindingContextRow(f.id, f.status, f.severity, f.ruleKey, "
            + "f.subject, f.title, f.evidence, f.dispositionNote) FROM AuditFinding f WHERE f.projectId = :projectId "
            + "AND f.auditKey = :auditKey AND f.status IN :statuses")
    List<AuditFindingContextRow> findContextRows(@Param("projectId") String projectId,
                                                 @Param("auditKey") String auditKey,
                                                 @Param("statuses") Collection<AuditFindingStatus> statuses);
}
