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
     * Dashboard-Window mit eager-geladenem workflow + project (NICHT steps).
     * <p>Steps werden bewusst NICHT mehr mitgefetcht: ein {@code LEFT JOIN FETCH wr.steps}
     * lädt jede StepResult-Zeile inkl. der großen TEXT-Spalten input/output/context_delta
     * (volle CLI-Outputs) als kartesisches Produkt — bei pool-size=1 hält das die einzige
     * SQLite-Connection mehrere Sekunden und blockiert alles andere. Die Aggregation in
     * {@code LearningService.getDashboard} braucht pro Step nur status + metadata; die liefert
     * {@link #findStepMetadataForDashboard(Instant)} als schlanke Projektion.
     *
     * <p>Inner JOIN FETCH auf {@code workflow}: Runs soft-gelöschter Workflows
     * ({@code @SQLRestriction("deleted_at IS NULL")}) werden bewusst ausgeschlossen.
     * Ein LEFT JOIN FETCH würde hier {@code FetchNotFoundException} werfen, weil der
     * nicht-nullable FK gesetzt ist, die Zeile aber durch die Restriction unsichtbar.
     */
    @Query("SELECT wr FROM WorkflowRun wr "
         + "JOIN FETCH wr.workflow w "
         + "LEFT JOIN FETCH w.project "
         + "WHERE wr.startedAt > :since "
         + "ORDER BY wr.startedAt DESC")
    List<WorkflowRun> findDashboardWindow(@Param("since") Instant since);

    /**
     * Schlanke Per-Step-Projektion für die Dashboard-Aggregation: nur (runId, status, metadata),
     * NICHT die großen input/output/context_delta-TEXT-Spalten. Sortiert nach Run + stepIndex,
     * damit die Provider-Erkennung (erster Nicht-Integration-Step gewinnt) deterministisch bleibt.
     */
    @Query("SELECT sr.workflowRun.id, sr.status, sr.metadata FROM StepResult sr "
         + "WHERE sr.workflowRun.startedAt > :since "
         + "ORDER BY sr.workflowRun.id, sr.stepIndex")
    List<Object[]> findStepMetadataForDashboard(@Param("since") Instant since);

    /**
     * Lädt vollständige Runs (inkl. steps + workflow + project) für die wenigen "recentRuns"
     * des Dashboards. Nur für ≤10 IDs gedacht — hier sind die großen TEXT-Spalten gewollt
     * (das Frontend zeigt sie). Eager-Fetch, weil getDashboard ohne Transaktion läuft (OSIV aus).
     */
    @Query("SELECT DISTINCT wr FROM WorkflowRun wr "
         + "JOIN FETCH wr.workflow w "
         + "LEFT JOIN FETCH w.project "
         + "LEFT JOIN FETCH wr.steps "
         + "WHERE wr.id IN :ids")
    List<WorkflowRun> findByIdInWithStepsAndWorkflow(@Param("ids") List<String> ids);

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
