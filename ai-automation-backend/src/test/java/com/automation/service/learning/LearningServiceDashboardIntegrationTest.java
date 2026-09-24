package com.automation.service.learning;

import com.automation.dto.response.DashboardResponse;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link LearningService#getDashboard} executed <b>without</b> a
 * surrounding transaction — exactly as the controller calls it (OSIV is disabled).
 *
 * <p>This deliberately does <b>not</b> use {@code @Transactional}: a transactional test
 * keeps its own Hibernate session open, which would mask the {@code LazyInitializationException}
 * this test exists to catch. Everything created here — runs, workflows, projects — is removed in
 * {@link #cleanUp()}: the integration tests of one JVM share one database, and the projects and
 * workflows this test used to leave behind pushed {@code RequestCostRatchetTest} over its ceilings
 * whenever it ran first (TODO 5695d482).
 *
 * <p>Regression coverage for the two bugs introduced by commit 8a93e06e:
 * <ul>
 *   <li>{@code FetchNotFoundException} — a run of a soft-deleted workflow.</li>
 *   <li>{@code LazyInitializationException} — {@code recentRuns} mapping touching un-fetched steps.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("integration")
class LearningServiceDashboardIntegrationTest {

    @Autowired
    private LearningService learningService;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowRunRepository runRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> createdRunIds = new ArrayList<>();
    private final List<String> createdWorkflowIds = new ArrayList<>();
    private final List<String> createdProjectIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        // Non-transactional test — remove the runs it created (steps cascade away with them).
        createdRunIds.forEach(id -> {
            try {
                runRepository.deleteById(id);
            } catch (Exception ignored) {
                // already gone
            }
        });
        createdRunIds.clear();
        // Plain SQL: the soft-deleted workflow is hidden from the repository by @SQLRestriction.
        createdWorkflowIds.forEach(id -> jdbc.update("DELETE FROM workflows WHERE id = ?", id));
        createdWorkflowIds.clear();
        createdProjectIds.forEach(id -> jdbc.update("DELETE FROM projects WHERE id = ?", id));
        createdProjectIds.clear();
    }

    @Test
    void getDashboard_withRunOfSoftDeletedWorkflow_doesNotThrowAndMapsRecentRuns() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("dash-it-" + nano)
                .description("Dashboard IT")
                .type(ProjectType.CODE)
                .path("/tmp/dash-it-" + nano)
                .status(ProjectStatus.STOPPED)
                .build());
        createdProjectIds.add(project.getId());

        Workflow liveWorkflow = workflowRepository.save(Workflow.builder()
                .name("live-wf-" + nano)
                .description("Live workflow")
                .project(project)
                .build());
        Workflow deletedWorkflow = workflowRepository.save(Workflow.builder()
                .name("deleted-wf-" + nano)
                .description("Soon-deleted workflow")
                .project(project)
                .build());
        createdWorkflowIds.add(liveWorkflow.getId());
        createdWorkflowIds.add(deletedWorkflow.getId());

        Instant now = Instant.now();
        WorkflowRun liveRun = WorkflowRun.builder()
                .workflow(liveWorkflow)
                .workflowName(liveWorkflow.getName())
                .status(RunStatus.COMPLETED)
                .startedAt(now.minusSeconds(60))
                .build();
        liveRun.addStep(StepResult.builder()
                .stepIndex(0)
                .status(StepStatus.COMPLETED)
                .startedAt(now.minusSeconds(60))
                .durationMs(500L)
                .build());
        createdRunIds.add(runRepository.save(liveRun).getId());

        WorkflowRun deletedWorkflowRun = WorkflowRun.builder()
                .workflow(deletedWorkflow)
                .workflowName(deletedWorkflow.getName())
                .status(RunStatus.COMPLETED)
                .startedAt(now.minusSeconds(30))
                .build();
        deletedWorkflowRun.addStep(StepResult.builder()
                .stepIndex(0)
                .status(StepStatus.COMPLETED)
                .startedAt(now.minusSeconds(30))
                .durationMs(700L)
                .build());
        createdRunIds.add(runRepository.save(deletedWorkflowRun).getId());

        // Soft-delete the second workflow — its run now points at a row hidden by @SQLRestriction.
        deletedWorkflow.setDeletedAt(Instant.now());
        workflowRepository.save(deletedWorkflow);

        // Must not throw FetchNotFoundException (bug #1) or LazyInitializationException (bug #2).
        DashboardResponse dashboard = learningService.getDashboard(30, null);

        assertThat(dashboard).isNotNull();
        // The live run is present, with its steps mapped (proves steps were eager-fetched).
        assertThat(dashboard.recentRuns())
                .anySatisfy(r -> {
                    assertThat(r.workflowId()).isEqualTo(liveWorkflow.getId());
                    assertThat(r.steps()).isNotEmpty();
                });
        // The run of the soft-deleted workflow is excluded.
        assertThat(dashboard.recentRuns())
                .noneSatisfy(r -> assertThat(r.workflowId()).isEqualTo(deletedWorkflow.getId()));
    }

    /**
     * Proves the {@code findStepMetadataForDashboard} projection deserializes the
     * {@code @Convert(JsonMapConverter)} metadata Map against real SQLite, and that
     * cost/token/provider aggregation works <b>without</b> loading the heavy step
     * input/output TEXT columns.
     */
    @Test
    void getDashboard_aggregatesCostTokensProviderFromStepMetadata_viaProjection() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("dash-cost-" + nano)
                .description("Dashboard cost IT")
                .type(ProjectType.CODE)
                .path("/tmp/dash-cost-" + nano)
                .status(ProjectStatus.STOPPED)
                .build());
        createdProjectIds.add(project.getId());
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("cost-wf-" + nano)
                .description("Cost workflow")
                .project(project)
                .build());
        createdWorkflowIds.add(workflow.getId());

        Instant now = Instant.now();
        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .workflowName(workflow.getName())
                .status(RunStatus.COMPLETED)
                .startedAt(now.minusSeconds(45))
                .build();
        run.addStep(StepResult.builder()
                .stepIndex(0)
                .status(StepStatus.COMPLETED)
                .startedAt(now.minusSeconds(45))
                .durationMs(1200L)
                // Large blobs the dashboard must NOT need to load:
                .input("x".repeat(5000))
                .output("y".repeat(5000))
                .metadata(Map.of(
                        "provider", "CLAUDE",
                        "model", "claude-sonnet-4-6",
                        "blockType", "simple",
                        "costUsd", 0.25,
                        "inputTokens", 1000,
                        "outputTokens", 500))
                .build());
        createdRunIds.add(runRepository.save(run).getId());

        DashboardResponse dashboard = learningService.getDashboard(30, null);

        assertThat(dashboard.totalCostUsd30d()).isGreaterThanOrEqualTo(0.25);
        assertThat(dashboard.totalTokens30d()).isGreaterThanOrEqualTo(1500L);
        assertThat(dashboard.providerStats())
                .extracting(DashboardResponse.ProviderStats::provider)
                .contains("CLAUDE");
    }

    /**
     * With a project filter the overview figures (workflow/run counts, success rate, runs today,
     * top workflows) count only that project — the queries run against real SQLite (TODO 4e1c9ad4).
     */
    @Test
    void getDashboard_withProjectFilter_scopesOverviewToProject() {
        long nano = System.nanoTime();
        Project mine = saveProject("dash-scope-a-" + nano);
        Project other = saveProject("dash-scope-b-" + nano);
        Workflow mineWf = saveWorkflow("scope-a-wf-" + nano, mine);
        Workflow otherWf = saveWorkflow("scope-b-wf-" + nano, other);

        Instant now = Instant.now();
        saveRun(mineWf, RunStatus.COMPLETED, now);
        saveRun(mineWf, RunStatus.FAILED, now);
        saveRun(otherWf, RunStatus.COMPLETED, now);
        saveRun(otherWf, RunStatus.COMPLETED, now);
        saveRun(otherWf, RunStatus.COMPLETED, now);

        DashboardResponse dashboard = learningService.getDashboard(30, mine.getId());

        assertThat(dashboard.totalWorkflows()).isEqualTo(1);
        assertThat(dashboard.totalRuns()).isEqualTo(2);
        assertThat(dashboard.successfulRuns()).isEqualTo(1);
        assertThat(dashboard.failedRuns()).isEqualTo(1);
        assertThat(dashboard.successRate()).isEqualTo(0.5);
        assertThat(dashboard.runsToday()).isEqualTo(2);
        assertThat(dashboard.topWorkflows())
                .extracting(DashboardResponse.TopWorkflowEntry::workflowId)
                .containsExactly(mineWf.getId());

        // Unfiltered, the other project's runs count as well.
        assertThat(learningService.getDashboard(30, null).totalRuns()).isGreaterThanOrEqualTo(5);
    }

    private Project saveProject(String name) {
        Project project = projectRepository.save(Project.builder()
                .name(name)
                .description("Dashboard IT")
                .type(ProjectType.CODE)
                .path("/tmp/" + name)
                .status(ProjectStatus.STOPPED)
                .build());
        createdProjectIds.add(project.getId());
        return project;
    }

    private Workflow saveWorkflow(String name, Project project) {
        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name(name)
                .description("Dashboard IT")
                .project(project)
                .build());
        createdWorkflowIds.add(workflow.getId());
        return workflow;
    }

    private void saveRun(Workflow workflow, RunStatus status, Instant startedAt) {
        createdRunIds.add(runRepository.save(WorkflowRun.builder()
                .workflow(workflow)
                .workflowName(workflow.getName())
                .status(status)
                .startedAt(startedAt)
                .build()).getId());
    }
}
