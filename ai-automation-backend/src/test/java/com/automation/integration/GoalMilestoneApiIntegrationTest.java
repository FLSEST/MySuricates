package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Meilensteine, Faelligkeit im Ziel, Zuordnen ueber {@code planning}, „Braucht dich“ und der
 * Ziel-Waechter ueber die echte API gegen die echte Datenbank (TODO 259f4f66). Neben
 * {@link GoalApiIntegrationTest}, der die Ziele selbst abdeckt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class GoalMilestoneApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private GoalMilestoneRepository milestoneRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowVersionRepository versionRepository;
    @Autowired private ScheduledWorkflowRepository scheduleRepository;
    @Autowired private EntityManager entityManager;

    private Project projectA;
    private Project projectB;

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        projectA = projectRepository.save(Project.builder().name("ms-a-" + nano).type(ProjectType.CODE)
                .path("/tmp/ms-a-" + nano).status(ProjectStatus.STOPPED).build());
        projectB = projectRepository.save(Project.builder().name("ms-b-" + nano).type(ProjectType.CODE)
                .path("/tmp/ms-b-" + nano).status(ProjectStatus.STOPPED).build());
    }

    @Test
    void milestones_areCreatedListedUpdatedAndDeleted_andCarryStateAndCounts() throws Exception {
        String goalId = createGoal("Launch");
        Instant soon = Instant.now().plus(2, ChronoUnit.DAYS);

        // ein Ziel ohne Projekte: das Teilziel braucht eine projectId (TODO c60bf914)
        perform(post("/api/v1/goals/" + goalId + "/milestones"), Map.of("title", "Ohne Projekt", "dueDate", "2026-10-01"))
                .andExpect(status().isBadRequest());
        JsonNode beta = json(perform(post("/api/v1/goals/" + goalId + "/milestones"),
                Map.of("title", "Beta", "dueDate", soon.toString(), "projectId", projectA.getId())).andExpect(status().isCreated()));
        assertThat(beta.get("state").asText()).isEqualTo("DUE_SOON");
        assertThat(beta.get("total").asInt()).isZero();
        assertThat(beta.get("reopened").asBoolean()).isFalse();
        assertThat(beta.get("projectId").asText()).isEqualTo(projectA.getId());
        assertThat(beta.get("position").asInt()).isZero();
        assertThat(beta.get("openQuestions").asInt()).isZero();
        JsonNode alpha = json(perform(post("/api/v1/goals/" + goalId + "/milestones"),
                Map.of("title", "Alpha", "dueDate", "2020-01-01")).andExpect(status().isCreated()));
        assertThat(alpha.get("dueDate").asText()).isEqualTo("2020-01-01T23:59:59Z");
        assertThat(alpha.get("state").asText()).isEqualTo("OVERDUE");
        assertThat(alpha.get("projectId").asText()).as("das einzige Projekt des Ziels").isEqualTo(projectA.getId());
        assertThat(alpha.get("position").asInt()).isEqualTo(1);

        JsonNode list = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/milestones")).andExpect(status().isOk()));
        assertThat(list.findValuesAsText("title")).as("nach Position, nicht nach Datum").containsExactly("Beta", "Alpha");

        perform(put("/api/v1/goals/" + goalId + "/milestones/" + alpha.get("id").asText()),
                Map.of("title", "Alpha 2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Alpha 2"))
                .andExpect(jsonPath("$.dueDate").value("2020-01-01T23:59:59Z"));

        perform(post("/api/v1/goals/" + goalId + "/milestones"), Map.of("title", " ", "dueDate", "2026-10-01"))
                .andExpect(status().isBadRequest());
        JsonNode undated = json(perform(post("/api/v1/goals/" + goalId + "/milestones"), Map.of("title", "Ohne Datum"))
                .andExpect(status().isCreated()));
        assertThat(undated.get("dueDate").isNull()).isTrue();
        assertThat(undated.get("state").asText()).isEqualTo("OPEN");
        perform(post("/api/v1/goals/missing/milestones"), Map.of("title", "X", "dueDate", "2026-10-01"))
                .andExpect(status().isNotFound());
        String other = createGoal("Anderes Ziel");
        perform(put("/api/v1/goals/" + other + "/milestones/" + beta.get("id").asText()), Map.of("title", "fremd"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/milestones/" + beta.get("id").asText()))
                .andExpect(status().isNoContent());
        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/milestones"))).findValuesAsText("title"))
                .containsExactly("Alpha 2", "Ohne Datum");
    }

    @Test
    void planning_assignsAMilestoneOfTheSameGoal_andReachedIsRememberedAfterReopening() throws Exception {
        String goalId = createGoal("Launch");
        String milestoneId = createMilestone(goalId, "Beta", Instant.now().plus(10, ChronoUnit.DAYS));
        Instant due = Instant.parse("2026-10-03T21:59:59Z");
        Todo first = todo("Erstes", projectA, due);
        Todo second = todo("Zweites", projectA, null);
        assign(goalId, first, second);

        perform(patch("/api/v1/todos/" + first.getId() + "/planning"), Map.of("milestoneId", milestoneId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.milestoneId").value(milestoneId));
        perform(patch("/api/v1/todos/" + second.getId() + "/planning"), Map.of("milestoneId", milestoneId))
                .andExpect(status().isOk());

        JsonNode detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isOk()));
        JsonNode firstItem = item(detail, first.getId());
        assertThat(firstItem.get("dueDate").asText()).isEqualTo(due.toString());
        assertThat(firstItem.get("milestoneId").asText()).isEqualTo(milestoneId);
        assertThat(detail.at("/milestones/0/total").asInt()).isEqualTo(2);
        assertThat(detail.at("/milestones/0/state").asText()).isEqualTo("OPEN");

        setStatus(first, "DONE");
        setStatus(second, "DONE");
        detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(detail.at("/milestones/0/state").asText()).isEqualTo("REACHED");
        String reachedAt = detail.at("/milestones/0/reachedAt").asText();
        assertThat(reachedAt).isNotEmpty();
        entityManager.flush();
        entityManager.clear();
        assertThat(milestoneRepository.findById(milestoneId).orElseThrow().getReachedAt())
                .as("beim ersten Erkennen gespeichert").isNotNull();

        Todo late = todo("Nachzuegler", projectA, null);
        assign(goalId, late);
        perform(patch("/api/v1/todos/" + late.getId() + "/planning"), Map.of("milestoneId", milestoneId))
                .andExpect(status().isOk());
        detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(detail.at("/milestones/0/state").asText()).isEqualTo("OPEN");
        assertThat(detail.at("/milestones/0/reopened").asBoolean()).isTrue();
        assertThat(detail.at("/milestones/0/reachedAt").asText()).isEqualTo(reachedAt);

        perform(patch("/api/v1/todos/" + late.getId() + "/planning"), Map.of("milestoneId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.milestoneId").doesNotExist());
    }

    @Test
    void planning_refusesAForeignMilestone_andLeavingTheGoalClearsIt() throws Exception {
        String goalId = createGoal("Launch");
        String otherGoal = createGoal("Anderes");
        String mine = createMilestone(goalId, "Meiner", Instant.now().plus(5, ChronoUnit.DAYS));
        String foreign = createMilestone(otherGoal, "Fremder", Instant.now().plus(5, ChronoUnit.DAYS), projectB);
        String mineForB = createMilestone(goalId, "Meiner fuer B", null, projectB);
        Todo todo = todo("Aufgabe", projectA, null);
        Todo loose = todo("Ohne Ziel", projectB, null);
        assign(goalId, todo);

        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("milestoneId", foreign))
                .andExpect(status().isBadRequest());
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("milestoneId", mine))
                .andExpect(status().isBadRequest());
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("milestoneId", "missing"))
                .andExpect(status().isNotFound());

        // Ziel und Meilenstein in einem Schritt: das Ziel zuerst, dann der Meilenstein.
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("goalId", otherGoal, "milestoneId", foreign))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.milestoneId").value(foreign));

        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("milestoneId", mine)).andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("goalId", otherGoal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.milestoneId").doesNotExist());

        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("milestoneId", foreign)).andExpect(status().isOk());
        assign(goalId, loose);
        assertThat(item(json(mockMvc.perform(get("/api/v1/goals/" + goalId))), loose.getId()).get("milestoneId").isNull())
                .as("Zuordnen zu einem anderen Ziel loest den Meilenstein").isTrue();

        // Teilziel eines anderen Projekts (TODO c60bf914)
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("milestoneId", mine)).andExpect(status().isBadRequest());
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("milestoneId", mineForB)).andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("goalId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning").doesNotExist());
    }

    @Test
    void deletingAMilestoneOrTheGoal_releasesTheTodos() throws Exception {
        String goalId = createGoal("Launch");
        String first = createMilestone(goalId, "Eins", Instant.now().plus(5, ChronoUnit.DAYS));
        String second = createMilestone(goalId, "Zwei", Instant.now().plus(6, ChronoUnit.DAYS));
        Todo a = todo("A", projectA, null);
        Todo b = todo("B", projectA, null);
        assign(goalId, a, b);
        perform(patch("/api/v1/todos/" + a.getId() + "/planning"), Map.of("milestoneId", first)).andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + b.getId() + "/planning"), Map.of("milestoneId", second)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/milestones/" + first)).andExpect(status().isNoContent());
        entityManager.clear();
        assertThat(todoRepository.findById(a.getId()).orElseThrow().getMilestoneId()).isNull();
        assertThat(todoRepository.findById(a.getId()).orElseThrow().getGoalId()).isEqualTo(goalId);

        mockMvc.perform(delete("/api/v1/goals/" + goalId)).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        Todo reloaded = todoRepository.findById(b.getId()).orElseThrow();
        assertThat(reloaded.getGoalId()).isNull();
        assertThat(reloaded.getMilestoneId()).isNull();
        assertThat(milestoneRepository.findById(second)).isEmpty();
    }

    @Test
    void needsYou_listsDueSoonAndOverdueMilestones_withTheirGoal() throws Exception {
        String goalId = createGoal("Launch " + System.nanoTime());
        String overdue = createMilestone(goalId, "Ueberfaellig", Instant.now().minus(1, ChronoUnit.DAYS));
        String soon = createMilestone(goalId, "Bald", Instant.now().plus(1, ChronoUnit.DAYS));
        String later = createMilestone(goalId, "Spaeter", Instant.now().plus(20, ChronoUnit.DAYS));
        String undated = createMilestone(goalId, "Irgendwann", null);

        JsonNode overview = json(mockMvc.perform(get("/api/v1/todos/overview")).andExpect(status().isOk()));
        Map<String, JsonNode> byId = new HashMap<>();
        overview.get("milestonesDue").forEach(node -> byId.put(node.get("id").asText(), node));

        assertThat(byId).containsKeys(overdue, soon).doesNotContainKeys(later, undated);
        assertThat(byId.get(overdue).get("state").asText()).isEqualTo("OVERDUE");
        assertThat(byId.get(overdue).get("goalId").asText()).isEqualTo(goalId);
        assertThat(byId.get(overdue).get("goalTitle").asText()).startsWith("Launch");
        assertThat(byId.get(soon).get("state").asText()).isEqualTo("DUE_SOON");
    }

    @Test
    void guard_switchOnCreatesWorkflowAndDailySchedule_switchOffPauses_switchOnAgainReuses() throws Exception {
        String goalId = createGoal("Waechter-Ziel");
        Todo todo = todo("Aufgabe", projectB, null);
        assign(goalId, todo);

        JsonNode on = json(perform(post("/api/v1/goals/" + goalId + "/guard"), Map.of("enabled", true, "locale", "de"))
                .andExpect(status().isOk()));
        assertThat(on.at("/guard/enabled").asBoolean()).isTrue();
        String workflowId = on.at("/guard/workflowId").asText();
        String scheduleId = on.at("/guard/scheduleId").asText();
        assertThat(on.at("/guard/nextRunAt").asText()).isNotEmpty();

        Workflow workflow = workflowRepository.findById(workflowId).orElseThrow();
        assertThat(workflow.getName()).isEqualTo("Ziel-Wächter: Waechter-Ziel");
        assertThat(workflow.getTemplateId()).isEqualTo("goal-guard");
        assertThat(workflow.getProject().getId()).as("Projekt mit den meisten TODOs").isEqualTo(projectB.getId());
        assertThat(workflow.getDefaultContext()).containsEntry("goal_id", goalId);
        assertThat(workflow.getModel()).isNull();
        assertThat(versionRepository.findTopByWorkflowIdOrderByVersionNumberDesc(workflowId).orElseThrow().getChangeSource())
                .isEqualTo("goal-guard");
        ScheduledWorkflow schedule = scheduleRepository.findById(scheduleId).orElseThrow();
        assertThat(schedule.getCronExpression()).isEqualTo("0 0 8 * * *");
        assertThat(schedule.getInputContext()).containsEntry("goal_id", goalId);
        assertThat(schedule.getConflictBehavior()).isEqualTo(ScheduledWorkflow.ConflictBehavior.SKIP);

        JsonNode off = json(perform(post("/api/v1/goals/" + goalId + "/guard"), Map.of("enabled", false))
                .andExpect(status().isOk()));
        assertThat(off.at("/guard/enabled").asBoolean()).isFalse();
        assertThat(off.at("/guard/workflowId").asText()).isEqualTo(workflowId);
        assertThat(workflowRepository.findById(workflowId)).as("der Workflow bleibt").isPresent();

        JsonNode again = json(perform(post("/api/v1/goals/" + goalId + "/guard"), Map.of("enabled", true))
                .andExpect(status().isOk()));
        assertThat(again.at("/guard/enabled").asBoolean()).isTrue();
        assertThat(again.at("/guard/workflowId").asText()).isEqualTo(workflowId);
        assertThat(again.at("/guard/scheduleId").asText()).isEqualTo(scheduleId);
        assertThat(scheduleRepository.findByWorkflowId(workflowId)).hasSize(1);

        JsonNode listed = null;
        for (JsonNode goal : json(mockMvc.perform(get("/api/v1/goals")))) {
            if (goalId.equals(goal.get("id").asText())) listed = goal;
        }
        assertThat(listed).isNotNull();
        assertThat(listed.at("/guard/enabled").asBoolean()).isTrue();
        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + goalId))).at("/goal/guard/scheduleId").asText())
                .isEqualTo(scheduleId);

        perform(post("/api/v1/goals/" + goalId + "/guard"), Map.of("enabled", true, "cron", "irgendwann"))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/goals/missing/guard"), Map.of("enabled", true)).andExpect(status().isNotFound());
    }

    // ── TODO c60bf914 (Ziele Stufe 1: Ziel → Projekte → Teilziele → TODOs) ──────────────────

    @Test
    void milestones_areOrderedPerProject_datesCanBeRemoved_andAProjectChangeNeedsAnEmptyMilestone() throws Exception {
        String goalId = createGoal("Release");
        perform(post("/api/v1/goals/" + goalId + "/projects"), Map.of("projectId", projectB.getId())).andExpect(status().isOk());
        String a1 = createMilestone(goalId, "A1", Instant.now().plus(9, ChronoUnit.DAYS), projectA);
        String a2 = createMilestone(goalId, "A2", null, projectA);
        String a3 = createMilestone(goalId, "A3", Instant.now().plus(1, ChronoUnit.DAYS), projectA);
        String b1 = createMilestone(goalId, "B1", null, projectB);
        // zwei Zielprojekte: projectId ist Pflicht
        perform(post("/api/v1/goals/" + goalId + "/milestones"), Map.of("title", "Welches Projekt?"))
                .andExpect(status().isBadRequest());

        JsonNode detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(detail.get("projects").findValuesAsText("projectId"))
                .as("B kam zuerst ans Ziel, A mit dem ersten Teilziel").containsExactly(projectB.getId(), projectA.getId());
        assertThat(detail.get("milestones").findValuesAsText("title")).containsExactly("B1", "A1", "A2", "A3");

        JsonNode reordered = json(perform(put("/api/v1/goals/" + goalId + "/milestones/order"), Map.of("ids", List.of(a3, a1)))
                .andExpect(status().isOk()));
        assertThat(reordered.findValuesAsText("title")).containsExactly("B1", "A3", "A1", "A2");
        assertThat(reordered.findValuesAsText("position")).containsExactly("0", "0", "1", "2");
        perform(put("/api/v1/goals/" + goalId + "/milestones/order"), Map.of("ids", List.of(a1, b1)))
                .andExpect(status().isBadRequest());
        perform(put("/api/v1/goals/" + goalId + "/milestones/order"), Map.of("ids", List.of("missing")))
                .andExpect(status().isNotFound());

        perform(put("/api/v1/goals/" + goalId + "/milestones/" + a1), Map.of("dueDate", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDate").doesNotExist())
                .andExpect(jsonPath("$.state").value("OPEN"));

        Todo todo = todo("Aufgabe", projectA, null);
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("goalId", goalId, "milestoneId", a1))
                .andExpect(status().isOk());
        perform(put("/api/v1/goals/" + goalId + "/milestones/" + a1), Map.of("projectId", projectB.getId()))
                .andExpect(status().isBadRequest());
        perform(put("/api/v1/goals/" + goalId + "/milestones/" + a2), Map.of("projectId", projectB.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectB.getId()))
                .andExpect(jsonPath("$.position").value(1));
    }

    @Test
    void guard_neverSwitchedOn_isReportedAsOff() throws Exception {
        String goalId = createGoal("Ohne Waechter");

        mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.goal.guard.enabled").value(false))
                .andExpect(jsonPath("$.goal.guard.workflowId").doesNotExist());
    }

    private String createGoal(String title) throws Exception {
        return json(perform(post("/api/v1/goals"), Map.of("title", title)).andExpect(status().isCreated())).get("id").asText();
    }

    private String createMilestone(String goalId, String title, Instant due) throws Exception {
        return createMilestone(goalId, title, due, projectA);
    }

    /** Teilziel im Projekt; ein Projekt, das dem Ziel noch fehlt, kommt dabei dazu. {@code due} null = ohne Datum. */
    private String createMilestone(String goalId, String title, Instant due, Project project) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", title, "projectId", project.getId()));
        if (due != null) body.put("dueDate", due.toString());
        return json(perform(post("/api/v1/goals/" + goalId + "/milestones"), body)
                .andExpect(status().isCreated())).get("id").asText();
    }

    private void assign(String goalId, Todo... todos) throws Exception {
        perform(post("/api/v1/goals/" + goalId + "/todos"),
                Map.of("todoIds", java.util.Arrays.stream(todos).map(Todo::getId).toList())).andExpect(status().isOk());
    }

    private void setStatus(Todo todo, String status) throws Exception {
        perform(patch("/api/v1/todos/" + todo.getId() + "/status"), Map.of("status", status)).andExpect(status().isOk());
    }

    private Todo todo(String title, Project project, Instant due) {
        return todoRepository.save(Todo.builder().title(title).priority(Todo.Priority.MEDIUM)
                .projectId(project.getId()).dueDate(due).build());
    }

    private static JsonNode item(JsonNode detail, String todoId) {
        for (JsonNode node : detail.get("todos")) {
            if (todoId.equals(node.get("id").asText())) return node;
        }
        throw new AssertionError("TODO " + todoId + " fehlt im Ziel");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
