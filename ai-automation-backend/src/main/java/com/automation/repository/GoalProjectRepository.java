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
 * Die ausdruecklich zugeordneten Projekte der Ziele (TODO c60bf914). Gelesen wird ueber die
 * schlanke Projektion {@link GoalProjectRow} – eine Abfrage je Ziel oder je Zielliste.
 */
@Repository
public interface GoalProjectRepository extends JpaRepository<GoalProject, GoalProject.Key> {

    String ROW = "SELECT new com.automation.dto.projection.GoalProjectRow("
            + "gp.goalId, gp.projectId, p.name, p.archivedAt, gp.position) "
            + "FROM GoalProject gp JOIN Project p ON p.id = gp.projectId ";

    /** Die Projekte eines Ziels in ihrer Reihenfolge. */
    @Query(ROW + "WHERE gp.goalId = :goalId ORDER BY gp.position, gp.projectId")
    List<GoalProjectRow> findRows(@Param("goalId") String goalId);

    /** Die Projekte mehrerer Ziele in einer Abfrage, je Ziel in seiner Reihenfolge. */
    @Query(ROW + "WHERE gp.goalId IN :goalIds ORDER BY gp.goalId, gp.position, gp.projectId")
    List<GoalProjectRow> findRowsForGoals(@Param("goalIds") Collection<String> goalIds);

    List<GoalProject> findByGoalIdOrderByPositionAscProjectIdAsc(String goalId);

    boolean existsByGoalIdAndProjectId(String goalId, String projectId);

    @Query("SELECT MAX(gp.position) FROM GoalProject gp WHERE gp.goalId = :goalId")
    Integer maxPosition(@Param("goalId") String goalId);

    /**
     * Haengt das Projekt ans Ziel, falls es noch nicht dran ist – ans Ende (Position max + 1). Ein
     * Statement, idempotent; gibt 1 zurueck, wenn es neu dazukam. Aufrufer muss @Transactional sein.
     * Leert den Persistenzkontext bewusst nicht: Aufrufer halten gerade veraenderte TODOs darin.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT OR IGNORE INTO goal_projects (goal_id, project_id, position) "
            + "SELECT :goalId, :projectId, COALESCE(MAX(position), -1) + 1 FROM goal_projects WHERE goal_id = :goalId",
            nativeQuery = true)
    int linkIfAbsent(@Param("goalId") String goalId, @Param("projectId") String projectId);

    /** Loest das Projekt vom Ziel. Aufrufer muss @Transactional sein. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM GoalProject gp WHERE gp.goalId = :goalId AND gp.projectId = :projectId")
    int unlink(@Param("goalId") String goalId, @Param("projectId") String projectId);
}
