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

    /** Reports, in denen ein Finding schon ein Vorkommen hat — nur die IDs, ohne die TEXT-Spalten (d2500abd). */
    @Query("SELECT o.reportId FROM AuditFindingOccurrence o WHERE o.findingId = :findingId")
    List<String> findReportIdsByFindingId(@Param("findingId") String findingId);

    /** Findings, die in einem Report schon ein Vorkommen haben — nur die IDs (3deaee34). */
    @Query("SELECT o.findingId FROM AuditFindingOccurrence o WHERE o.reportId = :reportId")
    List<String> findFindingIdsByReportId(@Param("reportId") String reportId);

    /**
     * Findings je Report als {@code [reportId, count]} in EINER GROUP-BY-Abfrage (TODO a3b89355) —
     * statt je Report alle Vorkommen zu laden und zu zaehlen. Reports ohne Findings fehlen im Ergebnis.
     */
    @Query("SELECT o.reportId, COUNT(o) FROM AuditFindingOccurrence o WHERE o.reportId IN :reportIds GROUP BY o.reportId")
    List<Object[]> countByReportIds(@Param("reportIds") Collection<String> reportIds);
}
