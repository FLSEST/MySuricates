package com.automation.repository;

import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditReportRepository extends JpaRepository<AuditReport, String> {
    boolean existsByWorkflowRunId(String workflowRunId);
    Optional<AuditReport> findTopByProjectIdAndAuditKeyAndIngestionStatusOrderByGeneratedAtDesc(
            String projectId, String auditKey, AuditIngestionStatus ingestionStatus);
    Page<AuditReport> findByProjectIdAndAuditKeyOrderByGeneratedAtDesc(
            String projectId, String auditKey, Pageable pageable);
    List<AuditReport> findByProjectIdOrderByGeneratedAtDesc(String projectId);
}
