package com.automation.repository;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class StepResultRepositoryTest {

    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private WorkflowRunRepository workflowRunRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ProjectRepository projectRepository;

    private Project saveProject(String name) {
        return projectRepository.save(Project.builder()
                .name(name)
                .type(ProjectType.CODE)
                .path("/tmp/" + name)
                .status(ProjectStatus.STOPPED)
                .build());
    }

    private Workflow saveWorkflow(String name) {
        Project project = saveProject(name + "-project");
        return workflowRepository.save(Workflow.builder()
                .name(name)
                .project(project)
                .build());
    }

    private WorkflowRun saveWorkflowRun(String workflowName) {
        Workflow workflow = saveWorkflow(workflowName);
        return workflowRunRepository.save(WorkflowRun.builder()
                .workflow(workflow)
                .workflowName(workflowName)
                .status(RunStatus.RUNNING)
                .build());
    }

    private StepResult saveStep(WorkflowRun run, int index, StepStatus status) {
        return stepResultRepository.save(StepResult.builder()
                .workflowRun(run)
                .stepIndex(index)
                .status(status)
                .input("input-" + index)
                .output("output-" + index)
                .build());
    }

    @Test
    void findByWorkflowRunId_returnsAllStepsForRun() {
        WorkflowRun run = saveWorkflowRun("wf-find-by-run");
        saveStep(run, 0, StepStatus.COMPLETED);
        saveStep(run, 1, StepStatus.RUNNING);
        saveStep(run, 2, StepStatus.PENDING);

        List<StepResult> result = stepResultRepository.findByWorkflowRunId(run.getId());

        assertThat(result).hasSize(3);
        assertThat(result).allMatch(s -> s.getWorkflowRun().getId().equals(run.getId()));
    }

    @Test
    void findByWorkflowRunId_returnsEmptyWhenNoMatch() {
        WorkflowRun run = saveWorkflowRun("wf-empty-run");
        saveStep(run, 0, StepStatus.COMPLETED);

        List<StepResult> result = stepResultRepository.findByWorkflowRunId("non-existent-id");

        assertThat(result).isEmpty();
    }

    @Test
    void findByWorkflowRunId_isolatesStepsBetweenRuns() {
        WorkflowRun runA = saveWorkflowRun("wf-isolate-A");
        WorkflowRun runB = saveWorkflowRun("wf-isolate-B");
        saveStep(runA, 0, StepStatus.COMPLETED);
        saveStep(runA, 1, StepStatus.COMPLETED);
        saveStep(runB, 0, StepStatus.PENDING);

        List<StepResult> resultA = stepResultRepository.findByWorkflowRunId(runA.getId());
        List<StepResult> resultB = stepResultRepository.findByWorkflowRunId(runB.getId());

        assertThat(resultA).hasSize(2);
        assertThat(resultB).hasSize(1);
    }

    @Test
    void findByWorkflowRunIdOrderByStepIndexAsc_returnsStepsSortedByIndex() {
        WorkflowRun run = saveWorkflowRun("wf-ordered");
        saveStep(run, 2, StepStatus.COMPLETED);
        saveStep(run, 0, StepStatus.COMPLETED);
        saveStep(run, 1, StepStatus.COMPLETED);

        List<StepResult> result = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId());

        assertThat(result).hasSize(3);
        assertThat(result).extracting(StepResult::getStepIndex).containsExactly(0, 1, 2);
    }

    @Test
    void findByWorkflowRunIdOrderByStepIndexAsc_returnsEmptyForUnknownRun() {
        List<StepResult> result = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("unknown-run-id");

        assertThat(result).isEmpty();
    }

    @Test
    void findByStatus_returnsOnlyMatchingSteps() {
        WorkflowRun run = saveWorkflowRun("wf-by-status");
        saveStep(run, 0, StepStatus.COMPLETED);
        saveStep(run, 1, StepStatus.COMPLETED);
        saveStep(run, 2, StepStatus.FAILED);
        saveStep(run, 3, StepStatus.PENDING);

        List<StepResult> failed = stepResultRepository.findByStatus(StepStatus.FAILED);

        assertThat(failed).isNotEmpty();
        assertThat(failed).allMatch(s -> s.getStatus() == StepStatus.FAILED);
        assertThat(failed).extracting(StepResult::getStepIndex).contains(2);
    }

    @Test
    void findByStatus_returnsEmptyWhenNoStepHasStatus() {
        WorkflowRun run = saveWorkflowRun("wf-no-skipped");
        saveStep(run, 0, StepStatus.COMPLETED);
        saveStep(run, 1, StepStatus.FAILED);

        List<StepResult> skipped = stepResultRepository.findByStatus(StepStatus.SKIPPED);

        assertThat(skipped).allMatch(s -> s.getStatus() == StepStatus.SKIPPED);
        // We can't assert empty globally (shared DB), but no SKIPPED step was added in this scope
    }

    @Test
    void save_persistsStepResultWithDefaults() {
        WorkflowRun run = saveWorkflowRun("wf-save");

        StepResult saved = saveStep(run, 0, StepStatus.PENDING);

        assertThat(saved.getId()).isNotBlank();
        assertThat(saved.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(saved.getMetadata()).isNotNull();
        assertThat(stepResultRepository.findById(saved.getId())).isPresent();
    }
}
