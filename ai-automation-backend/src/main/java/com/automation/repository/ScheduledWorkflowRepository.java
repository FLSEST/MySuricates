package com.automation.repository;

import com.automation.dto.projection.GuardScheduleRow;
import com.automation.entity.ScheduledWorkflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledWorkflowRepository extends JpaRepository<ScheduledWorkflow, String> {

    List<ScheduledWorkflow> findByEnabledTrue();
    long countByEnabledTrue();

    @Query("SELECT s FROM ScheduledWorkflow s WHERE s.enabled = true AND s.nextRunAt <= :before")
    List<ScheduledWorkflow> findDueSchedules(@Param("before") Instant before);

    /**
     * Loads a schedule with its workflow's activatedSkills eagerly fetched via JOIN FETCH.
     * Use this in the scheduler executor to avoid LazyInitializationException when
     * WorkflowExecutor.execute() accesses activatedSkills outside a JPA session.
     */
    @Query("SELECT sw FROM ScheduledWorkflow sw " +
           "LEFT JOIN FETCH sw.workflow w " +
           "LEFT JOIN FETCH w.activatedSkills " +
           "WHERE sw.id = :id")
    Optional<ScheduledWorkflow> findByIdWithWorkflowSkills(@Param("id") String id);

    /**
     * All schedules together with their targets in one query — the list behind
     * {@code GET /api/v1/schedules}.
     *
     * <p>{@code findAll()} leaves the two {@code EAGER} associations
     * {@code workflow} and {@code chain} open and Hibernate then resolves them row by row:
     * for 30 schedules 30 additional queries, each of them on the only
     * SQLite connection (TODO c9ec9844). The join fetches them along.
     *
     * <p>{@code w.project} and {@code p.container} hang on the same chain: they are themselves
     * {@code EAGER} and would otherwise cost one more query per project. Fetched along, the
     * number of queries stays constant, independent of the number of schedules.
     *
     * <p>{@code LEFT}, not {@code INNER}: a schedule has either a workflow or a
     * chain, never both — and a schedule whose workflow was soft-deleted
     * ({@code Workflow} carries {@code @SQLRestriction("deleted_at IS NULL")}) stays visible with an empty
     * target instead of dragging down the whole list. Exactly this behavior is pinned by
     * {@code ScheduleSoftDeletedWorkflowIntegrationTest}.
     */
    @Query("SELECT s FROM ScheduledWorkflow s " +
           "LEFT JOIN FETCH s.workflow w " +
           "LEFT JOIN FETCH w.project p " +
           "LEFT JOIN FETCH p.container " +
           "LEFT JOIN FETCH s.chain")
    List<ScheduledWorkflow> findAllWithTargets();

    List<ScheduledWorkflow> findByWorkflowId(String workflowId);

    /**
     * Schedules of the goal guards (TODO 259f4f66) as lean rows – one query for the whole
     * goal list, without loading workflow, project and container.
     */
    @Query("SELECT new com.automation.dto.projection.GuardScheduleRow("
         + "s.workflow.id, s.id, s.enabled, s.nextRunAt, s.createdAt) "
         + "FROM ScheduledWorkflow s WHERE s.workflow.id IN :workflowIds")
    List<GuardScheduleRow> findGuardRows(@Param("workflowIds") Collection<String> workflowIds);

    List<ScheduledWorkflow> findByChainId(String chainId);

    /**
     * Active schedules whose next occurrence lies in the period, together with their targets — the occurrences
     * of the calendar ({@code GET /api/v1/schedules/calendar}) and the assistant section for the
     * next 24 hours.
     *
     * <p>Fetches workflow, project, container and chain along like {@link #findAllWithTargets()}: without
     * the join Hibernate resolves the {@code EAGER} associations row by row, 30 queries
     * for 30 occurrences in the RequestCostRatchetTest (TODO 168e3d7f). A schedule on a
     * soft-deleted workflow still appears with an empty target — see
     * {@code ScheduleSoftDeletedWorkflowIntegrationTest}.
     */
    @Query("SELECT s FROM ScheduledWorkflow s " +
           "LEFT JOIN FETCH s.workflow w " +
           "LEFT JOIN FETCH w.project p " +
           "LEFT JOIN FETCH p.container " +
           "LEFT JOIN FETCH s.chain " +
           "WHERE s.enabled = true AND s.nextRunAt BETWEEN :from AND :to")
    List<ScheduledWorkflow> findSchedulesInRange(@Param("from") Instant from, @Param("to") Instant to);
}
