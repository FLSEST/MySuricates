package com.automation.integration.scenario;

import com.automation.entity.ChainRun;
import com.automation.entity.Project;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowRun;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.chain.ChainExecutor;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
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
 * <p>A scenario is one run, several runs of the same workflow ({@code runs}) or a chain
 * ({@code chain}, started through the real {@link ChainExecutor}); a run can be answered like a
 * person would ({@code answers}). Setup lives in {@link ScenarioFixtures}, the checks in
 * {@link ScenarioChecks}, the answers in {@link ScenarioApprovals}.
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
    @Autowired private ChainExecutor chainExecutor;
    @Autowired private WorkflowAnalyzer workflowAnalyzer;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository workflowRunRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private WorkflowChainRepository chainRepository;
    @Autowired private ChainRunRepository chainRunRepository;
    @Autowired private AuditReportRepository auditReportRepository;
    @Autowired private AuditFindingRepository auditFindingRepository;
    @Autowired private AuditFindingOccurrenceRepository auditOccurrenceRepository;
    @Autowired private AuditFindingTodoRepository auditFindingTodoRepository;
    @Autowired private com.automation.repository.GoalRepository goalRepository;
    @Autowired private com.automation.repository.GoalMilestoneRepository goalMilestoneRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private List<ScriptedAiProvider> scriptedProviders;

    @MockitoBean private ContainerService containerService;

    @TempDir Path tempDir;

    private final List<String> projectIds = new ArrayList<>();

    private ScenarioChecks checks;
    private ScenarioFixtures fixtures;
    private ScenarioApprovals approvals;
    private ScenarioGoals goals;

    @BeforeEach
    void setUp() {
        when(containerService.getDockerContainerId(any(Project.class))).thenReturn(null);
        checks = new ScenarioChecks(entityManager, workflowRunRepository, stepResultRepository, todoRepository,
                auditReportRepository, auditFindingRepository, scriptedProviders);
        fixtures = new ScenarioFixtures(entityManager, projectRepository, workflowRepository, chainRepository,
                auditReportRepository, auditFindingRepository, auditOccurrenceRepository, auditFindingTodoRepository);
        approvals = new ScenarioApprovals(workflowExecutor, workflowRunRepository, entityManager);
        goals = new ScenarioGoals(goalRepository, goalMilestoneRepository, todoRepository);
    }

    @AfterEach
    void cleanup() {
        // Scenario data must not leak into other integration tests of the same JVM (see TODO 5695d482)
        for (String projectId : projectIds) {
            todoRepository.deleteAll(todoRepository.findByProjectIdAndStatusIn(projectId, List.of(Todo.Status.values())));
            fixtures.deleteAuditData(projectId);
        }
        goals.deleteAll();
        chainRunRepository.deleteAll();
        chainRepository.deleteAll();
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
            for (JsonNode run : scenario.path("runs")) {
                assertThat(run.path("expect").isObject()).as(file + ": every entry of runs has its expect").isTrue();
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void scenario(Path file) throws IOException {
        JsonNode scenario = JSON.readTree(file.toFile());
        String id = scenario.path("id").asText();
        Project project = fixtures.project(tempDir, id);
        projectIds.add(project.getId());
        Map<String, String> refs = new java.util.LinkedHashMap<>(fixtures.namedWorkflows(project, scenario.path("workflows")));
        if (scenario.has("goal")) {
            refs.put("goal", goals.create(project, scenario.path("goal")));
        }
        if (scenario.has("chain")) {
            runChain(project, id, scenario, refs);
            return;
        }

        Workflow workflow = fixtures.workflow(project, id, scenario.path("workflow"), refs);
        JsonNode expect = scenario.path("expect");
        if (expect.has("analyzerRules")) {
            List<String> rules = workflowAnalyzer.analyze(workflow).stream().map(AnalyzerFinding::rule).toList();
            for (JsonNode rule : expect.path("analyzerRules")) {
                assertThat(rules).as("analyzer rules").contains(rule.asText());
            }
        }
        if (!scenario.path("execute").asBoolean(true)) {
            return;
        }

        if (!scenario.has("runs")) {
            checks.checkRun(project.getId(), run(workflow, scenario, scenario, refs), expect);
            return;
        }
        WorkflowRun last = null;
        int index = 0;
        for (JsonNode runSpec : scenario.path("runs")) {
            last = run(workflow, runSpec, scenario, refs);
            try {
                checks.checkRun(project.getId(), last, runSpec.path("expect"));
            } catch (AssertionError e) {
                throw new AssertionError("runs[" + index + "]: " + e.getMessage(), e);
            }
            index++;
        }
        checks.checkRun(project.getId(), last, expect);
    }

    /** One run: {@code ai}, {@code context} and {@code answers} from the run, else from the scenario. */
    private WorkflowRun run(Workflow workflow, JsonNode runSpec, JsonNode scenario, Map<String, String> refs) {
        scriptProviders(runSpec.has("ai") ? runSpec.path("ai") : scenario.path("ai"));
        Map<String, Object> context = contextOf(ScenarioFixtures.resolveRefs(
                runSpec.has("context") ? runSpec.path("context") : scenario.path("context"), refs));
        WorkflowRun started = workflowExecutor.execute(workflow, context, TriggerSource.MANUAL);
        approvals.answer(started.getId(), runSpec.path("answers"));
        WorkflowRun finished = waitFor("run " + started.getId(), Duration.ofSeconds(30),
                () -> workflowRunRepository.findById(started.getId()).orElseThrow(), WorkflowRun::getStatus);
        fixtures.awaitAuditIngestion(workflow, finished);
        return finished;
    }

    private void runChain(Project project, String id, JsonNode scenario, Map<String, String> refs) {
        scriptProviders(scenario.path("ai"));
        WorkflowChain chain = fixtures.chain(id, scenario.path("chain"), refs);
        ChainRun started = chainExecutor.execute(chain, contextOf(scenario.path("context")));
        ChainRun finished = waitFor("chain run " + started.getId(), Duration.ofSeconds(60),
                () -> chainRunRepository.findById(started.getId()).orElseThrow(), ChainRun::getStatus);
        checks.checkChain(project.getId(), finished, scenario.path("expect"));
    }

    private void scriptProviders(JsonNode ai) {
        for (ScriptedAiProvider provider : scriptedProviders) {
            List<ScriptedAiProvider.Rule> rules = new ArrayList<>();
            for (JsonNode rule : ai.path(provider.getId())) {
                rules.add(new ScriptedAiProvider.Rule(ScenarioChecks.text(rule, "match"), rule.path("respond").asText(""),
                        rule.path("exitCode").asInt(0)));
            }
            provider.script(rules);
        }
    }

    private static Map<String, Object> contextOf(JsonNode context) {
        Map<String, Object> values = JSON.convertValue(context, new TypeReference<>() { });
        return values == null ? Map.of() : values;
    }

    private <T> T waitFor(String what, Duration timeout, Supplier<T> load, Function<T, RunStatus> status) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            entityManager.clear();
            T current = load.get();
            if (TERMINAL.contains(status.apply(current))) {
                return current;
            }
            ScenarioChecks.sleep(100);
        }
        throw new AssertionError(what + " did not finish in " + timeout.toSeconds() + " s");
    }
}
