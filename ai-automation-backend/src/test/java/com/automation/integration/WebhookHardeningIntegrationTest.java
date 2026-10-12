package com.automation.integration;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.project.ContainerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook hardening on the real stack: unsealed workflows are not started by webhooks when
 * {@code require-sealed-for-unattended} is on, signatures are checked over the raw body, and a
 * sealed workflow with a correctly signed call runs with trigger source WEBHOOK.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "automation.hardening.sealing=true",
        "automation.hardening.require-sealed-for-unattended=true",
        "automation.hardening.webhook-signatures=true"})
class WebhookHardeningIntegrationTest {

    private static final String BODY = "{\"action\": \"deploy\"}";
    private static final Set<RunStatus> TERMINAL = Set.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS,
            RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.INTERRUPTED);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private ContainerService containerService;

    @TempDir Path tempDir;

    private String projectId;
    private String workflowId;
    private String webhookId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
        Project project = projectRepository.save(Project.builder()
                .name("Webhook hardening " + System.nanoTime()).type(ProjectType.CODE)
                .path(tempDir.toString()).status(ProjectStatus.STOPPED).build());
        projectId = project.getId();
        BlockDefinition echo = BlockDefinition.builder().type("shell")
                .config(new HashMap<>(Map.of("command", "echo webhook-ok", "timeout_seconds", 5))).build();
        workflowId = workflowRepository.save(Workflow.builder()
                .name("Webhook target").project(project).blocks(new ArrayList<>(List.of(echo))).build()).getId();

        String created = mockMvc.perform(post("/api/v1/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":\"" + workflowId + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode webhook = objectMapper.readTree(created);
        webhookId = webhook.get("id").asText();
        token = webhook.get("secretToken").asText();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM step_results WHERE workflow_run_id IN (SELECT id FROM workflow_runs WHERE workflow_id = ?)", workflowId);
        jdbc.update("DELETE FROM workflow_runs WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM webhook_triggers WHERE id = ?", webhookId);
        jdbc.update("DELETE FROM workflow_versions WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM activity_log WHERE workflow_id = ? OR reference_id = ?", workflowId, webhookId);
        jdbc.update("DELETE FROM workflows WHERE id = ?", workflowId);
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    @Test
    void unsealedWorkflowIsNotStartedByAWebhook() throws Exception {
        mockMvc.perform(post("/api/v1/webhook/trigger/{token}", token).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("UNATTENDED_REQUIRES_SEAL"));

        assertThat(runRepository.countByWorkflowId(workflowId)).isZero();
    }

    @Test
    void signedCallsOnlyAndSealedWorkflowsRun() throws Exception {
        String secret = objectMapper.readTree(mockMvc.perform(post("/api/v1/webhooks/{id}/secret", webhookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header").value("X-Signature"))
                .andReturn().getResponse().getContentAsString()).get("secret").asText();
        assertThat(jdbc.queryForObject("SELECT signing_secret_enc FROM webhook_triggers WHERE id = ?", String.class, webhookId))
                .isNotNull().doesNotContain(secret);

        mockMvc.perform(post("/api/v1/webhook/trigger/{token}", token).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        mockMvc.perform(post("/api/v1/webhook/trigger/{token}", token).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", sign("wrong-secret", BODY)).content(BODY))
                .andExpect(status().isUnauthorized());
        // valid signature, but the policy still wants a seal
        mockMvc.perform(post("/api/v1/webhook/trigger/{token}", token).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", sign(secret, BODY)).content(BODY))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/workflows/{id}/seal", workflowId)).andExpect(status().isOk());
        String runId = objectMapper.readTree(mockMvc.perform(post("/api/v1/webhook/trigger/{token}", token)
                        .contentType(MediaType.APPLICATION_JSON).header("X-Signature", sign(secret, BODY)).content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        WorkflowRun run = awaitTerminal(runId);
        assertThat(run.getTriggerSource()).isEqualTo(TriggerSource.WEBHOOK);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(runRepository.countByWorkflowId(workflowId)).isEqualTo(1);
    }

    private WorkflowRun awaitTerminal(String runId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            WorkflowRun run = runRepository.findById(runId).orElseThrow();
            if (TERMINAL.contains(run.getStatus())) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Run " + runId + " did not finish in time");
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }
}
