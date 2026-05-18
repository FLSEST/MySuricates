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
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for {@link LearningService#getDashboard} executed <b>without</b> a
 * surrounding transaction — exactly as the controller calls it (OSIV is disabled).
 *
 * <p>This deliberately does <b>not</b> use {@code @Transactional}: a transactional test
 * keeps its own Hibernate session open, which would mask the {@code LazyInitializationException}
 * this test exists to catch. Runs created here are removed in {@link #cleanUp()}.
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

    private final List<String> createdRunIds = new ArrayList<>();

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
}
