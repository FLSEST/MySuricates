package com.automation.repository;

import com.automation.dto.projection.DashboardRunRow;
import com.automation.dto.projection.RunOverviewCounts;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.metrics.SqlStatementCounter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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

    /** The calendar: the newest runs of the period up to the limit, plus their total count (TODO 86777fb4). */
    @Test
    void should_find_the_newest_runs_started_between_two_instants_and_count_them_all() {
        Instant now = Instant.now();
        Instant from = now.minus(1, ChronoUnit.DAYS);
        Instant to = now.plus(1, ChronoUnit.DAYS);
        long before = runRepository.countByStartedAtBetween(from, to);
        WorkflowRun older = run(workflowA, RunStatus.COMPLETED, now.minusSeconds(20));
        WorkflowRun newest = run(workflowA, RunStatus.COMPLETED, now.minusSeconds(10));
        run(workflowA, RunStatus.COMPLETED, now.minus(2, ChronoUnit.DAYS));

        List<WorkflowRun> result = runRepository.findByStartedAtBetweenOrderByStartedAtDesc(from, to, Limit.of(1));

        assertThat(result).extracting(WorkflowRun::getId).containsExactly(newest.getId());
        assertThat(runRepository.findByStartedAtBetweenOrderByStartedAtDesc(from, to, Limit.of(100)))
                .extracting(WorkflowRun::getId).containsSubsequence(newest.getId(), older.getId());
        assertThat(runRepository.countByStartedAtBetween(from, to)).isEqualTo(before + 2);
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
        String scheduleId = "schedule-" + java.util.UUID.randomUUID();
        WorkflowRun scheduled = run(workflowA, RunStatus.COMPLETED, Instant.now());
        scheduled.setTriggeredByScheduleId(scheduleId);
        runRepository.save(scheduled);
        run(workflowA, RunStatus.COMPLETED, Instant.now());

        List<WorkflowRun> result = runRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(scheduleId, Limit.unlimited());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTriggeredByScheduleId()).isEqualTo(scheduleId);
    }

    /**
     * The last status of a schedule for the single view: only the status column, newest
     * first, with {@code PageRequest.of(0, 1)} exactly one row (TODO 86286572).
     */
    @Test
    void should_find_only_the_newest_status_of_a_schedule() {
        String scheduleId = "schedule-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        scheduledRun(scheduleId, RunStatus.COMPLETED, now.minusSeconds(120));
        scheduledRun(scheduleId, RunStatus.FAILED, now);
        scheduledRun(scheduleId, RunStatus.CANCELLED, now.minusSeconds(60));
        run(workflowA, RunStatus.RUNNING, now.plusSeconds(60)); // another or no schedule

        assertThat(runRepository.findStatusesByScheduleIdNewestFirst(scheduleId, PageRequest.of(0, 10)))
                .containsExactly(RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.COMPLETED);
        assertThat(runRepository.findStatusesByScheduleIdNewestFirst(scheduleId, PageRequest.of(0, 1)))
                .containsExactly(RunStatus.FAILED);
        assertThat(runRepository.findStatusesByScheduleIdNewestFirst("no-such-schedule", PageRequest.of(0, 1))).isEmpty();
    }

    private void scheduledRun(String scheduleId, RunStatus status, Instant startedAt) {
        WorkflowRun scheduled = run(workflowA, status, startedAt);
        scheduled.setTriggeredByScheduleId(scheduleId);
        runRepository.save(scheduled);
    }

    /** The run list of a schedule: the newest up to the limit, plus their total count (TODO 86777fb4). */
    @Test
    void should_find_the_newest_runs_of_a_schedule_up_to_the_limit_and_count_them_all() {
        String scheduleId = "schedule-" + java.util.UUID.randomUUID();
        Instant now = Instant.now();
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            WorkflowRun scheduled = run(workflowA, RunStatus.COMPLETED, now.minusSeconds(60L * i));
            scheduled.setTriggeredByScheduleId(scheduleId);
            ids.add(runRepository.save(scheduled).getId());
        }

        assertThat(runRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(scheduleId, Limit.of(2)))
                .extracting(WorkflowRun::getId).containsExactly(ids.get(0), ids.get(1));
        assertThat(runRepository.countByTriggeredByScheduleId(scheduleId)).isEqualTo(3);
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
        List<DashboardRunRow> window = runRepository.findDashboardRows(since, null);

        assertThat(window).isNotEmpty();
        assertThat(window).allSatisfy(r -> assertThat(r.workflowId()).isNotEqualTo(workflowB.getId()));
    }

    // --- Dashboard window as a projection (TODO 2ccb9e40) ---

    /**
     * The dashboard window is a projection: one query, no entities — hence also no
     * {@code select containers} per row (TODO 13d1e2a2) and no input context. From metadata and
     * error come only the three values the dashboard reads, and only if they are text (or
     * todoTriggerRun a JSON true) — like the checks in Java before.
     */
    @Test
    void should_project_the_dashboard_window_with_only_the_values_the_dashboard_reads() {
        Instant now = Instant.now();
        WorkflowRun failed = run(workflowA, RunStatus.FAILED, now.minusSeconds(30));
        failed.setMetadata(new java.util.HashMap<>(Map.of("gate_status", "RED", "todoTriggerRun", true)));
        failed.setError(new java.util.HashMap<>(Map.of("message", "boom")));
        failed.setInputContext(new java.util.HashMap<>(Map.of("previous_report", "x".repeat(10_000))));
        failed.setDurationMs(1234L);
        WorkflowRun odd = run(workflowB, RunStatus.COMPLETED, now.minusSeconds(20));
        odd.setMetadata(new java.util.HashMap<>(Map.of("gate_status", 7, "todoTriggerRun", "true")));
        odd.setError(new java.util.HashMap<>(Map.of("message", "not a failure")));
        runRepository.saveAll(List.of(failed, odd));
        entityManager.flush();
        entityManager.clear();

        Counted<List<DashboardRunRow>> window = counted(
                () -> runRepository.findDashboardRows(now.minus(1, ChronoUnit.HOURS), null));

        assertThat(window.statements()).as("one query, no entity loads").isEqualTo(1);
        DashboardRunRow a = window.result().stream().filter(r -> r.id().equals(failed.getId())).findFirst().orElseThrow();
        assertThat(a).isEqualTo(new DashboardRunRow(failed.getId(), RunStatus.FAILED, a.startedAt(), null, 1234L,
                null, null, workflowA.getName(), workflowA.getId(), workflowA.getName(), project.getId(),
                project.getName(), "RED", "true", "boom"));
        assertThat(a.todoTriggerRun()).isTrue();
        DashboardRunRow b = window.result().stream().filter(r -> r.id().equals(odd.getId())).findFirst().orElseThrow();
        assertThat(b.gateStatus()).as("a number is no gate status").isNull();
        assertThat(b.todoTriggerRun()).as("the string \"true\" is no JSON true").isFalse();
        assertThat(b.failureMessage()).as("only FAILED runs have a failure reason").isNull();
        assertThat(b.projectName()).isEqualTo(projectB.getName());
        assertThat(window.result()).extracting(DashboardRunRow::id).containsSubsequence(odd.getId(), failed.getId());
    }

    /** The run window of the tool-pattern analysis: newest first, capped, since a cutoff (TODO 47c1cbe4). */
    @Test
    void should_find_the_newest_run_ids_of_a_workflow_since_a_cutoff() {
        Instant now = Instant.now();
        run(workflowA, RunStatus.COMPLETED, now.minus(100, ChronoUnit.DAYS));
        WorkflowRun first = run(workflowA, RunStatus.COMPLETED, now.minusSeconds(300));
        WorkflowRun second = run(workflowA, RunStatus.FAILED, now.minusSeconds(200));
        WorkflowRun third = run(workflowA, RunStatus.COMPLETED, now.minusSeconds(100));
        run(workflowB, RunStatus.COMPLETED, now);
        Instant cutoff = now.minus(90, ChronoUnit.DAYS);

        assertThat(runRepository.findRecentRunIds(workflowA.getId(), cutoff, PageRequest.of(0, 2)))
                .containsExactly(third.getId(), second.getId());
        assertThat(runRepository.findRecentRunIds(workflowA.getId(), cutoff, PageRequest.of(0, 10)))
                .containsExactly(third.getId(), second.getId(), first.getId());
    }

    /** Only [runId, metadata] per step – newest run first, its steps by index (TODO 47c1cbe4). */
    @Test
    void should_return_the_step_metadata_of_runs_newest_run_first_steps_in_order() {
        Instant now = Instant.now();
        WorkflowRun older = runWithSteps(now.minusSeconds(60), "older");
        WorkflowRun newer = runWithSteps(now, "newer");
        WorkflowRun other = runWithSteps(now.minusSeconds(30), "other");
        entityManager.flush();
        entityManager.clear();

        List<Object[]> rows = runRepository.findStepMetadataByRunIds(List.of(older.getId(), newer.getId()));

        assertThat(rows).extracting(row -> row[0])
                .containsExactly(newer.getId(), newer.getId(), older.getId(), older.getId());
        assertThat(rows).extracting(row -> (Object) ((Map<?, ?>) row[1]).get("step"))
                .containsExactly("newer-1", "newer-2", "older-1", "older-2");
        assertThat(rows).noneMatch(row -> row[0].equals(other.getId()));
    }

    /** A run whose two steps are added in reverse index order, each with an output and a metadata marker. */
    private WorkflowRun runWithSteps(Instant startedAt, String name) {
        WorkflowRun r = WorkflowRun.builder().workflow(workflowA).workflowName(workflowA.getName())
                .status(RunStatus.COMPLETED).startedAt(startedAt).build();
        for (int index : new int[]{2, 1}) {
            r.addStep(StepResult.builder().stepIndex(index).status(StepStatus.COMPLETED).startedAt(startedAt)
                    .output("output of " + name + "-" + index)
                    .metadata(new java.util.HashMap<>(Map.of("step", name + "-" + index))).build());
        }
        return runRepository.save(r);
    }

    /** The dashboard's project filter sits in both queries instead of in Java (TODO 2ccb9e40). */
    @Test
    void should_filter_the_dashboard_window_and_its_steps_by_project_in_the_query() {
        Instant now = Instant.now();
        for (Workflow workflow : List.of(workflowA, workflowB)) {
            WorkflowRun r = WorkflowRun.builder().workflow(workflow).workflowName(workflow.getName())
                    .status(RunStatus.COMPLETED).startedAt(now.minusSeconds(10)).build();
            r.addStep(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).startedAt(now)
                    .metadata(new java.util.HashMap<>(Map.of("blockType", "shell"))).build());
            runRepository.save(r);
        }
        entityManager.flush();
        entityManager.clear();
        Instant since = now.minus(1, ChronoUnit.HOURS);

        assertThat(runRepository.findDashboardRows(since, project.getId()))
                .isNotEmpty().allSatisfy(r -> assertThat(r.projectId()).isEqualTo(project.getId()));
        List<String> projectRunIds = runRepository.findDashboardRows(since, project.getId()).stream()
                .map(DashboardRunRow::id).toList();
        assertThat(runRepository.findStepMetadataForDashboard(since, project.getId()))
                .isNotEmpty().allSatisfy(row -> assertThat(projectRunIds).contains((String) row[0]));
        assertThat(runRepository.findDashboardRows(since, null)).extracting(DashboardRunRow::projectId)
                .contains(project.getId(), projectB.getId());
        assertThat(runRepository.findStepMetadataForDashboard(since, null)).hasSizeGreaterThanOrEqualTo(2);
    }

    /**
     * The dashboard's run counters come from one query instead of five (TODO 2ccb9e40):
     * success counts COMPLETED and COMPLETED_WITH_ERRORS, "today" only runs after the cutoff. Without a
     * project the query counts the whole table — which this JVM shares with other tests,
     * so the difference is checked there; with a project it counts only its runs.
     */
    @Test
    void should_count_the_dashboard_overview_in_one_query() {
        Instant today = Instant.now().minus(1, ChronoUnit.HOURS);
        RunOverviewCounts before = runRepository.countOverview(today);
        run(workflowA, RunStatus.COMPLETED, today.plusSeconds(60));
        run(workflowA, RunStatus.COMPLETED_WITH_ERRORS, today.minus(2, ChronoUnit.DAYS));
        run(workflowA, RunStatus.FAILED, today.plusSeconds(120));
        run(workflowA, RunStatus.RUNNING, today.minus(1, ChronoUnit.DAYS));
        run(workflowB, RunStatus.FAILED, today.plusSeconds(30));
        entityManager.flush();

        Counted<RunOverviewCounts> all = counted(() -> runRepository.countOverview(today));
        Counted<RunOverviewCounts> mine = counted(() -> runRepository.countOverviewByProject(project.getId(), today));

        assertThat(all.statements()).as("one query, not five counts").isEqualTo(1);
        assertThat(mine.statements()).as("one query, not five counts").isEqualTo(1);
        RunOverviewCounts added = all.result();
        assertThat(new RunOverviewCounts(added.total() - before.total(), added.succeeded() - before.succeeded(),
                added.failed() - before.failed(), added.startedSince() - before.startedSince()))
                .isEqualTo(new RunOverviewCounts(5, 2, 2, 3));
        assertThat(mine.result()).isEqualTo(new RunOverviewCounts(4, 2, 1, 2));
        assertThat(runRepository.countOverviewByProject("no-such-project", today))
                .isEqualTo(new RunOverviewCounts(0, 0, 0, 0));
    }

    private record Counted<T>(T result, int statements) {}

    /** Runs the query and counts the SQL statements it issues. */
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
}
