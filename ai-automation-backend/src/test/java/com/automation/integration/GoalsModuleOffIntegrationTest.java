package com.automation.integration;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.project.ContainerService;
import com.automation.service.scheduler.ScheduledWorkflowExecutor;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * The module {@code goals} switched off (TODO c60bf914 goals stage 1: goal → projects → work packages →
 * TODOs, milestones, outline and timeline): goal endpoints and planning blocked, TODO list,
 * detail and cockpit without goal data and without errors, the {@code goalId} filter a 400, a
 * goal-bound schedule (the goal guard) skips, a run with {@code goal_id} runs through without
 * goal variables, deleting continues to work – and switched on again everything is there.
 *
 * <p>Without a test transaction, because schedule and run have their own transactions and threads;
 * {@link #tearDown} cleans up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {"automation.modules.goals=false", "automation.modules.ui-editable=true"})
class GoalsModuleOffIntegrationTest {

    private static final Set<RunStatus> TERMINAL = Set.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS,
            RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.INTERRUPTED);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ModuleRegistry modules;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private GoalWorkPackageRepository workPackageRepository;
    @Autowired private GoalMilestoneRepository milestoneRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private ScheduledWorkflowRepository scheduleRepository;
    @Autowired private ScheduledWorkflowExecutor scheduledWorkflowExecutor;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private ContainerService containerService;

    @TempDir Path tempDir;

    private String projectId;
    private String goalId;
    private String todoId;
    private String workflowId;

    @BeforeEach
    void setUp() {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
        Project project = projectRepository.save(Project.builder().name("Goals off " + System.nanoTime())
                .type(ProjectType.CODE).path(tempDir.toString()).status(ProjectStatus.STOPPED).build());
        projectId = project.getId();
        goalId = goalRepository.save(Goal.builder().title("Release").build()).getId();
        String workPackageId = workPackageRepository.save(GoalWorkPackage.builder().goalId(goalId).projectId(projectId)
                .title("Doku").startDate(Instant.now().minus(5, ChronoUnit.DAYS)).endDate(Instant.now().minus(1, ChronoUnit.DAYS))
                .build()).getId();
        milestoneRepository.save(GoalMilestone.builder().goalId(goalId).title("Launch")
                .dueDate(Instant.now().plus(1, ChronoUnit.DAYS)).build());
        todoId = todoRepository.save(Todo.builder().title("Im Ziel").priority(Todo.Priority.HIGH).projectId(projectId)
                .goalId(goalId).workPackageId(workPackageId).openQuestion("Welcher Stil?").build()).getId();
        BlockDefinition echo = BlockDefinition.builder().type("shell")
                .config(new HashMap<>(Map.of("command", "echo goals-off", "timeout_seconds", 5))).build();
        workflowId = workflowRepository.save(Workflow.builder().name("Ziel-Waechter-Ersatz").project(project)
                .blocks(new ArrayList<>(List.of(echo))).build()).getId();
    }

    @AfterEach
    void tearDown() {
        modules.applyUiOverrides(Map.of());
        jdbc.update("DELETE FROM activity_log WHERE workflow_id = ? OR reference_id IN (SELECT id FROM scheduled_workflows WHERE workflow_id = ?)",
                workflowId, workflowId);
        jdbc.update("DELETE FROM scheduled_workflows WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM step_results WHERE workflow_run_id IN (SELECT id FROM workflow_runs WHERE workflow_id = ?)", workflowId);
        jdbc.update("DELETE FROM workflow_runs WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM workflow_versions WHERE workflow_id = ?", workflowId);
        jdbc.update("DELETE FROM workflows WHERE id = ?", workflowId);
        jdbc.update("DELETE FROM todos WHERE project_id = ?", projectId);
        jdbc.update("DELETE FROM goals WHERE id = ?", goalId);
        jdbc.update("DELETE FROM projects WHERE id = ?", projectId);
    }

    @Test
    void goalEndpointsAndPlanningAreLocked_todosWorkWithoutGoalData_andSwitchingBackOnShowsEverything() throws Exception {
        assertThat(modules.isEnabled(ModuleCatalog.GOALS)).isFalse();

        mockMvc.perform(get("/api/v1/goals")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/goals/" + goalId + "/workflows")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        mockMvc.perform(patch("/api/v1/todos/" + todoId + "/planning").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"Handgezeichnet\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MODULE_DISABLED"));

        JsonNode list = json(mockMvc.perform(get("/api/v1/todos").param("projectId", projectId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(list.at("/content/0/id").asText()).isEqualTo(todoId);
        assertThat(list.at("/content/0/planning").isNull()).as("keine Planungsdaten im Listeneintrag").isTrue();
        mockMvc.perform(get("/api/v1/todos/" + todoId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.planning").doesNotExist());
        mockMvc.perform(get("/api/v1/todos").param("goalId", goalId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("goals")));
        JsonNode overview = json(mockMvc.perform(get("/api/v1/todos/overview")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(overview.get("milestonesDue")).as("ueberfaelliges Paket und bald faelliger Meilenstein – aber Modul aus").isEmpty();

        JsonNode capabilities = json(mockMvc.perform(get("/api/v1/system/capabilities")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode goals = null;
        for (JsonNode module : capabilities.get("modules")) {
            if ("goals".equals(module.get("name").asText())) goals = module;
        }
        assertThat(goals).as("goals steht in der Modul-Liste").isNotNull();
        assertThat(goals.get("enabled").asBoolean()).isFalse();
        assertThat(goals.get("description").asText()).contains("work packages");

        // back on: nothing is lost
        modules.applyUiOverrides(Map.of(ModuleCatalog.GOALS, true));
        mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.workPackages[0].title").value("Doku"))
                .andExpect(jsonPath("$.milestones[0].title").value("Launch"))
                .andExpect(jsonPath("$.todos[0].openQuestion").value("Welcher Stil?"));
        mockMvc.perform(get("/api/v1/todos/" + todoId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.goalTitle").value("Release"))
                .andExpect(jsonPath("$.planning.decisionState").value("QUESTION"));
        assertThat(json(mockMvc.perform(get("/api/v1/todos/overview")).andReturn().getResponse().getContentAsString())
                .get("milestonesDue")).hasSize(2);
    }

    @Test
    void theGuardScheduleSkips_aRunWithTheGoalCompletesWithoutGoalData_andDeletingStillWorks() throws Exception {
        Workflow workflow = workflowRepository.findById(workflowId).orElseThrow();
        String scheduleId = scheduleRepository.save(ScheduledWorkflow.builder().workflow(workflow)
                .targetType(ScheduledWorkflow.TargetType.WORKFLOW).cronExpression("0 0 8 * * *")
                .scheduleType(ScheduledWorkflow.ScheduleType.CRON).enabled(true)
                .nextRunAt(Instant.now().minus(1, ChronoUnit.MINUTES)).description("Ziel-Waechter")
                .inputContext(Map.of("goal_id", goalId)).build()).getId();

        assertThat(scheduledWorkflowExecutor.executeSingleSchedule(scheduleId)).isEmpty();
        assertThat(runRepository.countByWorkflowId(workflowId)).as("kein Lauf, der ohne Zieldaten scheitern wuerde").isZero();
        ScheduledWorkflow after = scheduleRepository.findById(scheduleId).orElseThrow();
        assertThat(after.getNextRunAt()).as("rueckt weiter statt jeden Tick zu wiederholen").isAfter(Instant.now());
        assertThat(after.isEnabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_log WHERE level = 'ERROR' AND reference_id = ?",
                Integer.class, scheduleId)).isZero();

        String runId = json(mockMvc.perform(post("/api/v1/workflows/" + workflowId + "/execute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":{\"goal_id\":\"" + goalId + "\"}}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        WorkflowRun run = awaitTerminal(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_log WHERE level IN ('WARN','ERROR') AND workflow_id = ?",
                Integer.class, workflowId)).as("keine Luecken-Warnung fuer goal_status").isZero();

        mockMvc.perform(delete("/api/v1/todos/" + todoId)).andExpect(status().isNoContent());
        assertThat(todoRepository.findById(todoId)).isEmpty();
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

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }
}
