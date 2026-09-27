package com.automation.repository;

import com.automation.entity.GoalWorkPackage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Arbeitspakete der Ziele (TODO c60bf914, bis V92 „Meilensteine“); die zugeordneten TODOs zaehlt
 * {@link TodoRepository}.
 */
@Repository
public interface GoalWorkPackageRepository extends JpaRepository<GoalWorkPackage, String> {

    List<GoalWorkPackage> findByGoalId(String goalId);

    long countByGoalIdAndProjectId(String goalId, String projectId);

    /** Letzte Position im Projekt des Ziels; null = noch kein Arbeitspaket darin. */
    @Query("SELECT MAX(p.position) FROM GoalWorkPackage p WHERE p.goalId = :goalId AND p.projectId = :projectId")
    Integer maxPosition(@Param("goalId") String goalId, @Param("projectId") String projectId);

    @Query("SELECT p.id FROM GoalWorkPackage p WHERE p.goalId = :goalId AND p.projectId = :projectId")
    List<String> findIdsByGoalAndProject(@Param("goalId") String goalId, @Param("projectId") String projectId);

    /** Die genannten Pakete, die zum Ziel gehoeren – was fehlt, ist fremd oder unbekannt. */
    @Query("SELECT p.id FROM GoalWorkPackage p WHERE p.goalId = :goalId AND p.id IN :ids")
    List<String> findIdsInGoal(@Param("goalId") String goalId, @Param("ids") Collection<String> ids);

    /**
     * Loescht die Arbeitspakete eines Projekts im Ziel; ihre Meilenstein-Verknuepfungen fallen per
     * Kaskade weg. Aufrufer muss @Transactional sein und die TODOs vorher loesen.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM GoalWorkPackage p WHERE p.goalId = :goalId AND p.projectId = :projectId")
    int deleteByGoalAndProject(@Param("goalId") String goalId, @Param("projectId") String projectId);

    /**
     * Ueberfaellige Arbeitspakete aktiver Ziele mit noch offener Arbeit – die Kandidaten fuer „Braucht
     * dich“: das Ende ist vorbei, und mindestens ein TODO darin ist noch aktiv. Ohne Zeitraum nie.
     */
    @Query("SELECT p FROM GoalWorkPackage p, Goal g WHERE g.id = p.goalId AND g.status = 'ACTIVE' "
         + "AND p.endDate IS NOT NULL AND p.endDate < :now AND EXISTS (SELECT 1 FROM Todo t "
         + "WHERE t.workPackageId = p.id AND t.status IN ('OPEN','IN_PROGRESS','BACKLOG')) ORDER BY p.endDate ASC")
    List<GoalWorkPackage> findOverdueForActiveGoals(@Param("now") Instant now, Pageable page);
}
