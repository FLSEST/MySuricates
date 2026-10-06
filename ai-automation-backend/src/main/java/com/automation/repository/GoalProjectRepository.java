package com.automation.repository;

import com.automation.dto.projection.GoalProjectRow;
import com.automation.entity.GoalProject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * The explicitly assigned projects of the goals (TODO c60bf914). Reading goes through the
 * lean projection {@link GoalProjectRow} – one query per goal or per goal list.
 */
@Repository
public interface GoalProjectRepository extends JpaRepository<GoalProject, GoalProject.Key> {

    String ROW = "SELECT new com.automation.dto.projection.GoalProjectRow("
            + "gp.goalId, gp.projectId, p.name, p.archivedAt, gp.position) "
            + "FROM GoalProject gp JOIN Project p ON p.id = gp.projectId ";

    /** The projects of a goal in their order. */
    @Query(ROW + "WHERE gp.goalId = :goalId ORDER BY gp.position, gp.projectId")
    List<GoalProjectRow> findRows(@Param("goalId") String goalId);

    /** The projects of several goals in one query, per goal in its order. */
    @Query(ROW + "WHERE gp.goalId IN :goalIds ORDER BY gp.goalId, gp.position, gp.projectId")
    List<GoalProjectRow> findRowsForGoals(@Param("goalIds") Collection<String> goalIds);

    List<GoalProject> findByGoalIdOrderByPositionAscProjectIdAsc(String goalId);

    boolean existsByGoalIdAndProjectId(String goalId, String projectId);

    @Query("SELECT MAX(gp.position) FROM GoalProject gp WHERE gp.goalId = :goalId")
    Integer maxPosition(@Param("goalId") String goalId);

    /**
     * Attaches the project to the goal if it is not yet attached – at the end (position max + 1). One
     * statement, idempotent; returns 1 if it was newly added. Caller must be @Transactional.
     * Deliberately does not clear the persistence context: callers hold just-modified TODOs in it.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT OR IGNORE INTO goal_projects (goal_id, project_id, position) "
            + "SELECT :goalId, :projectId, COALESCE(MAX(position), -1) + 1 FROM goal_projects WHERE goal_id = :goalId",
            nativeQuery = true)
    int linkIfAbsent(@Param("goalId") String goalId, @Param("projectId") String projectId);

    /** Detaches the project from the goal. Caller must be @Transactional. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM GoalProject gp WHERE gp.goalId = :goalId AND gp.projectId = :projectId")
    int unlink(@Param("goalId") String goalId, @Param("projectId") String projectId);
}
