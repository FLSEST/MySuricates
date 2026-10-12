package com.automation.integration;

import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import com.automation.entity.GoalProject;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.metrics.SqlStatementCounter;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code GET /api/v1/goals} costs the same number of SQL statements for one goal as for many
 * (audit 2026-10-07: the production median rose from 1.9 to 4.0 statements per call, suspected
 * N+1). Every goal here carries everything a list row shows — todos (progress), an assigned
 * project, a milestone (next milestone) and a guard workflow with a schedule (guard) — so a lookup
 * per goal for any of them shows up as growth.
 *
 * <p>{@code RequestCostRatchetTest} measures the route at one and two goals, but its seed has
 * milestones on the first goal only and no guard; this test covers every per-row part at 1 and 5.
 * Measured like the ratchet: committed rows, no surrounding transaction (OSIV is off), and the
 * route filtered to this test's project so rows left behind by other tests of the JVM do not
 * count.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class GoalListStatementCountIntegrationTest {

    /**
     * goals, todo counts per goal, assigned projects, milestone summaries, guard schedules — one
     * query each for the whole list ({@code GoalService.list}).
     */
    private static final int LIST_STATEMENTS = 5;

    private static final Instant DUE = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactions;
    @Autowired private NamedParameterJdbcTemplate jdbc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ScheduledWorkflowRepository scheduleRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private GoalProjectRepository goalProjectRepository;
    @Autowired private GoalMilestoneRepository milestoneRepository;
    @Autowired private TodoRepository todoRepository;

    private Project project;
    private final List<String> goalIds = new ArrayList<>();
    private final List<String> workflowIds = new ArrayList<>();

    @BeforeEach
    void createProject() {
        long nano = System.nanoTime();
        project = transactions.execute(status -> projectRepository.save(Project.builder()
                .name("goal-list-cost-" + nano).type(ProjectType.CODE)
                .path("/tmp/goal-list-cost-" + nano).status(ProjectStatus.STOPPED).build()));
    }

    @AfterEach
    void removeRows() {
        Map<String, Object> ids = Map.of("goals", goalIds.isEmpty() ? List.of("-") : goalIds,
                "workflows", workflowIds.isEmpty() ? List.of("-") : workflowIds, "project", project.getId());
        jdbc.update("DELETE FROM todos WHERE goal_id IN (:goals)", ids);
        jdbc.update("DELETE FROM goal_milestones WHERE goal_id IN (:goals)", ids);
        jdbc.update("DELETE FROM goal_projects WHERE goal_id IN (:goals)", ids);
        jdbc.update("DELETE FROM goals WHERE id IN (:goals)", ids);
        jdbc.update("DELETE FROM scheduled_workflows WHERE workflow_id IN (:workflows)", ids);
        jdbc.update("DELETE FROM workflows WHERE id IN (:workflows)", ids);
        jdbc.update("DELETE FROM projects WHERE id = :project", ids);
    }

    @Test
    void statementCountDoesNotGrowWithTheNumberOfGoals() throws Exception {
        addGoals(1);
        Measurement one = measure();

        addGoals(4);
        Measurement five = measure();

        assertThat(one.goals()).as("goals listed after the first batch").isEqualTo(1);
        assertThat(five.goals()).as("goals listed after the second batch").isEqualTo(5);
        assertThat(five.statements())
                .as("GET /goals with 5 goals must cost what it costs with 1 — a per-goal lookup grows")
                .isEqualTo(one.statements());
        assertThat(one.statements()).as("one query per list part, see LIST_STATEMENTS").isEqualTo(LIST_STATEMENTS);
    }

    /** Each goal: three todos, the test's project, one milestone and its own guard with a schedule. */
    private void addGoals(int count) {
        transactions.executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                int n = goalIds.size();
                Workflow guard = workflowRepository.save(Workflow.builder()
                        .name("goal-list-guard-" + project.getId() + "-" + n).project(project).build());
                workflowIds.add(guard.getId());
                scheduleRepository.save(ScheduledWorkflow.builder()
                        .workflow(guard)
                        .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                        .cronExpression("0 0 6 * * MON")
                        .scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                        .enabled(true)
                        .nextRunAt(DUE)
                        .inputContext(Map.of())
                        .build());

                String goalId = goalRepository.save(Goal.builder().title("Goal list cost " + n)
                        .guardWorkflowId(guard.getId()).build()).getId();
                goalIds.add(goalId);
                goalProjectRepository.save(new GoalProject(goalId, project.getId(), 0));
                milestoneRepository.save(GoalMilestone.builder().goalId(goalId)
                        .title("Milestone " + n).dueDate(DUE).build());
                for (int t = 0; t < 3; t++) {
                    todoRepository.save(Todo.builder().title("Goal list todo " + n + "-" + t)
                            .status(t == 0 ? Todo.Status.DONE : Todo.Status.OPEN)
                            .priority(Todo.Priority.MEDIUM).goalId(goalId).projectId(project.getId()).build());
                }
            }
        });
    }

    private record Measurement(int statements, int goals) {}

    private Measurement measure() throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("measured inside an open transaction — production has none around the request").isFalse();

        SqlStatementCounter.start();
        MvcResult result;
        int statements;
        try {
            result = mockMvc.perform(get("/api/v1/goals")
                    .param("status", "ACTIVE").param("projectId", project.getId())).andReturn();
            statements = SqlStatementCounter.count();
        } finally {
            SqlStatementCounter.stop();
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        JsonNode goals = objectMapper.readTree(result.getResponse().getContentAsString());
        // Every row must really carry what the seed gave it, otherwise the count measures less.
        for (JsonNode goal : goals) {
            assertThat(goal.at("/progress/total").asInt()).as("todos of %s", goal.get("id")).isEqualTo(3);
            assertThat(goal.at("/projects/0/id").asText()).isEqualTo(project.getId());
            assertThat(goal.at("/nextMilestone/title").asText()).startsWith("Milestone ");
            assertThat(goal.at("/guard/scheduleId").isTextual()).as("guard schedule of %s", goal.get("id")).isTrue();
        }
        return new Measurement(statements, goals.size());
    }
}
