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
import com.automation.service.workflow.WorkflowExecutor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TODO 90d10ba1: an automatic resume is unattended. With {@code require-sealed-for-unattended} an
 * unsealed workflow is refused by RunAdmission — the run stays INTERRUPTED, a SECURITY entry is
 * written and there is no second attempt; a sealed one is resumed. Same context configuration as
 * {@code WebhookHardeningIntegrationTest}, so the context is shared.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "automation.hardening.sealing=true",
        "automation.hardening.require-sealed-for-unattended=true",
        "automation.hardening.webhook-signatures=true"})
class RunAutoResumeHardeningIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
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
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
        fixture = new InterruptedRunFixture(workflowExecutor, projectRepository, workflowRepository, runRepository,
                stepResultRepository, entityManager, resumer);
        previousDelay = config.getDelaySeconds();
        config.setDelaySeconds(0);
        project = fixture.project(tempDir);
        workflow = fixture.workflow(project, true);
    }

    @AfterEach
    void tearDown() {
        fixture.awaitResumerIdle();
        config.setDelaySeconds(previousDelay);
        String id = workflow.getId();
        jdbc.update("DELETE FROM step_results WHERE workflow_run_id IN (SELECT id FROM workflow_runs WHERE workflow_id = ?)", id);
        jdbc.update("DELETE FROM workflow_runs WHERE workflow_id = ?", id);
        jdbc.update("DELETE FROM workflow_seal_events WHERE workflow_id = ?", id);
        jdbc.update("DELETE FROM workflow_versions WHERE workflow_id = ?", id);
        jdbc.update("DELETE FROM activity_log WHERE workflow_id = ? OR reference_id = ?", id, id);
        jdbc.update("DELETE FROM workflows WHERE id = ?", id);
        jdbc.update("DELETE FROM projects WHERE id = ?", project.getId());
    }

    @Test
    void unsealedWorkflow_isRefused_staysInterrupted_withASecurityEntry_andNoSecondAttempt() {
        WorkflowRun run = fixture.inFlight(workflow, RunStatus.RUNNING, r -> { });   // MANUAL: attended, allowed

        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();

        WorkflowRun stored = fixture.reload(run);
        assertThat(stored.getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(stored.getTriggerSource()).isEqualTo(TriggerSource.MANUAL);
        assertThat(stored.getMetadata()).containsEntry(RunAutoResumer.ATTEMPTS_KEY, 1);
        InterruptedRunFixture.await("the SECURITY entry of the refusal", () -> countSecurityEntries() > 0);
        assertThat(jdbc.queryForObject("SELECT message FROM activity_log WHERE workflow_id = ? AND category = 'SECURITY'",
                String.class, workflow.getId())).contains("AUTO_RESUME");

        // A later restart does not try again.
        WorkflowRun again = fixture.reload(run);
        again.setStatus(RunStatus.RUNNING);
        runRepository.saveAndFlush(again);
        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();
        assertThat(fixture.reload(run).getStatus()).isEqualTo(RunStatus.INTERRUPTED);
        assertThat(countSecurityEntries()).isEqualTo(1);
    }

    @Test
    void sealedWorkflow_isResumedAutomatically() throws Exception {
        WorkflowRun run = fixture.inFlight(workflow, RunStatus.RUNNING, r -> { });
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", workflow.getId())).andExpect(status().isOk());

        runControlService.cleanupZombieRuns();
        fixture.awaitResumerIdle();
        WorkflowRun resumed = fixture.awaitFinished(run.getId());

        assertThat(resumed.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumed.getTriggerSource()).isEqualTo(TriggerSource.AUTO_RESUME);
        assertThat(resumed.getSealed()).isTrue();
    }

    private int countSecurityEntries() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM activity_log WHERE workflow_id = ? AND category = 'SECURITY'",
                Integer.class, workflow.getId());
        return count != null ? count : 0;
    }
}
