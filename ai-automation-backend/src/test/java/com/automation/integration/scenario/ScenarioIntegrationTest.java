package com.automation.integration.scenario;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.project.ContainerService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.analyzer.AnalyzerFinding;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Scenario tests (TODO fdf79a86): a workflow definition from {@code src/test/resources/scenarios}
 * runs through the real {@link WorkflowExecutor}; every AI call goes to a {@link ScriptedAiProvider}
 * instead of a CLI. What each scenario checks, and which spec and incident it comes from, is in
 * the file itself — {@code scenarios/README.md} lists them and explains the format.
 *
 * <p>Runs without {@code claude}, {@code codex}, {@code pi} and without a display: the scripted
 * providers are the only ones a scenario uses (they are registered as {@code SCRIPTED} and
 * {@code SCRIPTED_B}, the second one for "the block names another provider").
 */
@SpringBootTest
@ActiveProfiles("integration")
class ScenarioIntegrationTest {

    static final Path SCENARIOS = Path.of("src/test/resources/scenarios");

    private static final EnumSet<RunStatus> TERMINAL = EnumSet.of(RunStatus.COMPLETED,
            RunStatus.COMPLETED_WITH_ERRORS, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.INTERRUPTED);

    private static final ObjectMapper JSON = new ObjectMapper();

    @TestConfiguration
    static class ScriptedProviders {
        @Bean
        ScriptedAiProvider scriptedProvider() {
            return new ScriptedAiProvider("SCRIPTED");
        }

        @Bean
        ScriptedAiProvider scriptedProviderB() {
            return new ScriptedAiProvider("SCRIPTED_B");
        }
    }

    @Autowired private WorkflowExecutor workflowExecutor;
    @Autowired private WorkflowAnalyzer workflowAnalyzer;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository workflowRunRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private List<ScriptedAiProvider> scriptedProviders;

    @MockitoBean private ContainerService containerService;

    @TempDir Path tempDir;

