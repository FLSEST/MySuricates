package com.automation.repository;

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
}
