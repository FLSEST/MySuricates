package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sealing end to end on the real stack (SQLite, pool-size 1): a sealed workflow rejects definition
 * changes over the API, and a definition modified behind the application's back (raw SQL) is
 * refused before any run row is created.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "automation.hardening.sealing=true")
class WorkflowSealingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;
    private String workflowId;

    @BeforeEach
    void setUp() {
        Project project = projectRepository.save(Project.builder()
                .name("Sealing " + System.nanoTime()).type(ProjectType.CODE)
                .path("/tmp/sealing-test").status(ProjectStatus.STOPPED).build());
        projectId = project.getId();
        workflowId = workflowRepository.save(Workflow.builder()
                .name("Sealed deploy").project(project).blocks(new ArrayList<>()).instructions("original")
                .build()).getId();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM workflow_versions WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM activity_log WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM workflows WHERE id = ?", workflowId);
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    @Test
    void sealedWorkflowRejectsDefinitionChangesAndDeleteButAllowsRenaming() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", workflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealed").value(true))
                .andExpect(jsonPath("$.sealedHash").isString());

        mockMvc.perform(put("/api/v1/workflows/{id}", workflowId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"instructions\":\"changed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_SEALED"));
        assertThat(jdbc.queryForObject("SELECT instructions FROM workflows WHERE id = ?", String.class, workflowId))
                .isEqualTo("original");

        mockMvc.perform(put("/api/v1/workflows/{id}", workflowId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed deploy\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/workflows/{id}", workflowId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_SEALED"));
    }

    @Test
    void tamperedDefinitionIsRefusedBeforeARunExists() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", workflowId)).andExpect(status().isOk());

        // modified behind the application's back
        jdbc.update("UPDATE workflows SET instructions = ? WHERE id = ?", "rm -rf /", workflowId);

        mockMvc.perform(post("/api/v1/workflows/{id}/execute", workflowId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_TAMPERED"));

        assertThat(runRepository.countByWorkflowId(workflowId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM activity_log WHERE workflow_id = ? AND category = 'SECURITY' AND level = 'ERROR'",
                Integer.class, workflowId)).isEqualTo(1);
    }

    @Test
    void unsealingAllowsChangesAgain() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", workflowId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/workflows/{id}/unseal", workflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealed").value(false));

        mockMvc.perform(put("/api/v1/workflows/{id}", workflowId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"instructions\":\"changed\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT change_source FROM workflow_versions WHERE workflow_id = ? ORDER BY version_number DESC LIMIT 1",
                String.class, workflowId)).isEqualTo("user");
    }
}
