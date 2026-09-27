package com.automation.integration;

import com.automation.config.AutoResumeConfig;
import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.project.ContainerService;
import com.automation.service.workflow.RunAutoResumer;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.RunInterruption;
import com.automation.service.workflow.WorkflowExecutor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * TODO 90d10ba1 on the real stack: the startup cleanup marks leftover runs INTERRUPTED and the
 * auto-resumer continues exactly the opted-in, formerly RUNNING, top-level runs — once, as
 * {@link TriggerSource#AUTO_RESUME}, after the completed block. Same context configuration as
 * {@code WorkflowExecutorDeterministicIntegrationTest}, so the context is shared.
 */
@SpringBootTest
@ActiveProfiles("integration")
class RunAutoResumeIntegrationTest {

    @Autowired private WorkflowExecutor workflowExecutor;
    @Autowired private RunControlService runControlService;
    @Autowired private RunAutoResumer resumer;
    @Autowired private AutoResumeConfig config;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private ContainerService containerService;

    @TempDir Path tempDir;

    private InterruptedRunFixture fixture;
    private long previousDelay;
    private Project project;

    @BeforeEach
    void setUp() {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
        fixture = new InterruptedRunFixture(workflowExecutor, projectRepository, workflowRepository, runRepository,
                stepResultRepository, entityManager, resumer);
        previousDelay = config.getDelaySeconds();
        config.setDelaySeconds(0);
        project = fixture.project(tempDir);
    }

    @AfterEach
    void tearDown() {
        fixture.awaitResumerIdle();
        config.setDelaySeconds(previousDelay);
        config.setEnabled(true);
        stepResultRepository.deleteAll();
        runRepository.deleteAll();
        workflowRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    void optedInRun_isResumedAfterRestart_asAutoResume_fromTheInterruptedBlock() throws Exception {
        Workflow workflow = fixture.workflow(project, true);
        WorkflowRun run = fixture.inFlight(workflow, RunStatus.RUNNING, r -> { });

        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();
        WorkflowRun resumed = fixture.awaitFinished(run.getId());

        assertThat(resumed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumed.getTriggerSource()).isEqualTo(TriggerSource.AUTO_RESUME);
        assertThat(resumed.getMetadata())
                .containsEntry(RunAutoResumer.ATTEMPTS_KEY, 1)
                .containsEntry(RunInterruption.FROM_KEY, "RUNNING")
                .containsEntry(RunInterruption.BY_KEY, "RESTART");
        assertThat(fixture.lines(project, "a.log")).as("completed block does not run again").isEqualTo(1);
        assertThat(fixture.lines(project, "b.log")).as("interrupted block runs from the start").isEqualTo(1);
        assertThat(runRepository.countByWorkflowId(workflow.getId())).as("resumed in place, no new run").isEqualTo(1);
    }

    /**
     * Review finding: without a finished block the resume is a full retry — a new run. It carries the
     * attempt over, so its own interruption (e.g. the next standby) is not resumed automatically again.
     */
    @Test
    void fullRetry_newRunCarriesTheAttempt_andIsNotResumedAgain() {
        Workflow workflow = fixture.workflowWithoutFinishedBlock(project);
        WorkflowRun run = fixture.inFlight(workflow, RunStatus.RUNNING, r -> { });

        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();

        WorkflowRun old = fixture.reload(run);
        assertThat(old.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        String newRunId = (String) old.getMetadata().get(RunAutoResumer.RESTARTED_AS_KEY);
        assertThat(newRunId).isNotNull().isNotEqualTo(run.getId());
        WorkflowRun retry = fixture.awaitFinished(newRunId);
        assertThat(retry.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(retry.getTriggerSource()).isEqualTo(TriggerSource.AUTO_RESUME);
        assertThat(retry.getMetadata())
                .containsEntry(RunAutoResumer.ATTEMPTS_KEY, 1)
                .containsEntry(RunAutoResumer.RESUMED_FROM_KEY, run.getId());

        // The new run is interrupted as well: it stays for a person, no third run.
        WorkflowRun again = fixture.reload(retry);
        again.setStatus(RunStatus.RUNNING);
        again.setStartedAt(InterruptedRunFixture.beforeJvmStart());
        again.setCompletedAt(null);
        runRepository.saveAndFlush(again);
        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();

        assertThat(fixture.reload(retry).getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(runRepository.countByWorkflowId(workflow.getId())).isEqualTo(2);
    }

    @Test
    void runsThatDoNotQualify_stayInterruptedForAPerson() {
        WorkflowRun withoutOptIn = fixture.inFlight(fixture.workflow(project, false), RunStatus.RUNNING, r -> { });
        Workflow optedIn = fixture.workflow(project, true);
        WorkflowRun paused = fixture.inFlight(optedIn, RunStatus.PAUSED, r -> { });
        WorkflowRun alreadyTried = fixture.inFlight(optedIn, RunStatus.RUNNING,
                r -> r.setMetadata(new HashMap<>(Map.of(RunAutoResumer.ATTEMPTS_KEY, 1))));
        WorkflowRun chainEntry = fixture.inFlight(optedIn, RunStatus.RUNNING, r -> {
            r.setTriggerSource(TriggerSource.CHAIN);
            r.setChainRunId("chain-run-1");
        });
        WorkflowRun child = fixture.inFlight(optedIn, RunStatus.RUNNING,
                r -> r.setTriggerSource(TriggerSource.SUB_WORKFLOW));

        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();

        for (WorkflowRun run : new WorkflowRun[] {withoutOptIn, paused, alreadyTried, chainEntry, child}) {
            WorkflowRun stored = fixture.reload(run);
            assertThat(stored.getStatus()).as(run.getId()).isEqualTo(RunStatus.INTERRUPTED);
            assertThat(stored.getTriggerSource()).as(run.getId()).isNotEqualTo(TriggerSource.AUTO_RESUME);
        }
        assertThat(fixture.reload(paused).getMetadata()).containsEntry(RunInterruption.FROM_KEY, "PAUSED")
                .doesNotContainKey(RunAutoResumer.ATTEMPTS_KEY);
        assertThat(fixture.reload(alreadyTried).getMetadata()).containsEntry(RunAutoResumer.ATTEMPTS_KEY, 1);
    }

    @Test
    void globalSwitchOff_resumesNothing() {
        config.setEnabled(false);
        WorkflowRun run = fixture.inFlight(fixture.workflow(project, true), RunStatus.RUNNING, r -> { });

        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();

        assertThat(fixture.reload(run).getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(fixture.reload(run).getMetadata()).doesNotContainKey(RunAutoResumer.ATTEMPTS_KEY);
    }

    @Test
    void secondInterruption_afterTheAutomaticResume_isNotResumedAgain() {
        Workflow workflow = fixture.workflow(project, true);
        WorkflowRun run = fixture.inFlight(workflow, RunStatus.RUNNING, r -> { });
        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();
        fixture.awaitFinished(run.getId());

        // The resumed run is caught by the next restart as well.
        WorkflowRun again = fixture.reload(run);
        again.setStatus(RunStatus.RUNNING);
        again.setStartedAt(InterruptedRunFixture.beforeJvmStart());
        again.setCompletedAt(null);
        runRepository.saveAndFlush(again);
        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();

        WorkflowRun stored = fixture.reload(run);
        assertThat(stored.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(stored.getMetadata()).containsEntry(RunAutoResumer.ATTEMPTS_KEY, 1);
    }
}
