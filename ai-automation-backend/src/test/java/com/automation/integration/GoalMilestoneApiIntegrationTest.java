package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Todo;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
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
 * Meilensteine – Termine des ganzen Ziels, optional an Arbeitspakete geknuepft – ueber die echte API
 * gegen die echte Datenbank (TODO c60bf914 Ziele Stufe 1: Ziel → Projekte → Arbeitspakete → TODOs,
 * Meilensteine, Gliederung und Zeitstrahl): Zustand aus den Paketen, von Hand erreichen, der naechste
 * Meilenstein in Zielliste und Detail, „Braucht dich“.
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
    @Autowired private GoalWorkPackageRepository workPackageRepository;
    @Autowired private EntityManager entityManager;

    private Project project;

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        project = projectRepository.save(Project.builder().name("ms-" + nano).type(ProjectType.CODE)
                .path("/tmp/ms-" + nano).status(ProjectStatus.STOPPED).build());
    }

    @Test
    void milestones_followTheirPackages_orAreReachedByHand_andTheNextOneIsNamedInListAndDetail() throws Exception {
        String goalId = createGoal("Release " + System.nanoTime());
        String docs = createWorkPackage(goalId, "Doku");
        String site = createWorkPackage(goalId, "Site");
        Todo docsTodo = todoIn(goalId, docs, "Doku schreiben");
        Todo siteTodo = todoIn(goalId, site, "Site bauen");
        Instant launchDue = Instant.now().plus(30, ChronoUnit.DAYS);
        Instant betaDue = Instant.now().plus(1, ChronoUnit.DAYS);

        JsonNode launch = json(perform(post("/api/v1/goals/" + goalId + "/milestones"),
                Map.of("title", "Launch", "dueDate", launchDue.toString(), "workPackageIds", List.of(site, docs)))
                .andExpect(status().isCreated()));
        String launchId = launch.get("id").asText();
        assertThat(launch.get("state").asText()).isEqualTo("OPEN");
        assertThat(launch.get("workPackageIds")).extracting(JsonNode::asText).containsExactlyInAnyOrder(docs, site);
        assertThat(launch.get("packagesTotal").asInt()).isEqualTo(2);
        assertThat(launch.get("packagesDone").asInt()).isZero();
        assertThat(launch.get("reachedAt").isNull()).isTrue();
        assertThat(launch.get("dueDate").asText()).isEqualTo(launchDue.toString());
        JsonNode beta = json(perform(post("/api/v1/goals/" + goalId + "/milestones"),
                Map.of("title", "Beta", "dueDate", betaDue.toString())).andExpect(status().isCreated()));
        String betaId = beta.get("id").asText();
        assertThat(beta.get("state").asText()).isEqualTo("DUE_SOON");
        assertThat(beta.get("workPackageIds")).isEmpty();

        // Datum ist Pflicht, Pakete muessen zum Ziel gehoeren, das Ziel muss es geben
        perform(post("/api/v1/goals/" + goalId + "/milestones"), Map.of("title", "Ohne Datum")).andExpect(status().isBadRequest());
        String otherGoal = createGoal("Anderes");
        String foreign = createWorkPackage(otherGoal, "Fremd");
        perform(post("/api/v1/goals/" + goalId + "/milestones"),
                Map.of("title", "Fremd", "dueDate", "2026-12-01", "workPackageIds", List.of(foreign))).andExpect(status().isBadRequest());
        perform(post("/api/v1/goals/missing/milestones"), Map.of("title", "X", "dueDate", "2026-12-01")).andExpect(status().isNotFound());
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(60).toString();
        perform(post("/api/v1/goals/" + goalId + "/milestones"), Map.of("title", "Tag", "dueDate", day))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueDate").value(day + "T23:59:59Z"));

        JsonNode list = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/milestones")).andExpect(status().isOk()));
        assertThat(list.findValuesAsText("title")).as("nach Datum").containsExactly("Beta", "Launch", "Tag");

        setStatus(docsTodo, "DONE");
        JsonNode detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isOk()));
        assertThat(milestone(detail, launchId).get("packagesDone").asInt()).isEqualTo(1);
        assertThat(milestone(detail, launchId).get("state").asText()).isEqualTo("OPEN");
        assertThat(detail.at("/goal/nextMilestone/id").asText()).as("der naechste nicht erreichte nach Datum").isEqualTo(betaId);

        setStatus(siteTodo, "DONE");
        detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(milestone(detail, launchId).get("state").asText()).isEqualTo("REACHED");
        String reachedAt = milestone(detail, launchId).get("reachedAt").asText();
        assertThat(reachedAt).isNotEmpty();
        entityManager.flush();
        entityManager.clear();
        assertThat(milestoneRepository.findById(launchId).orElseThrow().getReachedAt()).as("beim ersten Erkennen gespeichert").isNotNull();
        assertThat(workPackageRepository.findById(site).orElseThrow().getCompletedAt()).isNotNull();

        // Ein neues offenes TODO im Paket: der Meilenstein ist wieder offen, reachedAt bleibt
        todoIn(goalId, site, "Nachzuegler");
        detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(milestone(detail, launchId).get("state").asText()).isEqualTo("OPEN");
        assertThat(milestone(detail, launchId).get("reopened").asBoolean()).isTrue();
        assertThat(milestone(detail, launchId).get("reachedAt").asText()).isEqualTo(reachedAt);

        // Von Hand erreichen: nur ohne Pakete
        perform(put("/api/v1/goals/" + goalId + "/milestones/" + launchId), Map.of("reached", true))
                .andExpect(status().isBadRequest());
        perform(put("/api/v1/goals/" + goalId + "/milestones/" + betaId), Map.of("reached", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("REACHED"))
                .andExpect(jsonPath("$.reachedAt").isNotEmpty());
        JsonNode goals = json(mockMvc.perform(get("/api/v1/goals")).andExpect(status().isOk()));
        JsonNode listed = null;
        for (JsonNode goal : goals) {
            if (goalId.equals(goal.get("id").asText())) listed = goal;
        }
        assertThat(listed).isNotNull();
        assertThat(listed.at("/nextMilestone/id").asText()).as("Beta ist erreicht, Launch wieder offen").isEqualTo(launchId);
        assertThat(listed.at("/nextMilestone/title").asText()).isEqualTo("Launch");
        assertThat(listed.at("/nextMilestone/state").asText()).isEqualTo("OPEN");
        perform(put("/api/v1/goals/" + goalId + "/milestones/" + betaId), Map.of("reached", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DUE_SOON"))
                .andExpect(jsonPath("$.reachedAt").doesNotExist());

        // Verknuepfungen ersetzen: nur noch das fertige Paket – erreicht
        perform(put("/api/v1/goals/" + goalId + "/milestones/" + launchId), Map.of("workPackageIds", List.of(docs)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packagesTotal").value(1))
                .andExpect(jsonPath("$.state").value("REACHED"))
                .andExpect(jsonPath("$.reachedAt").value(reachedAt));
        perform(put("/api/v1/goals/" + goalId + "/milestones/" + launchId), Map.of("dueDate", ""))
                .andExpect(status().isBadRequest());
        String other = createGoal("Noch eins");
        perform(put("/api/v1/goals/" + other + "/milestones/" + launchId), Map.of("title", "fremd"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/milestones/" + betaId)).andExpect(status().isNoContent());
        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/milestones"))).findValuesAsText("title"))
                .containsExactly("Launch", "Tag");
        assertThat(workPackageRepository.findById(docs)).as("Pakete bleiben").isPresent();
    }

    @Test
    void needsYou_listsDueSoonAndOverdueMilestones_withKindAndGoal() throws Exception {
        String goalId = createGoal("Launch " + System.nanoTime());
        String overdue = createMilestone(goalId, "Ueberfaellig", Instant.now().minus(1, ChronoUnit.DAYS));
        String soon = createMilestone(goalId, "Bald", Instant.now().plus(1, ChronoUnit.DAYS));
        String later = createMilestone(goalId, "Spaeter", Instant.now().plus(20, ChronoUnit.DAYS));
        String reached = createMilestone(goalId, "Erreicht", Instant.now().minus(2, ChronoUnit.DAYS));
        perform(put("/api/v1/goals/" + goalId + "/milestones/" + reached), Map.of("reached", true)).andExpect(status().isOk());

        JsonNode overview = json(mockMvc.perform(get("/api/v1/todos/overview")).andExpect(status().isOk()));
        Map<String, JsonNode> byId = new HashMap<>();
        overview.get("milestonesDue").forEach(node -> byId.put(node.get("id").asText(), node));

        assertThat(byId).containsKeys(overdue, soon).doesNotContainKeys(later, reached);
        assertThat(byId.get(overdue).get("kind").asText()).isEqualTo("MILESTONE");
        assertThat(byId.get(overdue).get("state").asText()).isEqualTo("OVERDUE");
        assertThat(byId.get(overdue).get("goalId").asText()).isEqualTo(goalId);
        assertThat(byId.get(overdue).get("goalTitle").asText()).startsWith("Launch");
        assertThat(byId.get(soon).get("state").asText()).isEqualTo("DUE_SOON");
    }

    private String createGoal(String title) throws Exception {
        return json(perform(post("/api/v1/goals"), Map.of("title", title, "projectIds", List.of(project.getId())))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private String createWorkPackage(String goalId, String title) throws Exception {
        return json(perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", title))
                .andExpect(status().isCreated())).get("id").asText();
    }

    private String createMilestone(String goalId, String title, Instant due) throws Exception {
        return json(perform(post("/api/v1/goals/" + goalId + "/milestones"), Map.of("title", title, "dueDate", due.toString()))
                .andExpect(status().isCreated())).get("id").asText();
    }

    /** Ein TODO im Projekt, dem Ziel und dem Paket zugeordnet. */
    private Todo todoIn(String goalId, String workPackageId, String title) throws Exception {
        Todo todo = todoRepository.save(Todo.builder().title(title).priority(Todo.Priority.MEDIUM).projectId(project.getId()).build());
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("goalId", goalId, "workPackageId", workPackageId))
                .andExpect(status().isOk());
        return todo;
    }

    private void setStatus(Todo todo, String status) throws Exception {
        perform(patch("/api/v1/todos/" + todo.getId() + "/status"), Map.of("status", status)).andExpect(status().isOk());
    }

    private static JsonNode milestone(JsonNode detail, String id) {
        for (JsonNode node : detail.get("milestones")) {
            if (id.equals(node.get("id").asText())) return node;
        }
        throw new AssertionError("Meilenstein " + id + " fehlt im Ziel");
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
