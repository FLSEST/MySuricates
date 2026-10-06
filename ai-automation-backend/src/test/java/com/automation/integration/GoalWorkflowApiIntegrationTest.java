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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Workflows on a goal or work package via the real API against the real database (TODO 52bc1e8c goals
 * stage 3: show, start and use workflows on a goal or sub-goal as a status): attach, change
 * role, detach, 400/404/409, soft-deleted workflows, cascades, the guard as first entry and
 * the start via the existing path with {@code goal_id} in the context – without AI, one shell block.
 *
 * <p>Without a test transaction: the run runs in its own thread and must see the data. Therefore
 * {@link #tearDown} cleans up itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class GoalWorkflowApiIntegrationTest {

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
    private final List<String> workflowIds = new ArrayList<>();
    private final List<String> goalIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
        projectId = projectRepository.save(Project.builder().name("Goal workflows " + System.nanoTime())
                .type(ProjectType.CODE).path(tempDir.toString()).status(ProjectStatus.STOPPED).build()).getId();
    }

    @AfterEach
    void tearDown() {
        for (String goalId : goalIds) {
            jdbc.queryForList("SELECT guard_workflow_id FROM goals WHERE id = ? AND guard_workflow_id IS NOT NULL",
                    String.class, goalId).forEach(workflowIds::add);
            jdbc.update("DELETE FROM goals WHERE id = ?", goalId);
        }
        for (String workflowId : workflowIds) {
            jdbc.update("DELETE FROM scheduled_workflows WHERE workflow_id = ?", workflowId);
            jdbc.update("DELETE FROM step_results WHERE workflow_run_id IN (SELECT id FROM workflow_runs WHERE workflow_id = ?)", workflowId);
            jdbc.update("DELETE FROM workflow_runs WHERE workflow_id = ?", workflowId);
            jdbc.update("DELETE FROM workflow_versions WHERE workflow_id = ?", workflowId);
            jdbc.update("DELETE FROM activity_log WHERE workflow_id = ?", workflowId);
            jdbc.update("DELETE FROM workflows WHERE id = ?", workflowId);
        }
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    @Test
    void workflowsAreAttachedToGoalAndPackage_listedInOrder_roleChanged_andDetached() throws Exception {
        String goalId = createGoal("Release");
        String docs = createWorkPackage(goalId, "Doku");
        String site = createWorkPackage(goalId, "Site");
        String report = workflow("Stand melden");
        String builder = workflow("Aufbauen");
        String alpha = workflow("Alpha");

        JsonNode goalLevel = json(perform(post("/api/v1/goals/" + goalId + "/workflows"),
                Map.of("workflowId", report, "role", "STATUS")).andExpect(status().isCreated()));
        assertThat(goalLevel.get("workPackageId").isNull()).isTrue();
        assertThat(goalLevel.get("role").asText()).isEqualTo("STATUS");
        assertThat(goalLevel.get("guard").asBoolean()).isFalse();
        assertThat(goalLevel.get("workflowName").asText()).isEqualTo("Stand melden");
        assertThat(goalLevel.get("workflowProjectId").asText()).isEqualTo(projectId);
        JsonNode onSite = json(perform(post("/api/v1/goals/" + goalId + "/workflows"),
                Map.of("workflowId", builder, "workPackageId", site)).andExpect(status().isCreated()));
        assertThat(onSite.get("role").asText()).as("Standard").isEqualTo("WORKER");
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", alpha, "workPackageId", docs))
                .andExpect(status().isCreated());
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", builder, "workPackageId", docs))
                .andExpect(status().isCreated());
        // the same workflow on the goal is a separate assignment
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", builder))
                .andExpect(status().isCreated());

        // Duplikat 409, fremdes Paket 400, unbekannter Workflow 404, ungueltige Rolle 400
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", builder, "workPackageId", site))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", report))
                .andExpect(status().isConflict());
        String otherGoal = createGoal("Anderes");
        String foreign = createWorkPackage(otherGoal, "Fremd");
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", report, "workPackageId", foreign))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", "missing"))
                .andExpect(status().isNotFound());
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", alpha, "role", "BOSS"))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/goals/missing/workflows"), Map.of("workflowId", report)).andExpect(status().isNotFound());

        JsonNode list = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/workflows")).andExpect(status().isOk()));
        List<String> order = new ArrayList<>();
        list.forEach(node -> order.add((node.get("workPackageId").isNull() ? "Ziel" : node.get("workPackageId").asText()
                .equals(docs) ? "Doku" : "Site") + ":" + node.get("workflowName").asText()));
        assertThat(order).as("Ziel-Ebene zuerst, dann Paket-Reihenfolge, dann Name")
                .containsExactly("Ziel:Aufbauen", "Ziel:Stand melden", "Doku:Alpha", "Doku:Aufbauen", "Site:Aufbauen");

        String siteAttachment = onSite.get("id").asText();
        perform(patch("/api/v1/goals/" + goalId + "/workflows/" + siteAttachment), Map.of("role", "STATUS"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("STATUS"));
        perform(patch("/api/v1/goals/" + goalId + "/workflows/" + siteAttachment), Map.of("role", "x"))
                .andExpect(status().isBadRequest());
        perform(patch("/api/v1/goals/" + otherGoal + "/workflows/" + siteAttachment), Map.of("role", "WORKER"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/workflows/guard")).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/workflows/" + siteAttachment)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/workflows/" + siteAttachment)).andExpect(status().isNotFound());
        assertThat(workflowRepository.findById(builder)).as("der Workflow bleibt").isPresent();

        // soft-deleted workflow: invisible, the assignment stays in the table
        mockMvc.perform(delete("/api/v1/workflows/" + alpha)).andExpect(status().isNoContent());
        list = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/workflows")));
        assertThat(list.findValuesAsText("workflowId")).doesNotContain(alpha);
        assertThat(count("SELECT COUNT(*) FROM goal_workflows WHERE workflow_id = ?", alpha)).isEqualTo(1);
        // a deleted workflow is unknown
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", alpha, "workPackageId", site))
                .andExpect(status().isNotFound());

        // Kaskaden: Paket loeschen nimmt seine Zuordnungen mit, Ziel loeschen alle
        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/work-packages/" + docs)).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM goal_workflows WHERE work_package_id = ?", docs)).isZero();
        assertThat(count("SELECT COUNT(*) FROM goal_workflows WHERE goal_id = ?", goalId)).isEqualTo(2);
        mockMvc.perform(delete("/api/v1/goals/" + goalId)).andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM goal_workflows WHERE goal_id = ?", goalId)).isZero();
    }

    @Test
    void theGuardIsTheFirstEntry_notDetachable_andAWorkflowStartsWithTheGoalInItsContext() throws Exception {
        String goalId = createGoal("Mit Waechter");
        String worker = workflow("Arbeiter");
        perform(post("/api/v1/goals/" + goalId + "/workflows"), Map.of("workflowId", worker)).andExpect(status().isCreated());
        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/workflows"))).findValuesAsText("id"))
                .as("ohne Waechter kein Waechter-Eintrag").doesNotContain("guard");

        perform(post("/api/v1/goals/" + goalId + "/guard"), Map.of("enabled", true, "locale", "de")).andExpect(status().isOk());
        JsonNode list = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/workflows")).andExpect(status().isOk()));
        assertThat(list.get(0).get("id").asText()).isEqualTo("guard");
        assertThat(list.get(0).get("guard").asBoolean()).isTrue();
        assertThat(list.get(0).get("role").asText()).isEqualTo("STATUS");
        assertThat(list.get(0).get("workflowName").asText()).startsWith("Ziel-Wächter");
        assertThat(list.get(1).get("workflowId").asText()).isEqualTo(worker);
        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/workflows/guard")).andExpect(status().isBadRequest());
        perform(patch("/api/v1/goals/" + goalId + "/workflows/guard"), Map.of("role", "WORKER")).andExpect(status().isBadRequest());

        perform(post("/api/v1/goals/" + goalId + "/guard"), Map.of("enabled", false)).andExpect(status().isOk());
        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/workflows"))).findValuesAsText("id"))
                .as("ausgeschaltet: kein Eintrag").doesNotContain("guard");

        // Start via the existing path, with the goal in the context
        String runId = json(perform(post("/api/v1/workflows/" + worker + "/execute"),
                Map.of("context", Map.of("goal_id", goalId, "goal_locale", "de"))).andExpect(status().isOk()))
                .get("id").asText();
        WorkflowRun run = awaitTerminal(runId);
        assertThat(run.getTriggerSource()).isEqualTo(TriggerSource.MANUAL);
        assertThat(run.getInputContext()).containsEntry("goal_id", goalId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    private String workflow(String name) {
        Project project = projectRepository.findById(projectId).orElseThrow();
        BlockDefinition echo = BlockDefinition.builder().type("shell")
                .config(new HashMap<>(Map.of("command", "echo goal-ok", "timeout_seconds", 5))).build();
        String id = workflowRepository.save(Workflow.builder().name(name).project(project)
                .blocks(new ArrayList<>(List.of(echo))).build()).getId();
        workflowIds.add(id);
        return id;
    }

    private String createGoal(String title) throws Exception {
        String id = json(perform(post("/api/v1/goals"), Map.of("title", title, "projectIds", List.of(projectId)))
                .andExpect(status().isCreated())).get("id").asText();
        goalIds.add(id);
        return id;
    }

    private String createWorkPackage(String goalId, String title) throws Exception {
        return json(perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", title))
                .andExpect(status().isCreated())).get("id").asText();
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

    private long count(String sql, String arg) {
        return jdbc.queryForObject(sql, Long.class, arg);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
