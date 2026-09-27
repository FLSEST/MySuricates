package com.automation.repository;

import com.automation.dto.projection.GoalMilestoneLinks;
import com.automation.dto.projection.GoalMilestoneSummaryRow;
import com.automation.entity.GoalMilestone;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Meilensteine der Ziele (TODO c60bf914) – gelesen mit ihren verknuepften Arbeitspaketen in EINER
 * Abfrage je Ziel ({@link #findWithLinks}) oder als Zusammenfassung mit Paket-Zaehlern fuer viele
 * Ziele auf einmal ({@link #findSummaries}).
 */
@Repository
public interface GoalMilestoneRepository extends JpaRepository<GoalMilestone, String> {

    /**
     * Zusammenfassung je Meilenstein in einer Abfrage: verknuepfte Pakete gesamt und davon
     * abgeschlossen (mindestens ein TODO ohne ARCHIVED, keins mehr offen) – als Unterabfragen,
     * damit weder Pakete noch TODOs geladen werden.
     */
    String SUMMARY = "SELECT new com.automation.dto.projection.GoalMilestoneSummaryRow("
            + "m.id, m.goalId, m.title, m.dueDate, m.reachedAt, "
            + "(SELECT COUNT(l.workPackageId) FROM GoalMilestoneWorkPackage l WHERE l.milestoneId = m.id), "
            + "(SELECT COUNT(c.workPackageId) FROM GoalMilestoneWorkPackage c WHERE c.milestoneId = m.id "
            + "AND EXISTS (SELECT 1 FROM Todo t WHERE t.workPackageId = c.workPackageId AND t.status <> 'ARCHIVED') "
            + "AND NOT EXISTS (SELECT 1 FROM Todo o WHERE o.workPackageId = c.workPackageId "
            + "AND o.status IN ('OPEN','IN_PROGRESS','BACKLOG')))) ";

    /** Eine Zeile je (Meilenstein, verknuepftes Paket); ein Meilenstein ohne Pakete mit {@code null}. */
    @Query("SELECT m, l.workPackageId FROM GoalMilestone m LEFT JOIN GoalMilestoneWorkPackage l ON l.milestoneId = m.id "
         + "WHERE m.goalId = :goalId")
    List<Object[]> findLinkRows(@Param("goalId") String goalId);

    /** Die Meilensteine eines Ziels mit den IDs ihrer Pakete – eine Abfrage. */
    default List<GoalMilestoneLinks> findWithLinks(String goalId) {
        Map<String, GoalMilestone> milestones = new LinkedHashMap<>();
        Map<String, List<String>> links = new LinkedHashMap<>();
        for (Object[] row : findLinkRows(goalId)) {
            GoalMilestone milestone = (GoalMilestone) row[0];
            milestones.putIfAbsent(milestone.getId(), milestone);
            List<String> ids = links.computeIfAbsent(milestone.getId(), id -> new ArrayList<>());
            if (row[1] instanceof String workPackageId) {
                ids.add(workPackageId);
            }
        }
        return milestones.values().stream()
                .map(m -> new GoalMilestoneLinks(m, links.get(m.getId()).stream().sorted().toList()))
                .toList();
    }

    /** Zusammenfassungen fuer eine Zielliste – eine Abfrage fuer alle Ziele. */
    @Query(SUMMARY + "FROM GoalMilestone m WHERE m.goalId IN :goalIds")
    List<GoalMilestoneSummaryRow> findSummaries(@Param("goalIds") Collection<String> goalIds);

    /**
     * Meilensteine aktiver Ziele, die bis {@code until} faellig sind – die Kandidaten fuer „Braucht
     * dich“. Ob einer inzwischen erreicht ist, entscheiden die Zaehler und {@code reachedAt}.
     */
    @Query(SUMMARY + "FROM GoalMilestone m, Goal g WHERE g.id = m.goalId AND g.status = 'ACTIVE' "
         + "AND m.dueDate <= :until ORDER BY m.dueDate ASC")
    List<GoalMilestoneSummaryRow> findDueForActiveGoals(@Param("until") Instant until, Pageable page);
}