    private final List<String> projectIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
    }

    @AfterEach
    void cleanup() {
        // Scenario data must not leak into other integration tests of the same JVM (see TODO 5695d482)
        for (String projectId : projectIds) {
            todoRepository.deleteAll(todoRepository.findByProjectIdAndStatusIn(projectId, List.of(Todo.Status.values())));
        }
        stepResultRepository.deleteAll();
        workflowRunRepository.deleteAll();
        workflowRepository.deleteAll();
        projectIds.forEach(projectRepository::deleteById);
        projectIds.clear();
    }

    static Stream<Path> scenarios() throws IOException {
        try (Stream<Path> files = Files.list(SCENARIOS)) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList().stream();
        }
    }

    @Test
    void thereAreScenarios_andEveryOneNamesItsOrigin() throws IOException {
        List<Path> files = scenarios().toList();
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            JsonNode scenario = JSON.readTree(file.toFile());
            assertThat(scenario.path("id").asText()).as(file + ": id = file name")
                    .isEqualTo(file.getFileName().toString().replace(".json", ""));
            assertThat(scenario.path("title").asText()).as(file + ": title").isNotBlank();
            JsonNode origin = scenario.path("derivedFrom");
            assertThat(origin.path("spec").asText()).as(file + ": derivedFrom.spec — what the system should do").isNotBlank();
            assertThat(origin.path("incidents").isArray() && !origin.path("incidents").isEmpty())
                    .as(file + ": derivedFrom.incidents — where it actually broke").isTrue();
            assertThat(scenario.path("expect").isObject()).as(file + ": expect").isTrue();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenario(Path file) throws IOException {
        JsonNode scenario = JSON.readTree(file.toFile());
        JsonNode expect = scenario.path("expect");
        Workflow workflow = createWorkflow(scenario.path("id").asText(), scenario.path("workflow"));

        if (expect.has("analyzerRules")) {
            List<String> rules = workflowAnalyzer.analyze(workflow).stream().map(AnalyzerFinding::rule).toList();
            for (JsonNode rule : expect.path("analyzerRules")) {
                assertThat(rules).as("analyzer rules").contains(rule.asText());
            }
        }
        if (!scenario.path("execute").asBoolean(true)) {
            return;
        }

        scriptProviders(scenario.path("ai"));
        Map<String, Object> context = JSON.convertValue(scenario.path("context"), new TypeReference<>() { });
        WorkflowRun run = waitForTerminal(workflowExecutor.execute(workflow,
                context == null ? Map.of() : context, TriggerSource.MANUAL));

        if (expect.has("runStatus")) {
            assertThat(run.getStatus().name()).as("run status").isEqualTo(expect.path("runStatus").asText());
        }
        checkGate(run, expect.path("gate"));
        checkSteps(run, expect.path("steps"));
        checkAiCalls(expect);
        checkTodos(workflow, expect);
    }

    // --- setup --------------------------------------------------------------------------------

    private Workflow createWorkflow(String id, JsonNode spec) {
        Path projectPath = tempDir.resolve(id);
        try {
            Files.createDirectories(projectPath);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Project project = projectRepository.saveAndFlush(Project.builder()
                .name("scenario " + id + "-" + System.nanoTime())
                .type(ProjectType.CODE)
                .path(projectPath.toString())
                .status(ProjectStatus.STOPPED)
                .build());
        projectIds.add(project.getId());

        List<BlockDefinition> blocks = JSON.convertValue(spec.path("blocks"), new TypeReference<>() { });
        Map<String, Object> metadata = spec.has("metadata")
                ? JSON.convertValue(spec.path("metadata"), new TypeReference<>() { }) : new HashMap<>();
        Workflow saved = workflowRepository.saveAndFlush(Workflow.builder()
                .name("scenario " + id + "-" + System.nanoTime())
                .project(project)
                .blocks(new ArrayList<>(blocks))
                .provider(text(spec, "provider"))
                .model(text(spec, "model"))
                .instructions(text(spec, "instructions"))
                .activatedSkills(new ArrayList<>())
                .defaultContext(Map.of())
                .metadata(metadata)
                .build());
        entityManager.clear();
        return workflowRepository.findByIdWithSkills(saved.getId()).orElseThrow();
    }

    private void scriptProviders(JsonNode ai) {
        for (ScriptedAiProvider provider : scriptedProviders) {
            List<ScriptedAiProvider.Rule> rules = new ArrayList<>();
            for (JsonNode rule : ai.path(provider.getId())) {
                rules.add(new ScriptedAiProvider.Rule(text(rule, "match"), rule.path("respond").asText(""),
                        rule.path("exitCode").asInt(0)));
            }
            provider.script(rules);
        }
    }

    private WorkflowRun waitForTerminal(WorkflowRun started) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            entityManager.clear();
            WorkflowRun current = workflowRunRepository.findById(started.getId()).orElseThrow();
            if (TERMINAL.contains(current.getStatus())) {
                return current;
            }
            sleep(100);
        }
        throw new AssertionError("Run " + started.getId() + " did not finish in time");
    }

    // --- checks -------------------------------------------------------------------------------

    private void checkGate(WorkflowRun run, JsonNode gate) {
        if (gate.isMissingNode()) {
            return;
        }
        Map<String, Object> metadata = run.getMetadata() == null ? Map.of() : run.getMetadata();
        if (gate.isTextual() && "none".equals(gate.asText())) {
            assertThat(metadata).as("run metadata — no gate expected").doesNotContainKey("gate_status");
            return;
        }
        assertThat(metadata.get("gate_status")).as("gate_status").isEqualTo(gate.path("status").asText());
        if (gate.has("category")) {
            assertThat(metadata.get("gate_category")).as("gate_category").isEqualTo(gate.path("category").asText());
        }
    }

    private void checkSteps(WorkflowRun run, JsonNode steps) {
        if (steps.isMissingNode()) {
            return;
        }
        entityManager.clear();
        var persisted = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId());
        for (JsonNode want : steps) {
            int index = want.path("index").asInt();
            assertThat(persisted.size()).as("steps of the run — step #%d expected", index).isGreaterThan(index);
            var step = persisted.get(index);
            String where = "step #" + index;
            if (want.has("status")) {
                assertThat(step.getStatus().name()).as(where + " status").isEqualTo(want.path("status").asText());
            }
            String output = step.getOutput() == null ? "" : step.getOutput();
            for (JsonNode part : want.path("outputContains")) {
                assertThat(output).as(where + " output").contains(part.asText());
            }
            for (JsonNode part : want.path("outputNotContains")) {
                assertThat(output).as(where + " output").doesNotContain(part.asText());
            }
        }
    }

    private void checkAiCalls(JsonNode expect) {
        for (ScriptedAiProvider provider : scriptedProviders) {
            List<ScriptedAiProvider.Call> calls = provider.calls();
            JsonNode count = expect.path("aiCallCount").path(provider.getId());
            if (!count.isMissingNode()) {
                assertThat(calls).as("calls to %s", provider.getId()).hasSize(count.asInt());
            }
            JsonNode expected = expect.path("aiCalls").path(provider.getId());
            for (int i = 0; i < expected.size(); i++) {
                JsonNode want = expected.get(i);
                assertThat(calls.size()).as("calls to %s — call #%d expected", provider.getId(), i).isGreaterThan(i);
                ScriptedAiProvider.Call call = calls.get(i);
                String where = provider.getId() + " call #" + i;
                if (want.has("model")) {
                    assertThat(call.model()).as(where + " model").isEqualTo(text(want, "model"));
                }
                if (want.has("timeoutSeconds")) {
                    assertThat(call.timeoutSeconds()).as(where + " timeoutSeconds").isEqualTo(want.path("timeoutSeconds").asInt());
                }
                if (want.has("effort")) {
                    assertThat(call.effort()).as(where + " effort").isEqualTo(text(want, "effort"));
                }
                for (JsonNode part : want.path("promptContains")) {
                    assertThat(call.prompt()).as(where + " prompt").contains(part.asText());
                }
                for (JsonNode part : want.path("promptNotContains")) {
                    assertThat(call.prompt()).as(where + " prompt").doesNotContain(part.asText());
                }
            }
        }
    }

    /** Auto-TODOs are written by an async listener after the run — polled, not read once. */
    private void checkTodos(Workflow workflow, JsonNode expect) {
        String projectId = workflow.getProject().getId();
        if (expect.path("noTodos").asBoolean(false)) {
            sleep(1_000); // long enough for the async listener to have written one if it would
            assertThat(todosOf(projectId)).as("todos of the scenario project").isEmpty();
        }
        for (JsonNode want : expect.path("todos")) {
            Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
            List<Todo> matching = List.of();
            while (Instant.now().isBefore(deadline)) {
                matching = todosOf(projectId).stream().filter(todo -> matches(todo, want)).toList();
                if (!matching.isEmpty()) {
                    break;
                }
                sleep(100);
            }
            assertThat(matching).as("todo %s among %s", want, todosOf(projectId).stream()
                    .map(todo -> todo.getTitle() + " " + todo.getTags() + " " + todo.getPriority()).toList()).isNotEmpty();
        }
    }

    private List<Todo> todosOf(String projectId) {
        entityManager.clear();
        return todoRepository.findByProjectIdAndStatusIn(projectId, Arrays.asList(Todo.Status.values()));
    }

    private static boolean matches(Todo todo, JsonNode want) {
        if (want.has("titleContains") && (todo.getTitle() == null || !todo.getTitle().contains(want.path("titleContains").asText()))) {
            return false;
        }
        if (want.has("priority") && !want.path("priority").asText().equals(String.valueOf(todo.getPriority()))) {
            return false;
        }
        for (JsonNode tag : want.path("tags")) {
            if (todo.getTags() == null || !todo.getTags().contains("\"" + tag.asText() + "\"")) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", e);
        }
    }
}
