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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {

    List<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(String workflowId);

    List<WorkflowRun> findByStatus(RunStatus status);

    @Query("SELECT wr FROM WorkflowRun wr LEFT JOIN FETCH wr.workflow")
    List<WorkflowRun> findAllWithWorkflow();

    long countByWorkflowId(String workflowId);

    /**
     * Run-Counts aller Workflows in EINER GROUP-BY-Query (statt {@code countByWorkflowId} pro
     * Workflow). Für die /workflows-Liste — vermeidet N+1 (~285 Einzel-Counts). Nutzt
     * {@code idx_workflow_runs_workflow_id} (V10). Workflows ohne Runs fehlen im Ergebnis
     * (→ Mapper nutzt {@code getOrDefault(id, 0L)}).
     */
    @Query("SELECT wr.workflow.id, COUNT(wr) FROM WorkflowRun wr WHERE wr.workflow IS NOT NULL GROUP BY wr.workflow.id")
    List<Object[]> countRunsGroupedByWorkflow();

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
     *
     * <p>Der Container des Projekts kommt mit: {@code Project.container} ist eine nullable
     * {@code @OneToOne(mappedBy)}, die Hibernate sonst je Ergebniszeile mit einem eigenen
     * {@code select containers} auflöst — 60 bei 60 Runs (TODO 13d1e2a2).
     */
    @Query("SELECT wr FROM WorkflowRun wr "
         + "JOIN FETCH wr.workflow w "
         + "LEFT JOIN FETCH w.project p "
         + "LEFT JOIN FETCH p.container "
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
     * (das Frontend zeigt sie). Eager-Fetch, weil getDashboard ohne Transaktion läuft (OSIV aus);
     * den Container des Projekts aus demselben Grund wie bei {@link #findDashboardWindow(Instant)}.
     */
    @Query("SELECT DISTINCT wr FROM WorkflowRun wr "
         + "JOIN FETCH wr.workflow w "
         + "LEFT JOIN FETCH w.project p "
         + "LEFT JOIN FETCH p.container "
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

    /** {@link #findTopWorkflows()} restricted to the workflows of one project (dashboard project filter). */
    @Query("SELECT wr.workflow.id, wr.workflow.name, COUNT(wr) " +
           "FROM WorkflowRun wr " +
           "WHERE wr.workflow.project.id = :projectId " +
           "GROUP BY wr.workflow.id, wr.workflow.name " +
           "ORDER BY COUNT(wr) DESC " +
           "LIMIT 5")
    List<Object[]> findTopWorkflowsByProject(@Param("projectId") String projectId);

    /** Runs of one project's workflows — the dashboard overview under a project filter. */
    @Query("SELECT COUNT(wr) FROM WorkflowRun wr WHERE wr.workflow.project.id = :projectId")
    long countByProject(@Param("projectId") String projectId);

    @Query("SELECT COUNT(wr) FROM WorkflowRun wr WHERE wr.workflow.project.id = :projectId AND wr.status = :status")
    long countByProjectAndStatus(@Param("projectId") String projectId, @Param("status") RunStatus status);

    @Query("SELECT COUNT(wr) FROM WorkflowRun wr WHERE wr.workflow.project.id = :projectId AND wr.startedAt > :since")
    long countByProjectAndStartedAtAfter(@Param("projectId") String projectId, @Param("since") Instant since);

    /**
     * Lauf-Id, Workflow-Id und der beim Start festgehaltene Workflow-Name je Lauf — ohne Schritte und
     * ohne Join auf den Workflow, in einer Abfrage. Für die Liste wartender Freigaben, die jede Seite pollt.
     */
    @Query("SELECT wr.id, wr.workflow.id, wr.workflowName FROM WorkflowRun wr WHERE wr.id IN :ids")
    List<Object[]> findWorkflowRefsByRunIds(@Param("ids") Collection<String> ids);

    /** Status, Zeiten und Workflow je Lauf in einer Abfrage, ohne Schritte (TODO c6ea8f5c). */
    @Query("SELECT new com.automation.dto.projection.RunStateRow(wr.id, wr.workflow.id, wr.workflowName, "
         + "wr.status, wr.startedAt, wr.completedAt) FROM WorkflowRun wr WHERE wr.id IN :ids")
    List<com.automation.dto.projection.RunStateRow> findRunStates(@Param("ids") Collection<String> ids);

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

    /**
     * Workflows whose MOST RECENT run is a failure — the "currently broken" set for the
     * Workflow-Debug page (one row per workflow, deduplicated). The correlated subquery picks
     * each workflow's latest run by {@code startedAt}; the outer {@code WHERE} keeps only those
     * whose latest run is in {@code :statuses} (FAILED / COMPLETED_WITH_ERRORS).
     *
     * <p>Modeled on {@link #findDashboardWindow}: {@code JOIN FETCH} workflow + project but
     * NOT steps — pool-size=1 means we must never load {@code StepResult} TEXT blobs in an
     * aggregation. The inner {@code JOIN FETCH} on workflow also excludes soft-deleted
     * workflows ({@code @SQLRestriction("deleted_at IS NULL")}), so a deleted workflow never
     * shows up as "broken".
     */
    @Query("SELECT wr FROM WorkflowRun wr "
         + "JOIN FETCH wr.workflow w "
         + "LEFT JOIN FETCH w.project "
         + "WHERE wr.status IN :statuses "
         + "AND wr.startedAt = (SELECT MAX(wr2.startedAt) FROM WorkflowRun wr2 WHERE wr2.workflow.id = wr.workflow.id) "
         + "ORDER BY wr.startedAt DESC")
    List<WorkflowRun> findBrokenWorkflows(@Param("statuses") List<RunStatus> statuses);

    long countByWorkflowIdAndStatusAndCompletedAtAfter(String workflowId, RunStatus status, Instant since);

    boolean existsByWorkflowIdAndStatus(String workflowId, RunStatus status);

    /** Whether the workflow has a run in one of these states — the scheduler's SKIP check (RUNNING, PAUSED). */
    boolean existsByWorkflowIdAndStatusIn(String workflowId, Collection<RunStatus> statuses);

    Optional<WorkflowRun> findTopByWorkflowIdAndStatusOrderByStartedAtDesc(String workflowId, RunStatus status);

    @Query("SELECT r FROM WorkflowRun r LEFT JOIN FETCH r.steps WHERE r.id = :id")
    Optional<WorkflowRun> findByIdWithSteps(@Param("id") String id);

    List<WorkflowRun> findTop5ByWorkflowIdOrderByStartedAtDesc(String workflowId);

    List<WorkflowRun> findByTriggeredByScheduleIdOrderByStartedAtDesc(String scheduleId);

    /**
     * Status und Startzeit aller Runs, die von einem der genannten Zeitplaene ausgeloest wurden —
     * absteigend, damit der erste Treffer je Zeitplan der juengste ist.
     *
     * <p>Ersetzt eine Abfrage je Zeitplan im {@code ScheduleMapper}: Der las fuer jeden Zeitplan
     * dessen komplette Run-Liste und verwarf alles ausser dem ersten Status. Bei 30 Zeitplaenen
     * waren das 30 Abfragen und 30 vollstaendig materialisierte Run-Listen, nur um 30 Enum-Werte
     * zu bekommen (TODO 172a6a67).
     *
     * <p>Projektion statt Entities: Es werden drei Spalten gelesen, keine Run-Objekte gebaut.
     */
    @Query("SELECT r.triggeredByScheduleId, r.status, r.startedAt FROM WorkflowRun r "
           + "WHERE r.triggeredByScheduleId IN :scheduleIds "
           + "ORDER BY r.startedAt DESC")
    List<Object[]> findStatusesByScheduleIds(@Param("scheduleIds") Collection<String> scheduleIds);

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

    /**
     * Finished runs (given terminal statuses) completed before the cutoff, oldest first, that no
     * audit report references — the retention candidates. Bounded by the pageable so the nightly
     * job deletes in small batches instead of holding the single SQLite connection for minutes.
     */
    @Query("SELECT wr.id FROM WorkflowRun wr WHERE wr.status IN :statuses AND wr.completedAt < :cutoff " +
           "AND NOT EXISTS (SELECT 1 FROM AuditReport ar WHERE ar.workflowRunId = wr.id) ORDER BY wr.completedAt ASC")
    List<String> findTerminalRunIdsOlderThan(@Param("statuses") java.util.Collection<RunStatus> statuses,
                                             @Param("cutoff") Instant cutoff, Pageable pageable);

    /**
     * Neuester Run je Workflow für die Workflow-Widgets des Dashboards — schlanke Projektion
     * {@code [workflowId, runId, status, startedAt, completedAt, durationMs, metadata]}.
     *
     * <p>Bewusst KEINE Steps: {@code StepResult.input/output/context_delta} sind die großen
     * TEXT-Spalten (volle CLI-Ausgaben); bei pool-size=1 hält ein solcher Fetch die einzige
     * SQLite-Connection sekundenlang. Der Vertrag des Ergebnis-Widgets steht deshalb schon beim
     * Run-Ende in {@code metadata} (RunCompletionHandler), genau wie {@code gate_status}.
     *
     * <p>Die Unterabfrage wählt je Workflow den Run mit dem größten {@code startedAt} — dasselbe
     * Muster wie {@link #findBrokenWorkflows}. Bei gleichem Zeitstempel können zwei Zeilen
     * zurückkommen; die Sortierung macht die Auswahl deterministisch, der Aufrufer nimmt die erste.
     */
    @Query("SELECT wr.workflow.id, wr.id, wr.status, wr.startedAt, wr.completedAt, "
         + "wr.durationMs, wr.metadata FROM WorkflowRun wr "
         + "WHERE wr.workflow.id IN :workflowIds "
         + "AND wr.startedAt = (SELECT MAX(wr2.startedAt) FROM WorkflowRun wr2 WHERE wr2.workflow.id = wr.workflow.id) "
         + "ORDER BY wr.startedAt DESC, wr.id DESC")
    List<Object[]> findLatestRunPerWorkflow(@Param("workflowIds") Collection<String> workflowIds);
}
