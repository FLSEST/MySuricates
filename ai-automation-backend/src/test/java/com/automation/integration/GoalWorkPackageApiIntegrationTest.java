package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.repository.GoalWorkPackageRepository;
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
 * Arbeitspakete (bis V92 „Meilensteine“, TODO 259f4f66), Zuordnen ueber {@code planning}, „Braucht
 * dich“ und der Ziel-Waechter ueber die echte API gegen die echte Datenbank – TODO c60bf914 (Ziele
 * Stufe 1: Ziel → Projekte → Arbeitspakete → TODOs, Meilensteine, Gliederung und Zeitstrahl). Neben
 * {@link GoalApiIntegrationTest} (Ziele selbst) und {@link GoalMilestoneApiIntegrationTest} (Meilensteine).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class GoalWorkPackageApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private GoalWorkPackageRepository workPackageRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowVersionRepository versionRepository;
    @Autowired private ScheduledWorkflowRepository scheduleRepository;
    @Autowired private EntityManager entityManager;

    private Project projectA;
    private Project projectB;

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        projectA = projectRepository.save(Project.builder().name("wp-a-" + nano).type(ProjectType.CODE)
                .path("/tmp/wp-a-" + nano).status(ProjectStatus.STOPPED).build());
        projectB = projectRepository.save(Project.builder().name("wp-b-" + nano).type(ProjectType.CODE)
                .path("/tmp/wp-b-" + nano).status(ProjectStatus.STOPPED).build());
    }

    @Test
    void workPackages_areCreatedListedUpdatedAndDeleted_andCarryStateCountsAndPeriod() throws Exception {
        String goalId = createGoal("Launch");
        Instant soon = Instant.now().plus(2, ChronoUnit.DAYS);

        // ein Ziel ohne Projekte: das Paket braucht eine projectId
        perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", "Ohne Projekt"))
                .andExpect(status().isBadRequest());
        JsonNode beta = json(perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", "Beta",
                "startDate", Instant.now().minus(1, ChronoUnit.DAYS).toString(), "endDate", soon.toString(),
                "projectId", projectA.getId())).andExpect(status().isCreated()));
        assertThat(beta.get("state").asText()).isEqualTo("DUE_SOON");
        assertThat(beta.get("total").asInt()).isZero();
        assertThat(beta.get("reopened").asBoolean()).isFalse();
        assertThat(beta.get("projectId").asText()).isEqualTo(projectA.getId());
        assertThat(beta.get("position").asInt()).isZero();
        assertThat(beta.get("openQuestions").asInt()).isZero();
        assertThat(beta.get("completedAt").isNull()).isTrue();
        JsonNode alpha = json(perform(post("/api/v1/goals/" + goalId + "/work-packages"),
                Map.of("title", "Alpha", "startDate", "2019-12-01", "endDate", "2020-01-01")).andExpect(status().isCreated()));
        assertThat(alpha.get("startDate").asText()).as("ein reines Datum als Start: Beginn des Tages").isEqualTo("2019-12-01T00:00:00Z");
        assertThat(alpha.get("endDate").asText()).as("als Ende: Ende des Tages").isEqualTo("2020-01-01T23:59:59Z");
        assertThat(alpha.get("state").asText()).isEqualTo("OVERDUE");
        assertThat(alpha.get("projectId").asText()).as("das einzige Projekt des Ziels").isEqualTo(projectA.getId());
        assertThat(alpha.get("position").asInt()).isEqualTo(1);

        JsonNode list = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/work-packages")).andExpect(status().isOk()));
        assertThat(list.findValuesAsText("title")).as("nach Position, nicht nach Datum").containsExactly("Beta", "Alpha");

        perform(put("/api/v1/goals/" + goalId + "/work-packages/" + alpha.get("id").asText()),
                Map.of("title", "Alpha 2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Alpha 2"))
                .andExpect(jsonPath("$.endDate").value("2020-01-01T23:59:59Z"));

        perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", " "))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", "Halb", "endDate", "2026-10-01"))
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/goals/" + goalId + "/work-packages"),
                Map.of("title", "Verkehrt", "startDate", "2026-10-05", "endDate", "2026-10-01"))
                .andExpect(status().isBadRequest());
        JsonNode undated = json(perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", "Ohne Zeitraum"))
                .andExpect(status().isCreated()));
        assertThat(undated.get("startDate").isNull()).isTrue();
        assertThat(undated.get("endDate").isNull()).isTrue();
        assertThat(undated.get("state").asText()).isEqualTo("OPEN");
        perform(post("/api/v1/goals/missing/work-packages"), Map.of("title", "X"))
                .andExpect(status().isNotFound());
        String other = createGoal("Anderes Ziel");
        perform(put("/api/v1/goals/" + other + "/work-packages/" + beta.get("id").asText()), Map.of("title", "fremd"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/work-packages/" + beta.get("id").asText()))
                .andExpect(status().isNoContent());
        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/work-packages"))).findValuesAsText("title"))
                .containsExactly("Alpha 2", "Ohne Zeitraum");
    }

    @Test
    void planning_assignsAWorkPackageOfTheSameGoal_andCompletionIsRememberedAfterReopening() throws Exception {
        String goalId = createGoal("Launch");
        String workPackageId = createWorkPackage(goalId, "Beta", Instant.now().plus(10, ChronoUnit.DAYS));
        Instant due = Instant.parse("2026-10-03T21:59:59Z");
        Todo first = todo("Erstes", projectA, due);
        Todo second = todo("Zweites", projectA, null);
        assign(goalId, first, second);

        perform(patch("/api/v1/todos/" + first.getId() + "/planning"), Map.of("workPackageId", workPackageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.workPackageId").value(workPackageId));
        // der alte Feldname wird weiter angenommen
        perform(patch("/api/v1/todos/" + second.getId() + "/planning"), Map.of("milestoneId", workPackageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.workPackageId").value(workPackageId));

        JsonNode detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isOk()));
        JsonNode firstItem = item(detail, first.getId());
        assertThat(firstItem.get("dueDate").asText()).isEqualTo(due.toString());
        assertThat(firstItem.get("workPackageId").asText()).isEqualTo(workPackageId);
        assertThat(firstItem.has("milestoneId")).isFalse();
        assertThat(detail.at("/workPackages/0/total").asInt()).isEqualTo(2);
        assertThat(detail.at("/workPackages/0/state").asText()).isEqualTo("OPEN");
        assertThat(detail.get("milestones")).as("echte Meilensteine gibt es hier keine").isEmpty();

        setStatus(first, "DONE");
        setStatus(second, "DONE");
        detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(detail.at("/workPackages/0/state").asText()).isEqualTo("REACHED");
        String completedAt = detail.at("/workPackages/0/completedAt").asText();
        assertThat(completedAt).isNotEmpty();
        entityManager.flush();
        entityManager.clear();
        assertThat(workPackageRepository.findById(workPackageId).orElseThrow().getCompletedAt())
                .as("beim ersten Erkennen gespeichert").isNotNull();

        Todo late = todo("Nachzuegler", projectA, null);
        assign(goalId, late);
        perform(patch("/api/v1/todos/" + late.getId() + "/planning"), Map.of("workPackageId", workPackageId))
                .andExpect(status().isOk());
        detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(detail.at("/workPackages/0/state").asText()).isEqualTo("OPEN");
        assertThat(detail.at("/workPackages/0/reopened").asBoolean()).isTrue();
        assertThat(detail.at("/workPackages/0/completedAt").asText()).isEqualTo(completedAt);

        perform(patch("/api/v1/todos/" + late.getId() + "/planning"), Map.of("workPackageId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.workPackageId").doesNotExist());
    }

    @Test
    void planning_refusesAForeignWorkPackage_andLeavingTheGoalClearsIt() throws Exception {
        String goalId = createGoal("Launch");
        String otherGoal = createGoal("Anderes");
        String mine = createWorkPackage(goalId, "Meins", Instant.now().plus(5, ChronoUnit.DAYS));
        String foreign = createWorkPackage(otherGoal, "Fremdes", Instant.now().plus(5, ChronoUnit.DAYS), projectB);
        String mineForB = createWorkPackage(goalId, "Meins fuer B", null, projectB);
        Todo todo = todo("Aufgabe", projectA, null);
        Todo loose = todo("Ohne Ziel", projectB, null);
        assign(goalId, todo);

        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("workPackageId", foreign))
                .andExpect(status().isBadRequest());
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("workPackageId", mine))
                .andExpect(status().isBadRequest());
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("workPackageId", "missing"))
                .andExpect(status().isNotFound());

        // Ziel und Paket in einem Schritt: das Ziel zuerst, dann das Paket.
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("goalId", otherGoal, "workPackageId", foreign))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.workPackageId").value(foreign));

        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("workPackageId", mine)).andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("goalId", otherGoal))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.workPackageId").doesNotExist());

        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("workPackageId", foreign)).andExpect(status().isOk());
        assign(goalId, loose);
        assertThat(item(json(mockMvc.perform(get("/api/v1/goals/" + goalId))), loose.getId()).get("workPackageId").isNull())
                .as("Zuordnen zu einem anderen Ziel loest das Paket").isTrue();

        // Paket eines anderen Projekts
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("workPackageId", mine)).andExpect(status().isBadRequest());
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("workPackageId", mineForB)).andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + loose.getId() + "/planning"), Map.of("goalId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning").doesNotExist());
    }

    @Test
    void deletingAWorkPackageOrTheGoal_releasesTheTodos_andTheMilestoneLinks() throws Exception {
        String goalId = createGoal("Launch");
        String first = createWorkPackage(goalId, "Eins", Instant.now().plus(5, ChronoUnit.DAYS));
        String second = createWorkPackage(goalId, "Zwei", Instant.now().plus(6, ChronoUnit.DAYS));
        String milestone = json(perform(post("/api/v1/goals/" + goalId + "/milestones"),
                Map.of("title", "Launch", "dueDate", "2026-12-01", "workPackageIds", List.of(first, second)))
                .andExpect(status().isCreated())).get("id").asText();
        Todo a = todo("A", projectA, null);
        Todo b = todo("B", projectA, null);
        assign(goalId, a, b);
        perform(patch("/api/v1/todos/" + a.getId() + "/planning"), Map.of("workPackageId", first)).andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + b.getId() + "/planning"), Map.of("workPackageId", second)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/work-packages/" + first)).andExpect(status().isNoContent());
        entityManager.clear();
        assertThat(todoRepository.findById(a.getId()).orElseThrow().getWorkPackageId()).isNull();
        assertThat(todoRepository.findById(a.getId()).orElseThrow().getGoalId()).isEqualTo(goalId);
        JsonNode detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(detail.at("/milestones/0/id").asText()).isEqualTo(milestone);
        assertThat(detail.at("/milestones/0/workPackageIds")).extracting(JsonNode::asText).containsExactly(second);

        mockMvc.perform(delete("/api/v1/goals/" + goalId)).andExpect(status().isNoContent());
        entityManager.flush();
        entityManager.clear();
        Todo reloaded = todoRepository.findById(b.getId()).orElseThrow();
        assertThat(reloaded.getGoalId()).isNull();
        assertThat(reloaded.getWorkPackageId()).isNull();
        assertThat(workPackageRepository.findById(second)).isEmpty();
    }

    @Test
    void needsYou_listsOverdueWorkPackagesWithOpenTodos_only() throws Exception {
        String goalId = createGoal("Launch " + System.nanoTime());
        String overdue = createWorkPackage(goalId, "Ueberfaellig", Instant.now().minus(1, ChronoUnit.DAYS));
        String overdueEmpty = createWorkPackage(goalId, "Ueberfaellig, leer", Instant.now().minus(1, ChronoUnit.DAYS));
        String overdueDone = createWorkPackage(goalId, "Ueberfaellig, fertig", Instant.now().minus(1, ChronoUnit.DAYS));
        String soon = createWorkPackage(goalId, "Bald", Instant.now().plus(1, ChronoUnit.DAYS));
        String undated = createWorkPackage(goalId, "Irgendwann", null);
        Todo open = todo("Offen", projectA, null);
        Todo done = todo("Erledigt", projectA, null);
        Todo alsoOpen = todo("Auch offen", projectA, null);
        assign(goalId, open, done, alsoOpen);
        perform(patch("/api/v1/todos/" + open.getId() + "/planning"), Map.of("workPackageId", overdue)).andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + done.getId() + "/planning"), Map.of("workPackageId", overdueDone)).andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + alsoOpen.getId() + "/planning"), Map.of("workPackageId", soon)).andExpect(status().isOk());
        setStatus(done, "DONE");

        JsonNode overview = json(mockMvc.perform(get("/api/v1/todos/overview")).andExpect(status().isOk()));
        Map<String, JsonNode> byId = new HashMap<>();
        overview.get("milestonesDue").forEach(node -> byId.put(node.get("id").asText(), node));

        assertThat(byId).containsKey(overdue).doesNotContainKeys(overdueEmpty, overdueDone, soon, undated);
        assertThat(byId.get(overdue).get("kind").asText()).isEqualTo("WORK_PACKAGE");
        assertThat(byId.get(overdue).get("state").asText()).isEqualTo("OVERDUE");
        assertThat(byId.get(overdue).get("goalId").asText()).isEqualTo(goalId);
        assertThat(byId.get(overdue).get("total").asInt()).isEqualTo(1);
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

    @Test
    void workPackages_areOrderedPerProject_periodsCanBeRemoved_andAProjectChangeNeedsAnEmptyPackage() throws Exception {
        String goalId = createGoal("Release");
        perform(post("/api/v1/goals/" + goalId + "/projects"), Map.of("projectId", projectB.getId())).andExpect(status().isOk());
        String a1 = createWorkPackage(goalId, "A1", Instant.now().plus(9, ChronoUnit.DAYS), projectA);
        String a2 = createWorkPackage(goalId, "A2", null, projectA);
        String a3 = createWorkPackage(goalId, "A3", Instant.now().plus(1, ChronoUnit.DAYS), projectA);
        String b1 = createWorkPackage(goalId, "B1", null, projectB);
        // zwei Zielprojekte: projectId ist Pflicht
        perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", "Welches Projekt?"))
                .andExpect(status().isBadRequest());

        JsonNode detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(detail.get("projects").findValuesAsText("projectId"))
                .as("B kam zuerst ans Ziel, A mit dem ersten Paket").containsExactly(projectB.getId(), projectA.getId());
        assertThat(detail.get("workPackages").findValuesAsText("title")).containsExactly("B1", "A1", "A2", "A3");

        JsonNode reordered = json(perform(put("/api/v1/goals/" + goalId + "/work-packages/order"), Map.of("ids", List.of(a3, a1)))
                .andExpect(status().isOk()));
        assertThat(reordered.findValuesAsText("title")).containsExactly("B1", "A3", "A1", "A2");
        assertThat(reordered.findValuesAsText("position")).containsExactly("0", "0", "1", "2");
        perform(put("/api/v1/goals/" + goalId + "/work-packages/order"), Map.of("ids", List.of(a1, b1)))
                .andExpect(status().isBadRequest());
        perform(put("/api/v1/goals/" + goalId + "/work-packages/order"), Map.of("ids", List.of("missing")))
                .andExpect(status().isNotFound());

        // nur den Start leeren liesse ein halbes Paket
        perform(put("/api/v1/goals/" + goalId + "/work-packages/" + a1), Map.of("startDate", ""))
                .andExpect(status().isBadRequest());
        perform(put("/api/v1/goals/" + goalId + "/work-packages/" + a1), Map.of("startDate", "", "endDate", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").doesNotExist())
                .andExpect(jsonPath("$.endDate").doesNotExist())
                .andExpect(jsonPath("$.state").value("OPEN"));
        perform(put("/api/v1/goals/" + goalId + "/work-packages/" + a1), Map.of("startDate", "2026-11-02", "endDate", "2026-11-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-11-02T00:00:00Z"))
                .andExpect(jsonPath("$.endDate").value("2026-11-06T23:59:59Z"));

        Todo todo = todo("Aufgabe", projectA, null);
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("goalId", goalId, "workPackageId", a1))
                .andExpect(status().isOk());
        perform(put("/api/v1/goals/" + goalId + "/work-packages/" + a1), Map.of("projectId", projectB.getId()))
                .andExpect(status().isBadRequest());
        perform(put("/api/v1/goals/" + goalId + "/work-packages/" + a2), Map.of("projectId", projectB.getId()))
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

    private String createWorkPackage(String goalId, String title, Instant end) throws Exception {
        return createWorkPackage(goalId, title, end, projectA);
    }

    /**
     * Paket im Projekt; ein Projekt, das dem Ziel noch fehlt, kommt dabei dazu. {@code end} null = ohne
     * Zeitraum, sonst ein Ein-Tages-Paket bis {@code end}.
     */
    private String createWorkPackage(String goalId, String title, Instant end, Project project) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", title, "projectId", project.getId()));
        if (end != null) {
            body.put("startDate", end.minus(1, ChronoUnit.HOURS).toString());
            body.put("endDate", end.toString());
        }
        return json(perform(post("/api/v1/goals/" + goalId + "/work-packages"), body)
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
