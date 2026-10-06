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
 * Work packages of the goals (TODO c60bf914, until V92 "Meilensteine"); the assigned TODOs are counted by
 * {@link TodoRepository}.
 */
@Repository
public interface GoalWorkPackageRepository extends JpaRepository<GoalWorkPackage, String> {

    List<GoalWorkPackage> findByGoalId(String goalId);

    long countByGoalIdAndProjectId(String goalId, String projectId);

    /** Last position in the goal's project; null = no work package in it yet. */
    @Query("SELECT MAX(p.position) FROM GoalWorkPackage p WHERE p.goalId = :goalId AND p.projectId = :projectId")
    Integer maxPosition(@Param("goalId") String goalId, @Param("projectId") String projectId);

    @Query("SELECT p.id FROM GoalWorkPackage p WHERE p.goalId = :goalId AND p.projectId = :projectId")
    List<String> findIdsByGoalAndProject(@Param("goalId") String goalId, @Param("projectId") String projectId);

    /** The named packages that belong to the goal – what is missing is foreign or unknown. */
    @Query("SELECT p.id FROM GoalWorkPackage p WHERE p.goalId = :goalId AND p.id IN :ids")
    List<String> findIdsInGoal(@Param("goalId") String goalId, @Param("ids") Collection<String> ids);

    /**
     * Deletes the work packages of a project in the goal; their milestone links disappear via
     * cascade. Caller must be @Transactional and detach the TODOs beforehand.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM GoalWorkPackage p WHERE p.goalId = :goalId AND p.projectId = :projectId")
    int deleteByGoalAndProject(@Param("goalId") String goalId, @Param("projectId") String projectId);

    /**
     * Overdue work packages of active goals with work still open – the candidates for "Braucht
     * dich": the end has passed, and at least one TODO in it is still active. Never without a period.
     */
    @Query("SELECT p FROM GoalWorkPackage p, Goal g WHERE g.id = p.goalId AND g.status = 'ACTIVE' "
         + "AND p.endDate IS NOT NULL AND p.endDate < :now AND EXISTS (SELECT 1 FROM Todo t "
         + "WHERE t.workPackageId = p.id AND t.status IN ('OPEN','IN_PROGRESS','BACKLOG')) ORDER BY p.endDate ASC")
    List<GoalWorkPackage> findOverdueForActiveGoals(@Param("now") Instant now, Pageable page);
}
