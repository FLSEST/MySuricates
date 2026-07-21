package com.automation.repository;

import com.automation.entity.AuditFindingOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditFindingOccurrenceRepository extends JpaRepository<AuditFindingOccurrence, String> {
    boolean existsByFindingIdAndReportId(String findingId, String reportId);
    List<AuditFindingOccurrence> findByReportId(String reportId);
}
