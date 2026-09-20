package com.automation.architecture;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.metrics.SqlStatementCounter;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.ScheduledWorkflowRepository;
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
 * <p><b>Ceilings alone are not enough.</b> A pinned absolute number only ever says that <em>this
 * seed</em> costs this much. {@code /api/v1/projects} sat green under a ceiling of 4 while the
 * same route cost 37 statements on the dev database — three seeded projects against thirty real
 * ones (TODO 6d0ab09f). {@link #statementCountMustNotGrowWithTheNumberOfRows} therefore measures
 * every route twice, at one and at two seed batches, and fails when the statement count follows
 * the row count. That criterion needs no guessed seed size, and it is the one that actually
 * describes an N+1. Routes with a known, open N+1 are exempt via {@link #KNOWN_GROWING}, and
 * {@link #knownGrowingRoutesStillGrow} takes them off that list again as soon as they are fixed.
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
     * <p><b>Some of these ceilings pin a known defect, not a target.</b> The first run of this test
     * found three N+1 patterns and one unbounded response; the ones still open are pinned so that
     * the gate is green and nothing gets <em>worse</em> while they are fixed, each with its ticket.
     * When a fix lands, drop the ceiling in the same commit —
     * {@code ceilingsThatDriftedTooHighAreReportedAsAHint} prints the new number (that is how
     * {@code /executions} went from 29 statements to 5 when TODO 9db34f3b was fixed).
     *
     * <ul>
     *   <li>{@code /workflows}, {@code /search}, {@code /learning/dashboard}: one
     *       {@code select containers} per workflow — the project's container association is
     *       resolved row by row (TODO 13d1e2a2). Listed in {@link #KNOWN_GROWING}.</li>
     *   <li>{@code /projects}: fixed, 4 → 1 statement. It was the same root cause as the three
     *       routes above — the nullable {@code @OneToOne(mappedBy)} {@code Project.container},
     *       resolved once per project. The list now runs through
     *       {@code ProjectRepository.findActiveWithContainer()} with a LEFT JOIN FETCH (TODO
     *       6d0ab09f). The ceiling of 4 had been green all along while the same route cost 37
     *       statements on the dev database — which is why this class now also measures growth,
     *       see {@link #statementCountMustNotGrowWithTheNumberOfRows}.</li>
     *   <li>{@code /executions}: fixed. The list response dropped from 3 061 816 to 51 716 bytes
     *       when the route stopped serialising every step (TODO a05c9504) — it now answers with
     *       {@code WorkflowRunListItemResponse}, which carries the step count and the last step's
     *       truncated output instead of the steps themselves. What is left is run metadata and
     *       input context; the size no longer grows with step output. The read side still fetches
     *       the step rows (the count and the last output come from them) — a projection would
     *       avoid that too, see TODO 7fd9f654.</li>
     *   <li>{@code /search}: the per-hit {@code select workflow_runs} is gone — search now uses the
     *       same batched mapper as {@code /workflows} (51 → 32 statements, TODO 13d1e2a2). What is
     *       left is the same {@code select containers} per workflow as {@code /workflows}, which is
     *       the half of that ticket still open.</li>
     *   <li>{@code /schedules}: fixed, 91 → 33 → 3 statements. The last 30 were one
     *       {@code select workflows} per schedule, because the EAGER association
     *       {@code ScheduledWorkflow.workflow} is not fetched along by {@code findAll()}; the list
     *       now runs through {@code findAllWithTargets()} with a LEFT JOIN FETCH over workflow,
     *       project, container and chain (TODO c9ec9844). The soft-delete question that blocked
     *       this is answered and pinned: a schedule pointing at a deleted workflow resolves to a
     *       null target instead of throwing — see
     *       {@code ScheduleSoftDeletedWorkflowIntegrationTest}. What is left is the query itself
     *       plus the two bundled run-status queries.</li>
     * </ul>
     */
    static final Map<String, Cost> BASELINE = new TreeMap<>(Map.of(
            "/api/v1/todos", new Cost(2, 32_000),
            "/api/v1/projects", new Cost(1, 1_200),
            "/api/v1/workflows", new Cost(32, 26_000),
            "/api/v1/executions", new Cost(5, 60_000),
            "/api/v1/learning/dashboard", new Cost(70, 650_000),
            "/api/v1/search?q=Workflow", new Cost(32, 18_000),
            "/api/v1/schedules", new Cost(3, 20_000)));

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private ScheduledWorkflowRepository scheduledWorkflowRepository;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void seed() {
        seedBatch("a");
    }

    /**
     * Eine Saat-Charge: {@link #PROJECTS} Projekte mit je {@link #WORKFLOWS_PER_PROJECT}
     * Workflows, deren Runs und Steps, einem Zeitplan je Workflow und {@link #TODOS} Todos.
     *
     * <p>Ein zweiter Aufruf verdoppelt jede dieser Tabellen — genau darauf beruht
     * {@link #statementCountMustNotGrowWithTheNumberOfRows}. Alle Namen und Pfade tragen die
     * Chargen-Kennung, damit die zweite Charge neue Zeilen anlegt statt die erste zu treffen.
     */
    private void seedBatch(String batch) {
        List<Workflow> workflows = new ArrayList<>();
        for (int p = 0; p < PROJECTS; p++) {
            Project project = projectRepository.save(Project.builder()
                    .name("Cost Project " + batch + p)
                    .type(ProjectType.CODE)
                    .path("/tmp/cost-" + batch + p)
                    .status(ProjectStatus.STOPPED)
                    .build());

            for (int w = 0; w < WORKFLOWS_PER_PROJECT; w++) {
                workflows.add(workflowRepository.save(Workflow.builder()
                        .name("Cost Workflow " + batch + p + "-" + w)
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

        // Ein Zeitplan je Workflow: Die Route /schedules laedt ueber ScheduledWorkflow.workflow
        // (EAGER) und Workflow.project (EAGER). Faellt der JOIN FETCH aus findAllWithTargets()
        // weg, loest Hibernate die Assoziationen wieder Zeile fuer Zeile auf und die Zahl der
        // Statements springt sichtbar auf ein Vielfaches von 30 (TODO 172a6a67, c9ec9844).
        for (Workflow workflow : workflows) {
            scheduledWorkflowRepository.save(ScheduledWorkflow.builder()
                    .workflow(workflow)
                    .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                    .cronExpression("0 0 6 * * MON")
                    .scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                    .enabled(true)
                    .description("Seeded for the request cost ratchet")
                    .inputContext(Map.of())
                    .build());
        }

        for (int t = 0; t < TODOS; t++) {
            todoRepository.save(Todo.builder()
                    .title("Cost Todo " + batch + t)
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
     * Die Routen, deren Statement-Zahl heute noch mit der Zeilenzahl waechst: bekannte, offene
     * Faelle mit Ticket. Sie sind vom Wachstums-Kriterium ausgenommen, damit dieses fuer die
     * uebrigen Routen scharf greift, statt als Ganzes rot und damit wertlos zu sein.
     *
     * <p>Die Ausnahme ist kein Freibrief: Die absoluten Obergrenzen in {@link #BASELINE} gelten
     * fuer diese Routen weiter, und {@link #knownGrowingRoutesStillGrow} meldet, sobald eine von
     * ihnen aufhoert zu wachsen — dann gehoert sie aus der Liste heraus, im selben Commit wie der
     * Fix.
     */
    static final Map<String, String> KNOWN_GROWING = new TreeMap<>(Map.of(
            "/api/v1/workflows",
            "TODO 13d1e2a2 — ein select containers je Workflow (Project.container ist eine "
                    + "nullable @OneToOne(mappedBy)). Ob Batch-Lookup oder schlanke Projektion, "
                    + "entscheidet der Nutzer; bis dahin bleibt die Route hier.",
            "/api/v1/search?q=Workflow",
            "TODO 13d1e2a2 — derselbe Mapper-Pfad wie /workflows.",
            "/api/v1/learning/dashboard",
            "TODO 13d1e2a2 — derselbe Mapper-Pfad wie /workflows."));

    /**
     * Das eigentliche N+1-Kriterium: Dieselbe Route wird gegen zwei Saat-Groessen gemessen, und
     * die Zahl der Statements darf dabei nicht steigen.
     *
     * <p><b>Warum zusaetzlich zu den Obergrenzen.</b> Eine gepinnte Absolutzahl bestaetigt nur,
     * dass <em>wenige</em> Zeilen wenig kosten. {@code /api/v1/projects} stand mit einer Obergrenze
     * von 4 gruen im Gate, waehrend dieselbe Route auf der Dev-Datenbank 37 Statements brauchte —
     * der Seed kannte drei Projekte, die echte Datenbank dreissig (TODO 6d0ab09f, gemessen
     * 2026-09-18 ueber {@code GET /api/v1/system/request-metrics}). Ein Kriterium, das die
     * Statement-Zahl gegen die Zeilenzahl haelt, braucht keine geratene Seed-Groesse: Es findet
     * jedes N+1 in den gepinnten Routen, unabhaengig davon, wie gross der Seed zufaellig ist.
     *
     * <p>Gemessen wird in einer Transaktion: erst mit einer Saat-Charge, dann mit zweien. Der
     * Persistenzkontext wird vor jeder Messung geleert, sonst faende die zweite Messung die
     * Zeilen der ersten im First-Level-Cache und jedes N+1 bliebe unsichtbar.
     */
    @Test
    void statementCountMustNotGrowWithTheNumberOfRows() throws Exception {
        Map<String, Integer> oneBatch = measureAll();
        seedBatch("b");
        Map<String, Integer> twoBatches = measureAll();

        List<String> nPlusOne = new ArrayList<>();
        for (String route : BASELINE.keySet()) {
            if (KNOWN_GROWING.containsKey(route)) {
                continue;
            }
            if (twoBatches.get(route) > oneBatch.get(route)) {
                nPlusOne.add("%s: %d statements for one seed batch, %d for two — the count follows "
                        .formatted(route, oneBatch.get(route), twoBatches.get(route))
                        + "the row count, so something runs a query per row");
            }
        }

        assertThat(nPlusOne)
                .as("A route got a query per row.\nOne batch: %s\nTwo batches: %s\nRaising a "
                        + "ceiling does not help here — batch the lookup or fetch it along.",
                        oneBatch, twoBatches)
                .isEmpty();
    }

    /**
     * Haelt {@link #KNOWN_GROWING} ehrlich: Eine Ausnahme, die nicht mehr noetig ist, nimmt die
     * Route dauerhaft aus dem Wachstums-Kriterium heraus — genau das, was eine Ausnahmeliste
     * gefaehrlich macht. Hoert eine gelistete Route auf zu wachsen, faellt dieser Test und die
     * Zeile gehoert geloescht.
     */
    @Test
    void knownGrowingRoutesStillGrow() throws Exception {
        Map<String, Integer> oneBatch = measureAll();
        seedBatch("b");
        Map<String, Integer> twoBatches = measureAll();

        List<String> fixed = new ArrayList<>();
        KNOWN_GROWING.forEach((route, ticket) -> {
            if (twoBatches.get(route) <= oneBatch.get(route)) {
                fixed.add("%s: %d statements at both seed sizes — it no longer grows, so take it "
                        .formatted(route, twoBatches.get(route))
                        + "out of KNOWN_GROWING (listed as: " + ticket + ")");
            }
        });

        assertThat(fixed)
                .as("An exception outlived its cause. Remove the entry in the same commit as the "
                        + "fix — otherwise the route silently stops being guarded.")
                .isEmpty();
    }

    private Map<String, Integer> measureAll() throws Exception {
        Map<String, Integer> statements = new LinkedHashMap<>();
        for (String route : BASELINE.keySet()) {
            statements.put(route, measure(route).statements());
        }
        return statements;
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
