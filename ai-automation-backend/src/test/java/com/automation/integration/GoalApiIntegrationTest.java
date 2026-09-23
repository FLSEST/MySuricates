package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Todo;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Ziele ueber die echte API gegen die echte Datenbank (TODO dc0566f8): anlegen, TODOs zuordnen,
 * ordnen, einschaetzen, Fortschritt, loeschen. Security ist im Profil {@code integration} aus.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class GoalApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private TodoRepository todoRepository;

    private Project projectA;
    private Project projectB;

    @BeforeEach
    void setUp() {
        long nano = System.nanoTime();
        projectA = projectRepository.save(Project.builder().name("goal-a-" + nano).type(ProjectType.CODE)
                .path("/tmp/goal-a-" + nano).status(ProjectStatus.STOPPED).build());
        projectB = projectRepository.save(Project.builder().name("goal-b-" + nano).type(ProjectType.CODE)
                .path("/tmp/goal-b-" + nano).status(ProjectStatus.STOPPED).build());
    }

    @Test
    void goalCollectsTodosAcrossProjects_ordersThem_andReportsProgress() throws Exception {
        String goalId = createGoal("Open-Source-Launch", "2026-10-15");
        Todo low = todo("Doku", Todo.Priority.LOW, projectA);
        Todo high = todo("Privatdaten", Todo.Priority.HIGH, projectA);
        Todo site = todo("Videos", Todo.Priority.MEDIUM, projectB);
        Todo outside = todo("Nicht im Ziel", Todo.Priority.CRITICAL, projectA);

        JsonNode assigned = json(perform(post("/api/v1/goals/" + goalId + "/todos"),
                Map.of("todoIds", List.of(low.getId(), high.getId(), site.getId()))).andExpect(status().isOk()));
        // Ohne Rang: Prioritaet entscheidet (HIGH, MEDIUM, LOW).
        assertThat(ids(assigned)).containsExactly(high.getId(), site.getId(), low.getId());
        assertThat(assigned.at("/goal/progress/total").asInt()).isEqualTo(3);
        assertThat(assigned.at("/goal/targetDate").asText()).startsWith("2026-10-15T00:00");
        assertThat(assigned.get("byProject")).hasSize(2);
        assertThat(assigned.at("/todos/0").has("description"))
                .as("die Zielansicht laedt keine Beschreibungen (schlanke Projektion)").isFalse();

        JsonNode ordered = json(perform(put("/api/v1/goals/" + goalId + "/order"),
                Map.of("todoIds", List.of(low.getId(), site.getId()))).andExpect(status().isOk()));
        assertThat(ids(ordered)).containsExactly(low.getId(), site.getId(), high.getId());
        assertThat(ordered.at("/todos/0/goalRank").asInt()).isEqualTo(1);
        assertThat(ordered.at("/todos/2/goalRank").isNull()).isTrue();

        perform(put("/api/v1/goals/" + goalId + "/order"), Map.of("todoIds", List.of(outside.getId())))
                .andExpect(status().isBadRequest());

        mockMvc.perform(patch("/api/v1/todos/" + site.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\"}")).andExpect(status().isOk());
        JsonNode list = json(mockMvc.perform(get("/api/v1/goals")).andExpect(status().isOk()));
        JsonNode mine = find(list, goalId);
        assertThat(mine.at("/progress/done").asInt()).isEqualTo(1);
        assertThat(mine.at("/progress/percentDone").asInt()).isEqualTo(33);

        JsonNode todos = json(mockMvc.perform(get("/api/v1/todos").param("goalId", goalId)).andExpect(status().isOk()));
        assertThat(todos.get("content")).hasSize(3);
        assertThat(todos.at("/content/0/planning/goalTitle").asText()).isEqualTo("Open-Source-Launch");
    }

    @Test
    void planningStoresRating_validatesValues_andTracksOpenQuestions() throws Exception {
        String goalId = createGoal("Launch", null);
        Todo todo = todo("Stilprobe", Todo.Priority.MEDIUM, projectA);

        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("goalRank", 2))
                .andExpect(status().isBadRequest());

        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of(
                "goalId", goalId, "complexity", "m", "suggestedEffort", "HIGH",
                "ratingNote", "Viele Dateien, klare Vorlage", "openQuestion", "Welcher Stil?", "ratedBy", "AI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.goalId").value(goalId))
                .andExpect(jsonPath("$.planning.complexity").value("M"))
                .andExpect(jsonPath("$.planning.suggestedEffort").value("high"))
                .andExpect(jsonPath("$.planning.ratedBy").value("AI"))
                .andExpect(jsonPath("$.planning.ratedAt").isNotEmpty());

        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + goalId))).at("/goal/progress/openQuestions").asInt())
                .isEqualTo(1);

        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("complexity", "XXL"))
                .andExpect(status().isBadRequest());
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("suggestedEffort", "max"))
                .andExpect(status().isBadRequest());

        // Ein Mensch ueberschreibt die KI; die Frage ist geklaert.
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("suggestedEffort", "medium", "openQuestion", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.ratedBy").value("USER"))
                .andExpect(jsonPath("$.planning.suggestedEffort").value("medium"))
                .andExpect(jsonPath("$.planning.openQuestion").doesNotExist());
        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + goalId))).at("/goal/progress/openQuestions").asInt())
                .isZero();

        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("goalId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.goalId").doesNotExist());
    }

    @Test
    void goalWithTodosWithoutProject_showsThemUnderNoProject() throws Exception {
        String goalId = createGoal("Ohne Projekt", null);
        Todo loose = todoRepository.save(Todo.builder().title("Lose").priority(Todo.Priority.LOW).build());
        perform(post("/api/v1/goals/" + goalId + "/todos"), Map.of("todoIds", List.of(loose.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todos[0].projectName").doesNotExist())
                .andExpect(jsonPath("$.byProject[0].total").value(1));
        mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isOk());
    }

    @Test
    void deletingGoalKeepsTodos_andArchivedGoalsNeedExplicitFilter() throws Exception {
        String goalId = createGoal("Aufraeumen", null);
        Todo todo = todo("Bleibt", Todo.Priority.MEDIUM, projectA);
        perform(post("/api/v1/goals/" + goalId + "/todos"), Map.of("todoIds", List.of(todo.getId())))
                .andExpect(status().isOk());

        String archived = createGoal("Archiviert", null);
        perform(put("/api/v1/goals/" + archived), Map.of("status", "ARCHIVED")).andExpect(status().isOk());
        assertThat(find(json(mockMvc.perform(get("/api/v1/goals"))), archived)).isNull();
        assertThat(find(json(mockMvc.perform(get("/api/v1/goals").param("status", "ARCHIVED"))), archived)).isNotNull();

        mockMvc.perform(delete("/api/v1/goals/" + goalId)).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isNotFound());
        Todo reloaded = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(reloaded.getGoalId()).isNull();

        perform(post("/api/v1/goals"), Map.of("title", " ")).andExpect(status().isBadRequest());
        perform(post("/api/v1/goals"), Map.of("title", "Datum kaputt", "targetDate", "morgen"))
                .andExpect(status().isBadRequest());
    }

    private String createGoal(String title, String targetDate) throws Exception {
        Map<String, Object> body = targetDate == null ? Map.of("title", title) : Map.of("title", title, "targetDate", targetDate);
        return json(perform(post("/api/v1/goals"), body).andExpect(status().isCreated())).get("id").asText();
    }

    private Todo todo(String title, Todo.Priority priority, Project project) {
        return todoRepository.save(Todo.builder().title(title).priority(priority).projectId(project.getId()).build());
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  Object body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<String> ids(JsonNode detail) {
        return detail.get("todos").findValuesAsText("id");
    }

    private static JsonNode find(JsonNode goals, String id) {
        for (JsonNode goal : goals) {
            if (id.equals(goal.get("id").asText())) {
                return goal;
            }
        }
        return null;
    }
}
