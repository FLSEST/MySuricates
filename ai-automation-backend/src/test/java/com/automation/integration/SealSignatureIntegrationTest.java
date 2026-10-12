package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowDefinitionHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The signature closes the gap the seal alone leaves: {@code sealed_hash} lives in the same SQLite
 * file as the definition it protects, so a direct database edit could rewrite both and pass. With a
 * key outside the database that no longer works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "automation.hardening.sealing=true")
class SealSignatureIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowDefinitionHasher hasher;
    @Autowired private JdbcTemplate jdbc;

    private String projectId;
    private String workflowId;

    @BeforeEach
    void setUp() {
        Project project = projectRepository.save(Project.builder()
                .name("Seal signature " + System.nanoTime()).type(ProjectType.CODE)
                .path("/tmp/seal-signature-test").status(ProjectStatus.STOPPED).build());
        projectId = project.getId();
        workflowId = workflowRepository.save(Workflow.builder()
                .name("Signed deploy").project(project).blocks(new ArrayList<>()).instructions("original")
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
    void sealIsSignedAndTheHistoryRecordsIt() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", workflowId)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT sealed_signature FROM workflows WHERE id = ?", String.class, workflowId))
                .isNotBlank();
        mockMvc.perform(get("/api/v1/workflows/seal-status"))
                .andExpect(jsonPath("$[?(@.workflowId == '" + workflowId + "')].signed").value(true));
        mockMvc.perform(get("/api/v1/workflows/{id}/seal-events", workflowId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("SEALED"))
                .andExpect(jsonPath("$[0].signed").value(true))
                .andExpect(jsonPath("$[0].chainIntact").value(true))
                .andExpect(jsonPath("$[0].occurredAt").isString());
    }

    @Test
    void aSealForgedInTheDatabaseIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", workflowId)).andExpect(status().isOk());

        // The whole point: change the definition AND move the seal along, exactly what someone with
        // write access to the SQLite file would do. Without the signature this run would start.
        jdbc.update("UPDATE workflows SET instructions = ? WHERE id = ?", "rm -rf /", workflowId);
        String forgedHash = hasher.hash(workflowRepository.findByIdWithSkills(workflowId).orElseThrow());
        jdbc.update("UPDATE workflows SET sealed_hash = ? WHERE id = ?", forgedHash, workflowId);

        mockMvc.perform(post("/api/v1/workflows/{id}/execute", workflowId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_TAMPERED"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("signature")));
        assertThat(runRepository.countByWorkflowId(workflowId)).isZero();

        mockMvc.perform(get("/api/v1/workflows/seal-status"))
                .andExpect(jsonPath("$[?(@.workflowId == '" + workflowId + "')].problem").value("SIGNATURE_INVALID"));
    }

    @Test
    void unsealingIsRecordedAndClearsTheSignature() throws Exception {
        mockMvc.perform(post("/api/v1/workflows/{id}/seal", workflowId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/workflows/{id}/unseal", workflowId)).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT sealed_signature FROM workflows WHERE id = ?", String.class, workflowId))
                .isNull();
        mockMvc.perform(get("/api/v1/workflows/{id}/seal-events", workflowId))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].action").value("UNSEALED"))
                .andExpect(jsonPath("$[1].action").value("SEALED"))
                .andExpect(jsonPath("$[0].chainIntact").value(true));
    }
}
