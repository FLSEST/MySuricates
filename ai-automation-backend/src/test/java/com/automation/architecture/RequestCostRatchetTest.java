package com.automation.architecture;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.metrics.SqlStatementCounter;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Ratchet against performance regressions in the read paths the UI hits on every page load:
 * no listed route may issue more SQL statements or return more bytes than its entry in
 * {@link #BASELINE} allows.
 *
 * <p><b>Why statements and bytes, not milliseconds.</b> Wall-clock time on a developer machine
 * depends on host load, JIT warm-up and who else holds the single SQLite connection — asserting
 * on it would flake. The two costs pinned here are deterministic and are exactly the ones that
 * went wrong before: an N+1 turns 3 statements into 33 ({@code 1e465a9e perf(api)}: /workflows
 * and /chains), and a fetched TEXT blob turns a small response into a huge one
 * ({@code 443fa57e perf(dashboard)}: step blobs in the dashboard query). Both were noticed in
 * production, by hand. This test notices them before the commit.
 *
 * <p><b>The list only ever shrinks.</b> A ceiling may drop when a route gets cheaper (update it
 * in the same commit), but raising one means the route got more expensive: say why in the commit
 * message, or fix the query instead. The statement ceilings are exact — they are reproducible to
 * the statement. The byte ceilings carry headroom, because timestamp serialisation varies by a
 * few characters per row; they guard the order of magnitude, not the byte.
 *
 * <p>Counting comes from {@link SqlStatementCounter}, fed by Hibernate's StatementInspector. The
 * persistence context is cleared before every measurement, so the request re-reads what it needs
 * instead of finding the seeded entities in the first-level cache — otherwise a lazy-loading N+1
 * would stay invisible here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Transactional
class RequestCostRatchetTest {

    /** Seed size — large enough that a per-row query shows up as a multiple, small enough to stay fast. */
    private static final int PROJECTS = 3;
    private static final int WORKFLOWS_PER_PROJECT = 10;
    private static final int RUNS_PER_WORKFLOW = 2;
    private static final int STEPS_PER_RUN = 3;
    private static final int TODOS = 50;

    /** A step output big enough that fetching it by accident is visible in the response size. */
    private static final String FAT_STEP_OUTPUT = "x".repeat(20_000);

    record Cost(int statements, int responseBytes) {}

    /**
     * Route -> ceiling, measured on 2026-09-18 against the seed above.
     *
     * <p><b>Several of these ceilings pin a known defect, not a target.</b> The first run of this
     * test found three N+1 patterns and one unbounded response; they are pinned so that the gate
     * is green and nothing gets <em>worse</em> while they are fixed, each with its ticket. When a
     * fix lands, drop the ceiling in the same commit — {@code ceilingsThatDriftedTooHighAreReportedAsAHint}
     * prints the new number.
     *
     * <ul>
     *   <li>{@code /workflows}, {@code /search}, {@code /learning/dashboard}: one
     *       {@code select containers} per workflow — the project's container association is
     *       resolved row by row (TODO 13d1e2a2).</li>
     *   <li>{@code /executions}: one {@code select workflows} per run, from
     *       {@code safeWorkflowName(run)} in WorkflowMapper (TODO 9db34f3b).</li>
     *   <li>{@code /executions}: the list response carries every step's full {@code output} TEXT,
     *       so its size grows with step output, unbounded (TODO a05c9504).</li>
     *   <li>{@code /search}: also one {@code select workflow_runs} per hit — the batched run-count
     *       query of {@code 1e465a9e} covers the list endpoint but not search (TODO 13d1e2a2).</li>
     * </ul>
     */
    static final Map<String, Cost> BASELINE = new TreeMap<>(Map.of(
            "/api/v1/todos", new Cost(2, 32_000),
            "/api/v1/projects", new Cost(4, 1_200),
            "/api/v1/workflows", new Cost(32, 26_000),
            "/api/v1/executions", new Cost(29, 3_200_000),
            "/api/v1/learning/dashboard", new Cost(70, 650_000),
            "/api/v1/search?q=Workflow", new Cost(51, 18_000)));

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private TodoRepository todoRepository;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void seed() {
        List<Workflow> workflows = new ArrayList<>();
        for (int p = 0; p < PROJECTS; p++) {
            Project project = projectRepository.save(Project.builder()
                    .name("Cost Project " + p)
                    .type(ProjectType.CODE)
                    .path("/tmp/cost-" + p)
                    .status(ProjectStatus.STOPPED)
                    .build());

            for (int w = 0; w < WORKFLOWS_PER_PROJECT; w++) {
                workflows.add(workflowRepository.save(Workflow.builder()
                        .name("Cost Workflow " + p + "-" + w)
                        .description("Seeded for the request cost ratchet")
                        .project(project)
                        .build()));
            }
        }

        for (Workflow workflow : workflows) {
            for (int r = 0; r < RUNS_PER_WORKFLOW; r++) {
                WorkflowRun run = runRepository.save(WorkflowRun.builder()
                        .workflow(workflow)
                        .status(RunStatus.COMPLETED)
                        .startedAt(Instant.parse("2026-09-01T10:00:00Z"))
                        .completedAt(Instant.parse("2026-09-01T10:00:05Z"))
                        .durationMs(5_000L)
                        .inputContext(Map.of())
                        .metadata(Map.of())
                        .build());

                for (int s = 0; s < STEPS_PER_RUN; s++) {
                    stepResultRepository.save(StepResult.builder()
                            .workflowRun(run)
                            .stepIndex(s)
                            .status(StepStatus.COMPLETED)
                            .input("seeded input")
                            .output(FAT_STEP_OUTPUT)
                            .startedAt(Instant.parse("2026-09-01T10:00:00Z"))
                            .completedAt(Instant.parse("2026-09-01T10:00:01Z"))
                            .durationMs(1_000L)
                            .build());
                }
            }
        }

        for (int t = 0; t < TODOS; t++) {
            todoRepository.save(Todo.builder()
                    .title("Cost Todo " + t)
                    .description("Seeded for the request cost ratchet")
                    .status(Todo.Status.OPEN)
                    .priority(Todo.Priority.MEDIUM)
                    .tags("performance,testing")
                    .build());
        }
    }

    @Test
    void readPathsStayWithinTheirCostCeiling() throws Exception {
        Map<String, Cost> measured = new LinkedHashMap<>();
        for (String route : BASELINE.keySet()) {
            measured.put(route, measure(route));
        }

        List<String> regressions = new ArrayList<>();
        measured.forEach((route, actual) -> {
            Cost ceiling = BASELINE.get(route);
            if (actual.statements() > ceiling.statements()) {
                regressions.add("%s: %d SQL statements, ceiling %d — an N+1 in the mapper or a lost "
                        .formatted(route, actual.statements(), ceiling.statements())
                        + "batch fetch is the usual cause");
            }
            if (actual.responseBytes() > ceiling.responseBytes()) {
                regressions.add("%s: %d response bytes, ceiling %d — a TEXT blob (step output, "
                        .formatted(route, actual.responseBytes(), ceiling.responseBytes())
                        + "context, metadata) is probably being serialised into a list response");
            }
        });

        assertThat(regressions)
                .as("Read paths got more expensive.\nMeasured: %s\nDo not just raise the ceiling — "
                        + "check the query first.", measured)
                .isEmpty();
    }

    /**
     * A ceiling that is far above what the route actually costs stops catching anything. Kept as a
     * hint rather than a failure, so that a genuine improvement never breaks the build — but the
     * stale headroom has to go in the commit that earned it.
     */
    @Test
    void ceilingsThatDriftedTooHighAreReportedAsAHint() throws Exception {
        List<String> slack = new ArrayList<>();
        for (Map.Entry<String, Cost> entry : BASELINE.entrySet()) {
            Cost actual = measure(entry.getKey());
            Cost ceiling = entry.getValue();
            if (actual.statements() < ceiling.statements()) {
                slack.add("%s: %d of %d statements".formatted(
                        entry.getKey(), actual.statements(), ceiling.statements()));
            }
        }
        if (!slack.isEmpty()) {
            System.out.println("[RequestCostRatchet] cheaper than pinned — tighten BASELINE ("
                    + slack.size() + "): " + String.join(", ", slack));
        }
    }

    private Cost measure(String route) throws Exception {
        entityManager.flush();
        entityManager.clear();

        SqlStatementCounter.start();
        MvcResult result;
        int statements;
        try {
            result = mockMvc.perform(get(route)).andReturn();
            statements = SqlStatementCounter.count();
        } finally {
            SqlStatementCounter.stop();
        }

        assertThat(result.getResponse().getStatus())
                .as("%s must answer 200 for the measurement to mean anything", route)
                .isEqualTo(200);

        // A ceiling only guards while something is actually being counted. Zero statements on a
        // route that reads from the database means the counter is not wired up any more — and
        // zero passes every ceiling, so without this the gate would go green while blind.
        // That happened: RequestCostFilter reset and removed the ThreadLocal mid-request, and
        // every route silently measured 0 (2026-09-18).
        assertThat(statements)
                .as("%s measured 0 SQL statements — the counter is not reaching this request, "
                        + "so the ceilings below are meaningless", route)
                .isPositive();

        return new Cost(statements, result.getResponse().getContentAsByteArray().length);
    }
}
