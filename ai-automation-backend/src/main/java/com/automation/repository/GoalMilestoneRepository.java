package com.automation.repository;

import com.automation.entity.GoalMilestone;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Meilensteine der Ziele (TODO 259f4f66); die zugeordneten TODOs zaehlt {@link TodoRepository}. */
@Repository
public interface GoalMilestoneRepository extends JpaRepository<GoalMilestone, String> {

    List<GoalMilestone> findByGoalId(String goalId);

    long countByGoalIdAndProjectId(String goalId, String projectId);

    /** Letzte Position im Projekt des Ziels (TODO c60bf914); null = noch kein Teilziel darin. */
    @Query("SELECT MAX(m.position) FROM GoalMilestone m WHERE m.goalId = :goalId AND m.projectId = :projectId")
    Integer maxPosition(@Param("goalId") String goalId, @Param("projectId") String projectId);

    @Query("SELECT m.id FROM GoalMilestone m WHERE m.goalId = :goalId AND m.projectId = :projectId")
    List<String> findIdsByGoalAndProject(@Param("goalId") String goalId, @Param("projectId") String projectId);

    /** Loescht die Teilziele eines Projekts im Ziel. Aufrufer muss @Transactional sein. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM GoalMilestone m WHERE m.goalId = :goalId AND m.projectId = :projectId")
    int deleteByGoalAndProject(@Param("goalId") String goalId, @Param("projectId") String projectId);

    /**
     * Noch nicht erledigte Meilensteine aktiver Ziele, die bis {@code until} faellig sind – die
     * Kandidaten fuer „Braucht dich“. Ob einer inzwischen erreicht ist, entscheiden die Zaehler.
     * Teilziele ohne Datum sind nie faellig (TODO c60bf914).
     */
    @Query("SELECT m FROM GoalMilestone m, Goal g WHERE g.id = m.goalId AND g.status = 'ACTIVE' "
         + "AND m.dueDate IS NOT NULL AND m.dueDate <= :until ORDER BY m.dueDate ASC")
    List<GoalMilestone> findDueForActiveGoals(@Param("until") Instant until, Pageable page);
}
