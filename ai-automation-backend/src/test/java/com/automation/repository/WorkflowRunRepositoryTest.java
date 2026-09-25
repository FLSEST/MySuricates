package com.automation.repository;

import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.dto.projection.RunOriginRow;
import com.automation.metrics.SqlStatementCounter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class WorkflowRunRepositoryTest {

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ContainerRepository containerRepository;

    @Autowired
    private EntityManager entityManager;

    private Workflow workflowA;
    private Workflow workflowB;
    private Project project;
    private Project projectB;

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        project = projectRepository.save(Project.builder()
                .name("test-project-" + nano)
                .description("Test Project")
                .type(ProjectType.CODE)
                .path("/tmp/test-project-" + nano)
                .status(ProjectStatus.STOPPED)
                .build());

        workflowA = workflowRepository.save(Workflow.builder()
                .name("wf-a-" + nano)
                .description("Workflow A")
                .project(project)
                .build());

        projectB = projectRepository.save(Project.builder()
                .name("test-project-b-" + nano)
                .description("Test Project B")
                .type(ProjectType.CODE)
                .path("/tmp/test-project-b-" + nano)
                .status(ProjectStatus.STOPPED)
                .build());

        workflowB = workflowRepository.save(Workflow.builder()
                .name("wf-b-" + nano)
                .description("Workflow B")
                .project(projectB)
                .build());
    }

    private WorkflowRun run(Workflow workflow, RunStatus status, Instant startedAt) {
        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .workflowName(workflow.getName())
                .status(status)
                .startedAt(startedAt)
                .build();
        return runRepository.save(run);
    }

    private WorkflowRun run(Workflow workflow, RunStatus status, Instant startedAt, Instant completedAt) {
        WorkflowRun r = run(workflow, status, startedAt);
        r.setCompletedAt(completedAt);
        return runRepository.save(r);
    }

    /**
     * Vom Kontext liest {@code findOriginColumns} nur den Eltern-Ausloeser, als Text (TODO a3b89355) — auch
     * wenn die erste Zeile keinen hat. Fuer einen Ausdruck ohne deklarierten Typ nimmt sqlite-jdbc den Typ
     * des Werts der aktuellen Zeile; ein NULL dort meldet NUMERIC. Die Zeilen kommen nach Id sortiert.
     */
    @Test
    void findOriginColumns_readsTheParentSourceAsText_evenWhenTheFirstRowHasNone() {
        WorkflowRun withParent = originRun(Map.of(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE"));
        WorkflowRun withoutParent = originRun(Map.of());
        for (int i = 0; i < 64 && withoutParent.getId().compareTo(withParent.getId()) > 0; i++) {
            withoutParent = originRun(Map.of());
        }
        assertThat(withoutParent.getId()).as("a run without parent sorts first").isLessThan(withParent.getId());
        entityManager.flush();
        entityManager.clear();

        List<Object[]> columns = runRepository.findOriginColumns(List.of(withParent.getId(), withoutParent.getId()));
        List<RunOriginRow> rows = runRepository.findOriginRows(List.of(withParent.getId(), withoutParent.getId()));

        assertThat(columns).extracting(row -> row[0]).containsExactly(withoutParent.getId(), withParent.getId());
        assertThat(rows).extracting(RunOriginRow::parentTriggerSource).containsExactly(null, "SCHEDULE");
        assertThat(rows).extracting(RunOriginRow::triggerSource).containsOnly(TriggerSource.CHAIN);
    }

    private WorkflowRun originRun(Map<String, Object> context) {
        return runRepository.save(WorkflowRun.builder().workflow(workflowA).workflowName(workflowA.getName())
                .status(RunStatus.COMPLETED).triggerSource(TriggerSource.CHAIN).inputContext(new HashMap<>(context)).build());
    }

    @Test
    void should_find_runs_by_workflow_id_ordered_by_started_at_desc() {
        Instant now = Instant.now();
        run(workflowA, RunStatus.COMPLETED, now.minusSeconds(30));
        run(workflowA, RunStatus.RUNNING, now);
        run(workflowB, RunStatus.COMPLETED, now);

        List<WorkflowRun> result = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowA.getId());

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getStartedAt()).isAfterOrEqualTo(result.get(1).getStartedAt());
        assertThat(result).allSatisfy(r -> assertThat(r.getWorkflow().getId()).isEqualTo(workflowA.getId()));
    }

    @Test
    void should_find_runs_by_status() {
        run(workflowA, RunStatus.RUNNING, Instant.now());
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowB, RunStatus.RUNNING, Instant.now());

        List<WorkflowRun> running = runRepository.findByStatus(RunStatus.RUNNING);

        assertThat(running).hasSizeGreaterThanOrEqualTo(2);
        assertThat(running).allMatch(r -> r.getStatus() == RunStatus.RUNNING);
    }

    @Test
    void should_count_runs_by_workflow_id() {
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowA, RunStatus.FAILED, Instant.now());
        run(workflowB, RunStatus.COMPLETED, Instant.now());

        long count = runRepository.countByWorkflowId(workflowA.getId());

        assertThat(count).isEqualTo(2);
    }

    @Test
    void countRunsGroupedByWorkflow_matchesPerWorkflowCounts() {
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowA, RunStatus.FAILED, Instant.now());
        run(workflowB, RunStatus.COMPLETED, Instant.now());

        Map<String, Long> grouped = runRepository.countRunsGroupedByWorkflow().stream()
                .collect(java.util.stream.Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));

        assertThat(grouped.get(workflowA.getId())).isEqualTo(2L);
        assertThat(grouped.get(workflowB.getId())).isEqualTo(1L);
        // Same value as the per-workflow count it replaces in the /workflows list:
        assertThat(grouped.get(workflowA.getId()))
                .isEqualTo(runRepository.countByWorkflowId(workflowA.getId()));
    }

    @Test
    void should_count_runs_by_status() {
        long before = runRepository.countByStatus(RunStatus.FAILED);
        run(workflowA, RunStatus.FAILED, Instant.now());
        run(workflowA, RunStatus.FAILED, Instant.now());
        run(workflowA, RunStatus.COMPLETED, Instant.now());

        long after = runRepository.countByStatus(RunStatus.FAILED);

        assertThat(after - before).isEqualTo(2);
    }

    @Test
    void should_count_runs_started_after_given_instant() {
        Instant cutoff = Instant.now().minusSeconds(60);
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(30));
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(120));

        long count = runRepository.countByStartedAtAfter(cutoff);

        assertThat(count).isGreaterThanOrEqualTo(2);
    }

    @Test
    void should_find_runs_by_status_with_pagination() {
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(10));
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(20));
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(30));

        Page<WorkflowRun> page = runRepository.findByStatusOrderByStartedAtDesc(RunStatus.COMPLETED, PageRequest.of(0, 2));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(3);
        assertThat(page.getContent().get(0).getStartedAt()).isAfterOrEqualTo(page.getContent().get(1).getStartedAt());
    }

    @Test
    void should_find_runs_by_project_id() {
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowA, RunStatus.RUNNING, Instant.now());
        run(workflowB, RunStatus.COMPLETED, Instant.now());

        List<WorkflowRun> result = runRepository.findByProjectIdOrderByStartedAtDesc(project.getId());

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(r -> assertThat(r.getWorkflow().getId()).isEqualTo(workflowA.getId()));
    }

    @Test
    void should_find_runs_by_status_and_project_id_with_pagination() {
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowA, RunStatus.FAILED, Instant.now());
        run(workflowB, RunStatus.COMPLETED, Instant.now());

        Page<WorkflowRun> page = runRepository.findByStatusAndProjectIdOrderByStartedAtDesc(
                RunStatus.COMPLETED, project.getId(), PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void should_find_top10_runs_ordered_by_started_at_desc() {
        for (int i = 0; i < 12; i++) {
            run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(i));
        }

        List<WorkflowRun> top10 = runRepository.findTop10ByOrderByStartedAtDesc();

        assertThat(top10).hasSize(10);
        for (int i = 0; i < top10.size() - 1; i++) {
            assertThat(top10.get(i).getStartedAt()).isAfterOrEqualTo(top10.get(i + 1).getStartedAt());
        }
    }

    @Test
    void should_find_top_workflows_by_run_count() {
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowB, RunStatus.COMPLETED, Instant.now());

        List<Object[]> top = runRepository.findTopWorkflows();

        assertThat(top).isNotEmpty();
        assertThat(top.size()).isLessThanOrEqualTo(5);
    }

    @Test
    void should_find_runs_started_between_two_instants() {
        Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant to = Instant.now().plus(1, ChronoUnit.DAYS);
        run(workflowA, RunStatus.COMPLETED, Instant.now());

        List<WorkflowRun> result = runRepository.findByStartedAtBetween(from, to);

        assertThat(result).isNotEmpty();
    }

    @Test
    void should_count_runs_by_status_and_started_after() {
        Instant cutoff = Instant.now().minusSeconds(60);
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(120));
        run(workflowA, RunStatus.FAILED, Instant.now());

        long count = runRepository.countByStatusAndStartedAtAfter(RunStatus.COMPLETED, cutoff);

        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void should_find_runs_by_workflow_id_with_pagination() {
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(10));
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(20));
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(30));

        Page<WorkflowRun> page = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowA.getId(), PageRequest.of(0, 2));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    void should_find_distinct_workflow_ids_with_failed_runs() {
        run(workflowA, RunStatus.FAILED, Instant.now());
        run(workflowA, RunStatus.FAILED, Instant.now());
        run(workflowB, RunStatus.COMPLETED, Instant.now());

        List<String> ids = runRepository.findDistinctWorkflowIdsWithFailedRuns();

        assertThat(ids).contains(workflowA.getId());
        assertThat(ids).doesNotContain(workflowB.getId());
    }

    @Test
    void should_count_completed_runs_by_workflow_after_instant() {
        Instant cutoff = Instant.now().minusSeconds(60);
        WorkflowRun a = run(workflowA, RunStatus.COMPLETED, Instant.now());
        a.setCompletedAt(Instant.now());
        runRepository.save(a);

        WorkflowRun b = run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(120));
        b.setCompletedAt(Instant.now().minusSeconds(120));
        runRepository.save(b);

        long count = runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter(
                workflowA.getId(), RunStatus.COMPLETED, cutoff);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void should_check_existence_by_workflow_id_and_status() {
        run(workflowA, RunStatus.RUNNING, Instant.now());

        assertThat(runRepository.existsByWorkflowIdAndStatus(workflowA.getId(), RunStatus.RUNNING)).isTrue();
        assertThat(runRepository.existsByWorkflowIdAndStatus(workflowA.getId(), RunStatus.FAILED)).isFalse();
    }

    /** The scheduler's SKIP check: a PAUSED run still occupies the workflow (TODO ade3fef8). */
    @Test
    void should_check_existence_by_workflow_id_and_any_of_several_statuses() {
        run(workflowA, RunStatus.PAUSED, Instant.now());
        run(workflowB, RunStatus.COMPLETED, Instant.now());
        var busy = java.util.EnumSet.of(RunStatus.RUNNING, RunStatus.PAUSED);

        assertThat(runRepository.existsByWorkflowIdAndStatusIn(workflowA.getId(), busy)).isTrue();
        assertThat(runRepository.existsByWorkflowIdAndStatusIn(workflowB.getId(), busy)).isFalse();
    }

    @Test
    void should_find_top_run_by_workflow_id_and_status() {
        run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(60));
        WorkflowRun newest = run(workflowA, RunStatus.COMPLETED, Instant.now());

        Optional<WorkflowRun> found = runRepository.findTopByWorkflowIdAndStatusOrderByStartedAtDesc(
                workflowA.getId(), RunStatus.COMPLETED);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(newest.getId());
    }

    @Test
    void should_return_empty_when_finding_top_run_with_no_matches() {
        Optional<WorkflowRun> found = runRepository.findTopByWorkflowIdAndStatusOrderByStartedAtDesc(
                workflowA.getId(), RunStatus.FAILED);

        assertThat(found).isEmpty();
    }

    @Test
    void should_find_top5_runs_by_workflow_id() {
        for (int i = 0; i < 7; i++) {
            run(workflowA, RunStatus.COMPLETED, Instant.now().minusSeconds(i));
        }

        List<WorkflowRun> top5 = runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc(workflowA.getId());

        assertThat(top5).hasSize(5);
    }

    @Test
    void should_find_runs_by_triggered_by_schedule_id() {
        WorkflowRun scheduled = run(workflowA, RunStatus.COMPLETED, Instant.now());
        scheduled.setTriggeredByScheduleId("schedule-123");
        runRepository.save(scheduled);
        run(workflowA, RunStatus.COMPLETED, Instant.now());

        List<WorkflowRun> result = runRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc("schedule-123");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTriggeredByScheduleId()).isEqualTo("schedule-123");
    }

    @Test
    void should_find_filtered_with_all_null_params_returning_all() {
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowB, RunStatus.FAILED, Instant.now());

        Page<WorkflowRun> page = runRepository.findFiltered(null, null, null, null, PageRequest.of(0, 50));

        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void should_find_filtered_by_status() {
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowA, RunStatus.FAILED, Instant.now());

        Page<WorkflowRun> page = runRepository.findFiltered(RunStatus.FAILED, null, null, null, PageRequest.of(0, 50));

        assertThat(page.getContent()).allMatch(r -> r.getStatus() == RunStatus.FAILED);
    }

    @Test
    void should_find_filtered_by_workflow_id() {
        run(workflowA, RunStatus.COMPLETED, Instant.now());
        run(workflowB, RunStatus.COMPLETED, Instant.now());

        Page<WorkflowRun> page = runRepository.findFiltered(null, workflowA.getId(), null, null, PageRequest.of(0, 50));

        assertThat(page.getContent()).allMatch(r -> r.getWorkflow().getId().equals(workflowA.getId()));
    }

    @Test
    void should_find_all_with_workflow_eagerly_loaded() {
        run(workflowA, RunStatus.COMPLETED, Instant.now());

        List<WorkflowRun> result = runRepository.findAllWithWorkflow();

        assertThat(result).isNotEmpty();
        // Triggers no LazyInitializationException because of JOIN FETCH
        assertThat(result.get(0).getWorkflow().getName()).isNotNull();
    }

    /**
     * Regression: a WorkflowRun whose workflow was soft-deleted ({@code deleted_at} set,
     * hidden by {@code @SQLRestriction}) must not break the dashboard window query.
     * The inner JOIN FETCH excludes such runs instead of throwing FetchNotFoundException.
     */
    @Test
    void should_exclude_runs_of_soft_deleted_workflow_from_dashboard_window() {
        Instant now = Instant.now();
        run(workflowA, RunStatus.COMPLETED, now.minusSeconds(20));
        run(workflowB, RunStatus.COMPLETED, now.minusSeconds(10));

        workflowB.setDeletedAt(Instant.now());
        workflowRepository.flush();
        entityManager.clear();

        Instant since = now.minus(1, ChronoUnit.DAYS);
        List<WorkflowRun> window = runRepository.findDashboardWindow(since);

        assertThat(window).isNotEmpty();
        assertThat(window).allSatisfy(r ->
                assertThat(r.getWorkflow().getId()).isEqualTo(workflowA.getId()));
    }

    // --- Dashboard: der Container des Projekts kommt mit (TODO 13d1e2a2) ---

    /**
     * Das Dashboard-Fenster holt den Container des Projekts in derselben Abfrage.
     * {@code Project.container} ist eine nullable {@code @OneToOne(mappedBy)}: Ohne den Join loest
     * Hibernate ihn je Ergebniszeile mit einem eigenen {@code select containers} auf — 60 bei
     * 60 Runs im RequestCostRatchetTest.
     */
    @Test
    void should_fetch_the_project_container_along_with_the_dashboard_window() {
        List<String> seeded = runsWithAndWithoutContainer();

        Counted<List<WorkflowRun>> window = counted(
                () -> runRepository.findDashboardWindow(Instant.now().minus(1, ChronoUnit.DAYS)));

        assertThat(window.statements())
                .as("one query for the window, not one select containers per run")
                .isEqualTo(1);
        assertContainersResolved(window.result(), seeded);
    }

    /**
     * Die zehn letzten Runs des Dashboards ebenso — und hier zaehlt es doppelt: Mit den Schritten
     * im Join ist jede Schritt-Zeile eine Ergebniszeile, und {@code getDashboard} laeuft ohne
     * Transaktion, also mit leerem Persistenzkontext. Im RequestCostRatchetTest sieht man das nicht,
     * weil der Request dort den Persistenzkontext der Testtransaktion mitbenutzt; ohne Transaktion
     * kostete das Dashboard mit 12 Runs zu je 3 Schritten 52 statt 10 Statements.
     */
    @Test
    void should_fetch_the_project_container_along_with_the_recent_dashboard_runs() {
        List<String> seeded = runsWithAndWithoutContainer();

        Counted<List<WorkflowRun>> recent = counted(() -> runRepository.findByIdInWithStepsAndWorkflow(seeded));

        assertThat(recent.statements())
                .as("one query for the recent runs, not one select containers per step row")
                .isEqualTo(1);
        assertThat(recent.result()).hasSize(seeded.size())
                .allSatisfy(r -> assertThat(r.getSteps()).hasSize(2));
        assertContainersResolved(recent.result(), seeded);
    }

    /**
     * Je zwei Runs mit zwei Schritten fuer workflowA (Projekt mit Container) und workflowB (Projekt
     * ohne). Leert danach den Persistenzkontext, sonst faende die Abfrage die verwalteten Instanzen
     * vor und muesste nichts nachladen.
     */
    private List<String> runsWithAndWithoutContainer() {
        containerRepository.save(Container.builder()
                .project(project)
                .dockerContainerId("docker-run-list")
                .imageName("automation/test:latest")
                .status(ContainerStatus.RUNNING)
                .build());
        List<String> ids = new ArrayList<>();
        Instant now = Instant.now();
        for (Workflow workflow : List.of(workflowA, workflowB)) {
            for (int r = 0; r < 2; r++) {
                WorkflowRun run = WorkflowRun.builder()
                        .workflow(workflow)
                        .workflowName(workflow.getName())
                        .status(RunStatus.COMPLETED)
                        .startedAt(now.minusSeconds(60 + r))
                        .build();
                for (int s = 0; s < 2; s++) {
                    run.addStep(StepResult.builder()
                            .stepIndex(s)
                            .status(StepStatus.COMPLETED)
                            .startedAt(now)
                            .durationMs(5L)
                            .build());
                }
                ids.add(runRepository.save(run).getId());
            }
        }
        entityManager.flush();
        entityManager.clear();
        return ids;
    }

    /** Runs von workflowA tragen den Container ihres Projekts, Runs von workflowB keinen. */
    private void assertContainersResolved(List<WorkflowRun> runs, List<String> seeded) {
        List<WorkflowRun> ours = runs.stream().filter(r -> seeded.contains(r.getId())).toList();
        assertThat(ours).hasSize(seeded.size());
        assertThat(ours).filteredOn(r -> r.getWorkflow().getId().equals(workflowA.getId()))
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.getWorkflow().getProject().getContainer().getDockerContainerId())
                        .isEqualTo("docker-run-list"));
        assertThat(ours).filteredOn(r -> r.getWorkflow().getId().equals(workflowB.getId()))
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.getWorkflow().getProject().getContainer()).isNull());
    }

    private record Counted<T>(T result, int statements) {}

    /** Fuehrt die Abfrage aus und zaehlt die SQL-Statements, die sie absetzt. */
    private static <T> Counted<T> counted(Supplier<T> query) {
        SqlStatementCounter.start();
        try {
            T result = query.get();
            return new Counted<>(result, SqlStatementCounter.count());
        } finally {
            SqlStatementCounter.stop();
        }
    }

    @Test
    void should_find_by_id_with_steps() {
        WorkflowRun saved = run(workflowA, RunStatus.COMPLETED, Instant.now());

        Optional<WorkflowRun> result = runRepository.findByIdWithSteps(saved.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }

    private static final List<RunStatus> BROKEN =
            List.of(RunStatus.FAILED, RunStatus.COMPLETED_WITH_ERRORS);

    @Test
    void findBrokenWorkflows_returnsOnlyWorkflowsWhoseLatestRunFailed() {
        Instant now = Instant.now();
        // workflowA: an older COMPLETED run, but the LATEST run FAILED → broken
        run(workflowA, RunStatus.COMPLETED, now.minusSeconds(60));
        WorkflowRun latestA = run(workflowA, RunStatus.FAILED, now.minusSeconds(10));
        // workflowB: failed earlier, but the LATEST run COMPLETED → healthy, must NOT appear
        run(workflowB, RunStatus.FAILED, now.minusSeconds(60));
        run(workflowB, RunStatus.COMPLETED, now.minusSeconds(10));

        List<WorkflowRun> broken = runRepository.findBrokenWorkflows(BROKEN);

        List<String> workflowIds = broken.stream().map(r -> r.getWorkflow().getId()).toList();
        assertThat(workflowIds).contains(workflowA.getId());
        assertThat(workflowIds).doesNotContain(workflowB.getId());
        WorkflowRun aRun = broken.stream()
                .filter(r -> r.getWorkflow().getId().equals(workflowA.getId()))
                .findFirst().orElseThrow();
        assertThat(aRun.getId()).isEqualTo(latestA.getId());
        assertThat(aRun.getStatus()).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void findBrokenWorkflows_includesCompletedWithErrors() {
        run(workflowA, RunStatus.COMPLETED_WITH_ERRORS, Instant.now());

        List<WorkflowRun> broken = runRepository.findBrokenWorkflows(BROKEN);

        assertThat(broken.stream().map(r -> r.getWorkflow().getId())).contains(workflowA.getId());
    }

    @Test
    void findBrokenWorkflows_excludesWorkflowWhoseLatestRunIsStillRunning() {
        Instant now = Instant.now();
        // Failed earlier, then re-run and currently RUNNING → not "broken" anymore.
        run(workflowA, RunStatus.FAILED, now.minusSeconds(30));
        run(workflowA, RunStatus.RUNNING, now);

        List<WorkflowRun> broken = runRepository.findBrokenWorkflows(BROKEN);

        assertThat(broken.stream().map(r -> r.getWorkflow().getId())).doesNotContain(workflowA.getId());
    }

    @Test
    void findWorkflowRefsByRunIds_returnsIdWorkflowAndRecordedName_onlyForAskedRuns() {
        WorkflowRun waiting = run(workflowA, RunStatus.RUNNING, Instant.now());
        run(workflowB, RunStatus.RUNNING, Instant.now());
        entityManager.flush();
        entityManager.clear();

        List<Object[]> rows = runRepository.findWorkflowRefsByRunIds(List.of(waiting.getId(), "no-such-run"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly(waiting.getId(), workflowA.getId(), workflowA.getName());
    }
}
