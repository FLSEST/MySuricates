package com.automation.repository;

import com.automation.dto.projection.GoalWorkflowRow;
import com.automation.entity.GoalWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Workflows an Zielen und Arbeitspaketen (TODO 52bc1e8c). Gelesen wird in einer Abfrage mit Name und
 * Projekt des Workflows; weich geloeschte Workflows fallen dabei heraus, ihre Zuordnung bleibt stehen.
 */
@Repository
public interface GoalWorkflowRepository extends JpaRepository<GoalWorkflow, String> {

    @Query("SELECT new com.automation.dto.projection.GoalWorkflowRow(a.id, a.goalId, a.workPackageId, a.workflowId, "
         + "w.name, p.id, a.role, a.createdAt, wp.position, gp.position) "
         + "FROM GoalWorkflow a JOIN Workflow w ON w.id = a.workflowId LEFT JOIN w.project p "
         + "LEFT JOIN GoalWorkPackage wp ON wp.id = a.workPackageId "
         + "LEFT JOIN GoalProject gp ON gp.goalId = wp.goalId AND gp.projectId = wp.projectId "
         + "WHERE a.goalId = :goalId AND w.deletedAt IS NULL")
    List<GoalWorkflowRow> findRows(@Param("goalId") String goalId);

    /** Gibt es diese Zuordnung schon? {@code workPackageId} null = am ganzen Ziel. */
    @Query("SELECT COUNT(a) > 0 FROM GoalWorkflow a WHERE a.goalId = :goalId AND a.workflowId = :workflowId "
         + "AND ((:workPackageId IS NULL AND a.workPackageId IS NULL) OR a.workPackageId = :workPackageId)")
    boolean existsAttachment(@Param("goalId") String goalId, @Param("workPackageId") String workPackageId,
                             @Param("workflowId") String workflowId);
}
