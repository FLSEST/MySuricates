package com.automation.repository;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {

    List<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(String workflowId);

    List<WorkflowRun> findByStatus(RunStatus status);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow")
    List<WorkflowRun> findAllWithWorkflow();

    long countByWorkflowId(String workflowId);

    long countByStatus(RunStatus status);

    long countByStartedAtAfter(Instant since);

    List<WorkflowRun> findAllByOrderByStartedAtDesc();

    List<WorkflowRun> findByStatusOrderByStartedAtDesc(RunStatus status);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow w WHERE w.project.id = :projectId ORDER BY wr.startedAt DESC")
    List<WorkflowRun> findByProjectIdOrderByStartedAtDesc(@org.springframework.data.repository.query.Param("projectId") String projectId);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow w WHERE wr.status = :status AND w.project.id = :projectId ORDER BY wr.startedAt DESC")
    List<WorkflowRun> findByStatusAndProjectIdOrderByStartedAtDesc(@org.springframework.data.repository.query.Param("status") RunStatus status, @org.springframework.data.repository.query.Param("projectId") String projectId);

    List<WorkflowRun> findTop10ByOrderByStartedAtDesc();

    List<WorkflowRun> findByStartedAtAfterOrderByStartedAtDesc(Instant since);

    /**
     * Dashboard-Window mit eager-geladenem workflow, project und steps.
     * Eliminiert N+1 lazy loads beim Aggregieren in {@code LearningService.getDashboard}.
     * Mit pool-size=1 würden N+1-Queries die einzige SQLite-Connection >60s halten und
     * den HikariCP-Leak-Watchdog auslösen.
     *
     * <p>Inner JOIN FETCH auf {@code workflow}: Runs soft-gelöschter Workflows
     * ({@code @SQLRestriction("deleted_at IS NULL")}) werden bewusst ausgeschlossen.
     * Ein LEFT JOIN FETCH würde hier {@code FetchNotFoundException} werfen, weil der
     * nicht-nullable FK gesetzt ist, die Zeile aber durch die Restriction unsichtbar.
     */
    @Query("SELECT DISTINCT wr FROM WorkflowRun wr "
         + "JOIN FETCH wr.workflow w "
         + "LEFT JOIN FETCH w.project "
         + "LEFT JOIN FETCH wr.steps "
         + "WHERE wr.startedAt > :since "
         + "ORDER BY wr.startedAt DESC")
    List<WorkflowRun> findDashboardWindow(@Param("since") Instant since);

    /**
     * Top-10-Recent-Runs mit eager-geladenem workflow für das Dashboard-Recent-Runs-Panel.
     * Inner JOIN FETCH: Runs soft-gelöschter Workflows werden ausgeschlossen (vgl.
     * {@link #findDashboardWindow}) — liefert so bis zu 10 Runs mit lebendem Workflow.
     */
    @Query("SELECT wr FROM WorkflowRun wr "
         + "JOIN FETCH wr.workflow "
         + "ORDER BY wr.startedAt DESC "
         + "LIMIT 10")
    List<WorkflowRun> findTop10WithWorkflowByOrderByStartedAtDesc();

    @Query("SELECT wr.workflow.id, wr.workflow.name, COUNT(wr) " +
           "FROM WorkflowRun wr " +
           "WHERE wr.workflow IS NOT NULL " +
           "GROUP BY wr.workflow.id, wr.workflow.name " +
           "ORDER BY COUNT(wr) DESC " +
           "LIMIT 5")
    List<Object[]> findTopWorkflows();

    List<WorkflowRun> findByStartedAtBetween(Instant from, Instant to);

    long countByStatusAndStartedAtAfter(RunStatus status, Instant since);

    // Pageable variants
    Page<WorkflowRun> findByOrderByStartedAtDesc(Pageable pageable);

    Page<WorkflowRun> findByStatusOrderByStartedAtDesc(RunStatus status, Pageable pageable);

    Page<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(String workflowId, Pageable pageable);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow w WHERE w.project.id = :projectId ORDER BY wr.startedAt DESC")
    Page<WorkflowRun> findByProjectIdOrderByStartedAtDesc(@org.springframework.data.repository.query.Param("projectId") String projectId, Pageable pageable);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow w WHERE wr.status = :status AND w.project.id = :projectId ORDER BY wr.startedAt DESC")
    Page<WorkflowRun> findByStatusAndProjectIdOrderByStartedAtDesc(@org.springframework.data.repository.query.Param("status") RunStatus status, @org.springframework.data.repository.query.Param("projectId") String projectId, Pageable pageable);

    @Query("SELECT DISTINCT wr.workflow.id FROM WorkflowRun wr WHERE wr.status = 'FAILED' AND wr.workflow IS NOT NULL")
    List<String> findDistinctWorkflowIdsWithFailedRuns();

    long countByWorkflowIdAndStatusAndCompletedAtAfter(String workflowId, RunStatus status, Instant since);

    boolean existsByWorkflowIdAndStatus(String workflowId, RunStatus status);

    Optional<WorkflowRun> findTopByWorkflowIdAndStatusOrderByStartedAtDesc(String workflowId, RunStatus status);

    @Query("SELECT r FROM WorkflowRun r LEFT JOIN FETCH r.steps WHERE r.id = :id")
    Optional<WorkflowRun> findByIdWithSteps(@Param("id") String id);

    List<WorkflowRun> findTop5ByWorkflowIdOrderByStartedAtDesc(String workflowId);

    List<WorkflowRun> findByTriggeredByScheduleIdOrderByStartedAtDesc(String scheduleId);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN wr.workflow w " +
           "WHERE (:status IS NULL OR wr.status = :status) " +
           "AND (:workflowId IS NULL OR w.id = :workflowId) " +
           "AND (:projectId IS NULL OR w.project.id = :projectId) " +
           "AND (:search IS NULL OR LOWER(w.name) LIKE LOWER(CONCAT('%', :search, '%'))) " +
           "ORDER BY wr.startedAt DESC")
    Page<WorkflowRun> findFiltered(@Param("status") RunStatus status,
                                    @Param("workflowId") String workflowId,
                                    @Param("projectId") String projectId,
                                    @Param("search") String search,
                                    Pageable pageable);

    /**
     * INTERRUPTED runs with zero completed steps, completed before the given cutoff.
     * Safe to delete — no resume value and no learning value.
     */
    @Query("SELECT wr.id FROM WorkflowRun wr WHERE wr.status = 'INTERRUPTED' AND wr.completedAt < :cutoff " +
           "AND NOT EXISTS (SELECT 1 FROM StepResult sr WHERE sr.workflowRun = wr AND sr.status = 'COMPLETED')")
    List<String> findInterruptedRunIdsWithNoCompletedSteps(@Param("cutoff") Instant cutoff);

    /**
     * INTERRUPTED runs (regardless of steps) older than the given cutoff.
     * Retention period has elapsed — safe to delete.
     */
    @Query("SELECT wr.id FROM WorkflowRun wr WHERE wr.status = 'INTERRUPTED' AND wr.completedAt < :cutoff")
    List<String> findInterruptedRunIdsOlderThan(@Param("cutoff") Instant cutoff);
}
