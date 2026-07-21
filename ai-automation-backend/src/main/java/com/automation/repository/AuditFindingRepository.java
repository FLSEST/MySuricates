package com.automation.repository;

import com.automation.entity.AuditFinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface AuditFindingRepository extends JpaRepository<AuditFinding, String>, JpaSpecificationExecutor<AuditFinding> {
    Optional<AuditFinding> findByProjectIdAndAuditKeyAndFingerprint(String projectId, String auditKey, String fingerprint);
    List<AuditFinding> findByProjectIdAndAuditKey(String projectId, String auditKey);
}
