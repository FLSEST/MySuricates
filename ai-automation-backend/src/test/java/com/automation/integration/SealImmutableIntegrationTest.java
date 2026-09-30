package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With {@code seal-immutable} a seal cannot be released over REST at all — the switch is startup
 * configuration, so an API key (a leaked one, or an agent holding it) is not enough any more.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {"automation.hardening.sealing=true", "automation.hardening.seal-immutable=true"})
class SealImmutableIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;
    private String workflowId;

    @BeforeEach
    void setUp() {
        Project project = projectRepository.save(Project.builder()
                .name("Seal immutable " + System.nanoTime()).type(ProjectType.CODE)
                .path("/tmp/seal-immutable-test").status(ProjectStatus.STOPPED).build());
        projectId = project.getId();
        workflowId = workflowRepository.save(Workflow.builder()
                .name("Locked deploy").project(project).blocks(new ArrayList<>()).instructions("original")
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM workflow_seal_events WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM workflow_versions WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM activity_log WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM workflows WHERE id = ?", workflowId);
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    @Test
    void sealingStillWorksButUnsealingIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", workflowId)).andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/workflows/{id}/unseal", workflowId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SEAL_IMMUTABLE"));

        assertThat(jdbc.queryForObject("SELECT sealed FROM workflows WHERE id = ?", Boolean.class, workflowId)).isTrue();
        assertThat(refusalEntries()).isEqualTo(1);
    }

    /**
     * The refusal is written after the request's transaction has ended, on its own thread
     * ({@code ActivityLogService.logRefusal}) — counting right after the response races that write.
     */
    private int refusalEntries() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int count;
        while ((count = countRefusals()) == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        return count;
    }

    private int countRefusals() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE workflow_id = ? AND message LIKE 'Unseal refused%'",
                Integer.class, workflowId);
    }
}
