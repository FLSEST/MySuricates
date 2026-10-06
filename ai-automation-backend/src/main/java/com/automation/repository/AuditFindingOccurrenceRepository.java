package com.automation.repository;

import com.automation.entity.AuditFindingOccurrence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AuditFindingOccurrenceRepository extends JpaRepository<AuditFindingOccurrence, String> {
    boolean existsByFindingIdAndReportId(String findingId, String reportId);
    List<AuditFindingOccurrence> findByFindingId(String findingId);

    /** Reports in which a finding already has an occurrence — only the IDs, without the TEXT columns (d2500abd). */
    @Query("SELECT o.reportId FROM AuditFindingOccurrence o WHERE o.findingId = :findingId")
    List<String> findReportIdsByFindingId(@Param("findingId") String findingId);

    /** Findings that already have an occurrence in a report — only the IDs (3deaee34). */
    @Query("SELECT o.findingId FROM AuditFindingOccurrence o WHERE o.reportId = :reportId")
    List<String> findFindingIdsByReportId(@Param("reportId") String reportId);

    /**
     * Findings per report as {@code [reportId, count]} in ONE GROUP-BY query (TODO a3b89355) —
     * instead of loading and counting all occurrences per report. Reports without findings are missing from the result.
     */
    @Query("SELECT o.reportId, COUNT(o) FROM AuditFindingOccurrence o WHERE o.reportId IN :reportIds GROUP BY o.reportId")
    List<Object[]> countByReportIds(@Param("reportIds") Collection<String> reportIds);
}
