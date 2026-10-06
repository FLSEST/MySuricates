package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.TodoTrigger;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.TodoTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Goals via the real API against the real database (TODO dc0566f8): create, assign TODOs,
 * order, estimate, progress, delete. Security is off in the profile {@code integration}.
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
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private TodoTriggerRepository triggerRepository;
    @Autowired private EntityManager entityManager;

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
        assertThat(assigned.get("projects").findValuesAsText("projectId"))
                .as("die Projekte der TODOs kommen automatisch ans Ziel (TODO c60bf914)")
                .containsExactlyInAnyOrder(projectA.getId(), projectB.getId());
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

        // A human overrides the AI; the question is settled.
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

    /**
     * A second ordering with fewer TODOs takes the rank from the remaining ones (TODO f83dcc48): reorder loads
     * the no longer named ones in bulk (findAllById) and saves them without a rank. The
     * persistence context is cleared beforehand as between two real requests, otherwise the TODOs would come
     * from the cache instead of the database.
     */
    @Test
    void reorderWithFewerTodos_dropsTheRankOfTheOthers() throws Exception {
        String goalId = createGoal("Release", null);
        Todo docs = todo("Doku", Todo.Priority.LOW, projectA);
        Todo privacy = todo("Privatdaten", Todo.Priority.HIGH, projectA);
        Todo videos = todo("Videos", Todo.Priority.MEDIUM, projectB);
        List<String> all = List.of(docs.getId(), privacy.getId(), videos.getId());
        perform(post("/api/v1/goals/" + goalId + "/todos"), Map.of("todoIds", all)).andExpect(status().isOk());
        perform(put("/api/v1/goals/" + goalId + "/order"), Map.of("todoIds", all)).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        JsonNode reordered = json(perform(put("/api/v1/goals/" + goalId + "/order"),
                Map.of("todoIds", List.of(videos.getId()))).andExpect(status().isOk()));

        // With rank first, then without rank by priority (HIGH before LOW).
        assertThat(ids(reordered)).containsExactly(videos.getId(), privacy.getId(), docs.getId());
        assertThat(reordered.at("/todos/0/goalRank").asInt()).isEqualTo(1);
        assertThat(reordered.at("/todos/1/goalRank").isNull()).isTrue();
        assertThat(reordered.at("/todos/2/goalRank").isNull()).isTrue();
        entityManager.flush();
        entityManager.clear();
        assertThat(todoRepository.findAllById(all)).extracting(Todo::getId, Todo::getGoalRank)
                .containsExactlyInAnyOrder(tuple(docs.getId(), null), tuple(privacy.getId(), null),
                        tuple(videos.getId(), 1));
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

    /**
     * Relation map (TODO c6ea8f5c): origin from gate run, audit and human, handling via
     * fix workflow and TodoTrigger, result of the last fix run, candidates via {@code include}
     * – and {@code goalId=none} as a filter for TODOs without a goal.
     */
    @Test
    void relations_showOriginWorkersAndResult_andCandidatesFromUnassignedTodos() throws Exception {
        String goalId = createGoal("Launch-Karte", null);
        long nano = System.nanoTime();
        Workflow gate = workflowRepository.save(Workflow.builder().name("Gate " + nano).project(projectA).build());
        Workflow fixer = workflowRepository.save(Workflow.builder().name("Fixer " + nano).project(projectA).build());
        WorkflowRun gateRun = run(gate, RunStatus.FAILED);
        WorkflowRun fixRun = run(fixer, RunStatus.COMPLETED);
        String tag = "karte-" + nano;
        triggerRepository.save(TodoTrigger.builder().tagPattern(tag).targetWorkflow(fixer).description("Karten-Fixer").build());

        Todo fromGate = todoRepository.save(Todo.builder().title("Aus dem Gate").projectId(projectA.getId())
                .source(Todo.Source.WORKFLOW).workflowRunId(gateRun.getId()).referenceType("workflow_run")
                .referenceId(gate.getId()).tags("[\"" + tag + "\"]").lastFixRunId(fixRun.getId()).fixAttempts(1)
                .resolutionState(Todo.ResolutionState.SUCCEEDED).build());
        Todo fromAudit = todoRepository.save(Todo.builder().title("Aus dem Audit").projectId(projectB.getId())
                .source(Todo.Source.WORKFLOW).referenceType("audit_finding").referenceId("finding-" + nano).build());
        Todo mine = todoRepository.save(Todo.builder().title("Von Hand").linkedWorkflowId(fixer.getId()).build());
        Todo candidate = todoRepository.save(Todo.builder().title("Kandidat " + nano).tags("[\"" + tag + "\"]").build());
        perform(post("/api/v1/goals/" + goalId + "/todos"),
                Map.of("todoIds", List.of(fromGate.getId(), fromAudit.getId(), mine.getId()))).andExpect(status().isOk());

        JsonNode relations = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/relations")
                .param("include", candidate.getId() + "," + mine.getId())).andExpect(status().isOk()));

        JsonNode gateNode = findTodo(relations, fromGate.getId());
        assertThat(gateNode.at("/origin/kind").asText()).isEqualTo("WORKFLOW_RUN");
        assertThat(gateNode.at("/origin/workflowName").asText()).isEqualTo(gate.getName());
        assertThat(gateNode.at("/origin/runStatus").asText()).isEqualTo("FAILED");
        // Other tests may leave active triggers behind – search specifically for the own one.
        assertThat(workersOf(gateNode, fixer.getId())).containsExactly("TRIGGER:Karten-Fixer");
        assertThat(gateNode.at("/result/runStatus").asText()).isEqualTo("COMPLETED");
        assertThat(gateNode.at("/result/workflowId").asText()).isEqualTo(fixer.getId());
        assertThat(gateNode.at("/result/resolutionState").asText()).isEqualTo("SUCCEEDED");
        assertThat(gateNode.at("/projectName").asText()).isEqualTo(projectA.getName());
        assertThat(gateNode.has("description")).isFalse();

        JsonNode auditNode = findTodo(relations, fromAudit.getId());
        assertThat(auditNode.at("/origin/kind").asText()).isEqualTo("AUDIT");
        assertThat(auditNode.at("/origin/findingIds/0").asText()).isEqualTo("finding-" + nano);
        assertThat(workersOf(auditNode, fixer.getId())).isEmpty();
        assertThat(auditNode.get("result").isNull()).isTrue();

        JsonNode mineNode = findTodo(relations, mine.getId());
        assertThat(mineNode.at("/origin/kind").asText()).isEqualTo("USER");
        assertThat(workersOf(mineNode, fixer.getId())).containsExactly("LINKED:null");
        assertThat(mineNode.get("candidate").asBoolean()).as("eigenes TODO bleibt Mitglied").isFalse();

        JsonNode candidateNode = findTodo(relations, candidate.getId());
        assertThat(candidateNode.get("candidate").asBoolean()).isTrue();
        assertThat(workersOf(candidateNode, fixer.getId())).containsExactly("TRIGGER:Karten-Fixer");
        assertThat(relations.get("todos")).hasSize(4);
        assertThat(relations.get("activeTriggers").asInt()).isPositive();

        JsonNode unassigned = json(mockMvc.perform(get("/api/v1/todos").param("goalId", "none").param("tag", tag))
                .andExpect(status().isOk()));
        assertThat(unassigned.get("content").findValuesAsText("id")).containsExactly(candidate.getId());

        mockMvc.perform(get("/api/v1/goals/missing/relations")).andExpect(status().isNotFound());
    }

    private WorkflowRun run(Workflow workflow, RunStatus status) {
        Instant start = Instant.parse("2026-09-24T08:00:00Z");
        return runRepository.save(WorkflowRun.builder().workflow(workflow).workflowName(workflow.getName())
                .status(status).startedAt(start).completedAt(start.plusSeconds(30))
                .inputContext(Map.of()).metadata(Map.of()).build());
    }

    private static List<String> workersOf(JsonNode todo, String workflowId) {
        List<String> found = new java.util.ArrayList<>();
        for (JsonNode worker : todo.get("workers")) {
            if (workflowId.equals(worker.get("workflowId").asText())) {
                found.add(worker.get("kind").asText() + ":" + worker.get("triggerLabel").asText(null));
            }
        }
        return found;
    }

    private static JsonNode findTodo(JsonNode relations, String id) {
        for (JsonNode todo : relations.get("todos")) {
            if (id.equals(todo.get("id").asText())) {
                return todo;
            }
        }
        throw new AssertionError("TODO " + id + " fehlt in der Relationskarte");
    }

    // ── TODO c60bf914 (Ziele Stufe 1: Ziel → Projekte → Arbeitspakete → TODOs, Meilensteine) ────

    @Test
    void goalWithProjects_isListedPerProject_andCarriesItsProjectsInOrder() throws Exception {
        long nano = System.nanoTime();
        Project projectC = projectRepository.save(Project.builder().name("goal-c-" + nano).type(ProjectType.CODE)
                .path("/tmp/goal-c-" + nano).status(ProjectStatus.STOPPED).build());
        JsonNode created = json(perform(post("/api/v1/goals"), Map.of("title", "Release",
                "projectIds", List.of(projectB.getId(), projectA.getId()))).andExpect(status().isCreated()));
        String goalId = created.get("id").asText();
        assertThat(created.get("projects").findValuesAsText("id")).containsExactly(projectB.getId(), projectA.getId());
        assertThat(created.at("/projects/0/name").asText()).isEqualTo(projectB.getName());
        String other = createGoal("Ohne Projekt", null);

        JsonNode forA = json(mockMvc.perform(get("/api/v1/goals").param("status", "ACTIVE").param("projectId", projectA.getId()))
                .andExpect(status().isOk()));
        assertThat(find(forA, goalId)).isNotNull();
        assertThat(find(forA, other)).isNull();
        assertThat(find(forA, goalId).get("projects").findValuesAsText("id")).containsExactly(projectB.getId(), projectA.getId());
        JsonNode forC = json(mockMvc.perform(get("/api/v1/goals").param("projectId", projectC.getId())));
        assertThat(find(forC, goalId)).isNull();
        JsonNode all = json(mockMvc.perform(get("/api/v1/goals")));
        assertThat(find(all, other).get("projects")).isEmpty();
        assertThat(find(all, other).get("nextMilestone").isNull()).as("ohne Meilensteine keiner").isTrue();

        perform(post("/api/v1/goals"), Map.of("title", "Kaputt", "projectIds", List.of("missing")))
                .andExpect(status().isNotFound());
    }

    @Test
    void projectsOfAGoal_areAddedIdempotently_reordered_andRemovedOnlyWhenEmptyOrCascaded() throws Exception {
        String goalId = createGoal("Release", null);
        Todo done = todo("Fertig", Todo.Priority.LOW, projectA);
        Todo question = todo("Frage", Todo.Priority.LOW, projectA);
        perform(post("/api/v1/goals/" + goalId + "/todos"), Map.of("todoIds", List.of(done.getId(), question.getId())))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/todos/" + done.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\"}")).andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + question.getId() + "/planning"), Map.of("openQuestion", "Welcher Stil?"))
                .andExpect(status().isOk());

        JsonNode added = json(perform(post("/api/v1/goals/" + goalId + "/projects"), Map.of("projectId", projectB.getId()))
                .andExpect(status().isOk()));
        perform(post("/api/v1/goals/" + goalId + "/projects"), Map.of("projectId", projectB.getId())).andExpect(status().isOk());
        assertThat(added.get("projects").findValuesAsText("projectId")).containsExactly(projectA.getId(), projectB.getId());
        assertThat(added.at("/projects/0/total").asInt()).isEqualTo(2);
        assertThat(added.at("/projects/0/done").asInt()).isEqualTo(1);
        assertThat(added.at("/projects/0/openQuestions").asInt()).isEqualTo(1);
        assertThat(added.at("/projects/0/archived").asBoolean()).isFalse();
        assertThat(added.at("/projects/1/total").asInt()).isZero();
        perform(post("/api/v1/goals/" + goalId + "/projects"), Map.of("projectId", "missing")).andExpect(status().isNotFound());

        JsonNode reordered = json(perform(put("/api/v1/goals/" + goalId + "/projects/order"),
                Map.of("ids", List.of(projectB.getId()))).andExpect(status().isOk()));
        assertThat(reordered.get("projects").findValuesAsText("projectId")).containsExactly(projectB.getId(), projectA.getId());
        assertThat(reordered.at("/projects/1/position").asInt()).isEqualTo(1);
        perform(put("/api/v1/goals/" + goalId + "/projects/order"), Map.of("ids", List.of("missing")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/projects/" + projectB.getId())).andExpect(status().isNoContent());
        String workPackage = json(perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", "Werbematerial"))
                .andExpect(status().isCreated())).get("id").asText();
        perform(patch("/api/v1/todos/" + question.getId() + "/planning"), Map.of("workPackageId", workPackage))
                .andExpect(status().isOk());
        String milestone = json(perform(post("/api/v1/goals/" + goalId + "/milestones"),
                Map.of("title", "Launch", "dueDate", "2026-12-01", "workPackageIds", List.of(workPackage)))
                .andExpect(status().isCreated())).get("id").asText();

        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/projects/" + projectA.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("1 Arbeitspaket")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("2 TODO")));
        mockMvc.perform(delete("/api/v1/goals/" + goalId + "/projects/" + projectA.getId()).param("cascade", "true"))
                .andExpect(status().isNoContent());

        JsonNode detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isOk()));
        assertThat(detail.get("projects")).isEmpty();
        assertThat(detail.get("workPackages")).isEmpty();
        assertThat(detail.get("todos")).isEmpty();
        assertThat(detail.at("/milestones/0/id").asText()).as("der Meilenstein gilt fuers ganze Ziel und bleibt").isEqualTo(milestone);
        assertThat(detail.at("/milestones/0/workPackageIds")).as("nur die Verknuepfung faellt weg").isEmpty();
        entityManager.clear();
        Todo released = todoRepository.findById(question.getId()).orElseThrow();
        assertThat(released.getGoalId()).isNull();
        assertThat(released.getWorkPackageId()).isNull();
        assertThat(released.getOpenQuestion()).as("das TODO selbst bleibt").isEqualTo("Welcher Stil?");
    }

    @Test
    void todoMovingToAnotherProject_bringsItsProjectToTheGoal_andLeavesAWorkPackageOfTheOldOne() throws Exception {
        String goalId = json(perform(post("/api/v1/goals"), Map.of("title", "Release", "projectIds", List.of(projectA.getId())))
                .andExpect(status().isCreated())).get("id").asText();
        String workPackage = json(perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", "Beta"))
                .andExpect(status().isCreated())).get("id").asText();
        Todo todo = todo("Wandert", Todo.Priority.MEDIUM, projectA);
        perform(patch("/api/v1/todos/" + todo.getId() + "/planning"), Map.of("goalId", goalId, "workPackageId", workPackage))
                .andExpect(status().isOk());

        perform(put("/api/v1/todos/" + todo.getId()), Map.of("projectId", projectB.getId())).andExpect(status().isOk());

        JsonNode detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)));
        assertThat(detail.get("projects").findValuesAsText("projectId")).containsExactly(projectA.getId(), projectB.getId());
        assertThat(detail.at("/todos/0/workPackageId").isNull()).as("Arbeitspaket des alten Projekts faellt weg").isTrue();
        assertThat(detail.at("/todos/0/projectId").asText()).isEqualTo(projectB.getId());
    }

    // ── „Könnte dazugehören“ only from the goal projects (TODO c60bf914) ─────────────────────

    @Test
    void candidates_comeFromTheGoalProjects_orFromAllProjectsOnRequest() throws Exception {
        String tag = "cand-" + System.nanoTime();
        String goalId = json(perform(post("/api/v1/goals"), Map.of("title", "Release", "projectIds", List.of(projectA.getId())))
                .andExpect(status().isCreated())).get("id").asText();
        String empty = createGoal("Ohne Projekte", null);
        Todo inA = todoRepository.save(Todo.builder().title("In A").priority(Todo.Priority.HIGH).projectId(projectA.getId())
                .tags(tag).build());
        Todo backlogA = todoRepository.save(Todo.builder().title("Backlog A").status(Todo.Status.BACKLOG)
                .priority(Todo.Priority.LOW).projectId(projectA.getId()).tags(tag).build());
        Todo inB = todoRepository.save(Todo.builder().title("In B").priority(Todo.Priority.MEDIUM).projectId(projectB.getId())
                .tags(tag).build());
        todoRepository.save(Todo.builder().title("Erledigt A").status(Todo.Status.DONE).priority(Todo.Priority.HIGH)
                .projectId(projectA.getId()).tags(tag).build());
        Todo assigned = todoRepository.save(Todo.builder().title("Schon im Ziel").priority(Todo.Priority.HIGH)
                .projectId(projectA.getId()).tags(tag).goalId(empty).build());

        JsonNode page = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/candidates").param("tag", tag))
                .andExpect(status().isOk()));
        assertThat(page.get("content").findValuesAsText("id")).as("offen, ohne Ziel, aus Projekt A; nach Prioritaet")
                .containsExactly(inA.getId(), backlogA.getId());
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page.at("/content/0/projectName").asText()).isEqualTo(projectA.getName());

        JsonNode all = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/candidates").param("tag", tag)
                .param("allProjects", "true")));
        assertThat(all.get("content").findValuesAsText("id")).containsExactly(inA.getId(), inB.getId(), backlogA.getId())
                .doesNotContain(assigned.getId());
        JsonNode searched = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/candidates").param("tag", tag)
                .param("q", "backlog")));
        assertThat(searched.get("content").findValuesAsText("id")).containsExactly(backlogA.getId());
        JsonNode paged = json(mockMvc.perform(get("/api/v1/goals/" + goalId + "/candidates").param("tag", tag)
                .param("allProjects", "true").param("size", "1").param("page", "1")));
        assertThat(paged.get("content").findValuesAsText("id")).containsExactly(inB.getId());
        assertThat(paged.get("totalElements").asInt()).isEqualTo(3);

        JsonNode none = json(mockMvc.perform(get("/api/v1/goals/" + empty + "/candidates").param("tag", tag)));
        assertThat(none.get("content")).as("ein Ziel ohne Projekte hat ohne allProjects keine Kandidaten").isEmpty();
        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + empty + "/candidates").param("tag", tag)
                .param("allProjects", "true"))).get("content")).hasSize(3);
        mockMvc.perform(get("/api/v1/goals/missing/candidates")).andExpect(status().isNotFound());
    }

    // ── „Entschieden“ (TODO 68a5e24e) ──────────────────────────────────────────────────────

    @Test
    void decisions_answerTheQuestion_andAreCountedPerGoalProjectAndWorkPackage() throws Exception {
        String goalId = json(perform(post("/api/v1/goals"), Map.of("title", "Release", "projectIds", List.of(projectA.getId())))
                .andExpect(status().isCreated())).get("id").asText();
        String workPackage = json(perform(post("/api/v1/goals/" + goalId + "/work-packages"), Map.of("title", "Video"))
                .andExpect(status().isCreated())).get("id").asText();
        Todo style = todo("Stil", Todo.Priority.MEDIUM, projectA);
        Todo music = todo("Musik", Todo.Priority.MEDIUM, projectA);
        perform(post("/api/v1/goals/" + goalId + "/todos"), Map.of("todoIds", List.of(style.getId(), music.getId())))
                .andExpect(status().isOk());
        perform(patch("/api/v1/todos/" + style.getId() + "/planning"),
                Map.of("workPackageId", workPackage, "openQuestion", "Welcher Stil?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.decisionState").value("QUESTION"));
        perform(patch("/api/v1/todos/" + music.getId() + "/planning"), Map.of("openQuestion", "Welche Musik?"))
                .andExpect(status().isOk());

        perform(patch("/api/v1/todos/" + style.getId() + "/planning"), Map.of("decision", "Handgezeichnet", "ratedBy", "AI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.decision").value("Handgezeichnet"))
                .andExpect(jsonPath("$.planning.decidedBy").value("AI"))
                .andExpect(jsonPath("$.planning.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.planning.openQuestion").doesNotExist())
                .andExpect(jsonPath("$.planning.decisionState").value("DECIDED"));
        perform(patch("/api/v1/todos/" + music.getId() + "/planning"), Map.of("openQuestion", "Andere?", "decision", "Jazz"))
                .andExpect(status().isBadRequest());

        JsonNode detail = json(mockMvc.perform(get("/api/v1/goals/" + goalId)).andExpect(status().isOk()));
        assertThat(detail.at("/goal/progress/decided").asInt()).isEqualTo(1);
        assertThat(detail.at("/goal/progress/openQuestions").asInt()).isEqualTo(1);
        assertThat(detail.at("/projects/0/decided").asInt()).isEqualTo(1);
        assertThat(detail.at("/workPackages/0/decided").asInt()).isEqualTo(1);
        JsonNode styleItem = null;
        for (JsonNode item : detail.get("todos")) {
            if (style.getId().equals(item.get("id").asText())) styleItem = item;
        }
        assertThat(styleItem).isNotNull();
        assertThat(styleItem.get("decision").asText()).isEqualTo("Handgezeichnet");
        assertThat(styleItem.get("decisionState").asText()).isEqualTo("DECIDED");
        JsonNode listed = find(json(mockMvc.perform(get("/api/v1/goals"))), goalId);
        assertThat(listed.at("/progress/decided").asInt()).as("auch in der Liste, aus dem GROUP BY").isEqualTo(1);

        // A new question leaves the decision in place; "" takes it back
        perform(patch("/api/v1/todos/" + style.getId() + "/planning"), Map.of("openQuestion", "Farbig oder nicht?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.decision").value("Handgezeichnet"))
                .andExpect(jsonPath("$.planning.decisionState").value("QUESTION"));
        perform(patch("/api/v1/todos/" + style.getId() + "/planning"), Map.of("decision", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planning.decision").doesNotExist())
                .andExpect(jsonPath("$.planning.decidedBy").doesNotExist());
        assertThat(json(mockMvc.perform(get("/api/v1/goals/" + goalId))).at("/goal/progress/decided").asInt()).isZero();
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
