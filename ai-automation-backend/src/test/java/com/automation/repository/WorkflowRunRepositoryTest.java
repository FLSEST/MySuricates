package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
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
import java.util.List;
import java.util.Optional;

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

    @Test
    void should_find_by_id_with_steps() {
        WorkflowRun saved = run(workflowA, RunStatus.COMPLETED, Instant.now());

        Optional<WorkflowRun> result = runRepository.findByIdWithSteps(saved.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
    }
}
