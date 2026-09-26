package com.automation.architecture;

import com.automation.dto.response.WorkflowRunListItemResponse;
import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.GateStatus;
import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.entity.TodoTrigger;
import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.metrics.SqlStatementCounter;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.TodoTriggerRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.EntityStatistics;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
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
 * <p>Counting comes from {@link SqlStatementCounter}, fed by Hibernate's StatementInspector.
 *
 * <p><b>No surrounding transaction.</b> Every route is measured the way production serves it: OSIV
 * is off, so a service without a transaction of its own gets a fresh persistence context for every
 * repository call, and what one call loaded, the next one loads again. This class used to measure
 * inside the test transaction; the request joined it and shared one persistence context from the
 * first query to the last — a context it never has in production. {@code /learning/dashboard}
 * measured 10 statements that way while it cost 52 without (TODO 5c56b3d1): the ratchet could not
 * see what a service without a transaction of its own really costs. The seed is therefore
 * committed, {@link #measure} refuses to count inside an open transaction, and
 * {@link #removeTheSeed} empties the seeded tables after every test.
 *
 * <p><b>Its own database.</b> The ceilings describe <em>this seed</em>, so nothing else may be in
 * the tables when it is measured. The integration tests of one JVM share one SQLite file, and a
 * test without rollback leaves its rows behind: two projects and three workflows from
 * {@code LearningServiceDashboardIntegrationTest} were enough to push {@code /projects} over its
 * byte ceiling and {@code /workflows} over its statement ceiling — in a {@code -Dtest} selection
 * that ran that test first, while the class alone and the full suite stayed green (TODO 5695d482).
 * A ceiling that depends on the test order is luck. This class therefore points
 * {@code integration.db.path} at a file of its own, migrated fresh for this context and closed
 * with it ({@link DirtiesContext}), and {@link #nothingButTheSeedIsMeasured()} checks before every
 * seed that the seeded tables are empty.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@DirtiesContext
class RequestCostRatchetTest {

    /** Directory of the class's own database file, see the class comment. */
    @TempDir
    static Path ownDatabase;

    @DynamicPropertySource
    static void useOwnDatabase(DynamicPropertyRegistry registry) {
        registry.add("integration.db.path", () -> ownDatabase.resolve("request-cost.db").toString());
        // The class sends well over 120 requests a minute — every route several times per test. The
        // rate limit is not what it measures; with it on, the routes added for TODO 86777fb4 tipped
        // the measurements into 429 answers.
        registry.add("automation.security.rate-limit.enabled", () -> "false");
    }

    /**
     * The tables {@link #seedBatch} writes to, a referenced table before the tables pointing at it —
     * empty before the first batch (see {@link #nothingButTheSeedIsMeasured()}) and emptied again in
     * reverse order after every test ({@link #removeTheSeed()}).
     */
    private static final List<String> SEEDED_TABLES = List.of(
            "projects", "workflows", "workflow_runs", "step_results", "scheduled_workflows", "goals",
            "goal_milestones", "todos", "todo_triggers", "wishes", "audit_reports", "audit_findings");

    /** Seed size — large enough that a per-row query shows up as a multiple, small enough to stay fast. */
    private static final int PROJECTS = 3;
    private static final int WORKFLOWS_PER_PROJECT = 10;
    private static final int RUNS_PER_WORKFLOW = 2;
    private static final int STEPS_PER_RUN = 3;
    private static final int TODOS = 50;
    private static final int WISHES = 10;
    private static final int AUDITS_PER_PROJECT = 2;

    /** A step output big enough that fetching it by accident is visible in the response size. */
    private static final String FAT_STEP_OUTPUT = "x".repeat(20_000);

    /** An audit report's markdown, as big as a step output: in the /audits list it would show in the bytes. */
    private static final String FAT_REPORT_MARKDOWN = "#".repeat(20_000);

    /** The report history of the first seeded audit — the Audit Center's history tab. */
    private static final String AUDIT_HISTORY = "/api/v1/audits/{audit}/reports?projectId={auditProject}&size=50";

    /** Start of every seeded run — a Tuesday. */
    private static final Instant RUN_STARTED = Instant.parse("2026-09-01T10:00:00Z");

    /** Next occurrence of the seeded schedules ({@code 0 0 6 * * MON}): the following Monday. */
    private static final Instant NEXT_SCHEDULED = Instant.parse("2026-09-07T06:00:00Z");

    /** The calendar week holding both: every seeded run and every seeded schedule's next date. */
    private static final String CALENDAR =
            "/api/v1/schedules/calendar?from=2026-09-01T00:00:00Z&to=2026-09-08T00:00:00Z";

    /**
     * A schedule that fires every five minutes, seeded once with more runs than either of its lists
     * sends — {@code /schedules/{busy}/runs} (limit 50) and the calendar (limit 1 000) — so both have
     * to cut and count (TODO 86777fb4). Nearly four days from {@link #BUSY_FROM}, a month before the
     * other runs and outside {@link #CALENDAR}.
     */
    private static final int BUSY_SCHEDULE_RUNS = 1_100;
    private static final Instant BUSY_FROM = Instant.parse("2026-08-03T00:00:00Z");
    private static final String BUSY_CALENDAR =
            "/api/v1/schedules/calendar?from=2026-08-03T00:00:00Z&to=2026-08-10T00:00:00Z";

    /**
     * The dashboard over a window that reaches back past {@link #RUN_STARTED}. Its default window
     * is the last 30 days up to today, and the seed has a fixed date: from 2026-10-02 on, the route
     * would have measured an empty window — cheap, green and blind (TODO 5c56b3d1). A century back
     * holds the seed for as long as this test lives; the queries are the same as for 30 days.
     * {@link #windowedRoutesSeeTheWholeSeed} checks that the window actually holds it.
     */
    private static final String DASHBOARD = "/api/v1/learning/dashboard?days=36500";

    record Cost(int statements, int responseBytes) {}

    /**
     * Route -> ceiling, measured on 2026-09-18 against the seed above.
     *
     * <p>Measured again on 2026-09-25 without the test transaction (TODO 5c56b3d1): every ceiling
     * held to the statement, none had to rise. {@code /learning/dashboard} — the route that had cost
     * 52 instead of 10 statements outside the test transaction — still runs without a transaction
     * of its own, but since TODO 13d1e2a2 the query for its recent runs
     * ({@code findByIdInWithStepsAndWorkflow}) fetches the project's container itself. Inside the
     * test transaction the recent runs had borrowed the containers the window query left in the
     * shared persistence context, so dropping that fetch stayed green here; now it shows.
     *
     * <p><b>Some of these ceilings pin a known defect, not a target.</b> The first run of this test
     * found three N+1 patterns and one unbounded response; the ones still open are pinned so that
     * the gate is green and nothing gets <em>worse</em> while they are fixed, each with its ticket.
     * When a fix lands, drop the ceiling in the same commit —
     * {@code ceilingsThatDriftedTooHighAreReportedAsAHint} prints the new number (that is how
     * {@code /executions} went from 29 statements to 5 when TODO 9db34f3b was fixed).
     *
     * <ul>
     *   <li>{@code /workflows}, {@code /search}, {@code /learning/dashboard}: fixed, 32 → 2,
     *       32 → 2 and 70 → 10 statements. Each paid one {@code select containers} per result row:
     *       {@code Project.container} is a nullable {@code @OneToOne(mappedBy)}, and Hibernate
     *       re-selects it for every row whose project has no container — a found container is
     *       cached, "no container" is not. The queries behind the three routes now fetch the
     *       container along ({@code WorkflowRepository.findAllWithActiveProject},
     *       {@code WorkflowRunRepository.findDashboardWindow} and
     *       {@code findByIdInWithStepsAndWorkflow}, TODO 13d1e2a2) — the same move as
     *       {@code /projects} and {@code /schedules} below. What is left is the query itself plus
     *       the bundled run counts; the dashboard adds its counters and aggregations.</li>
     *   <li>{@code /projects}: fixed, 4 → 1 statement. It was the same root cause as the three
     *       routes above — the nullable {@code @OneToOne(mappedBy)} {@code Project.container},
     *       resolved once per project. The list now runs through
     *       {@code ProjectRepository.findActiveWithContainer()} with a LEFT JOIN FETCH (TODO
     *       6d0ab09f). The ceiling of 4 had been green all along while the same route cost 37
     *       statements on the dev database — which is why this class now also measures growth,
     *       see {@link #statementCountMustNotGrowWithTheNumberOfRows}. 1 → 2 on purpose: the
     *       response carries {@code workflowCount}, counted for all projects by ONE GROUP-BY
     *       query ({@code WorkflowRepository.countWorkflowsGroupedByProject}, TODO 8dabaca8) —
     *       a constant extra statement, not one per project.</li>
     *   <li>{@code /executions}: fixed. The list response dropped from 3 061 816 to 51 716 bytes
     *       when the route stopped serialising every step (TODO a05c9504) — it now answers with
     *       {@code WorkflowRunListItemResponse}, which carries the step count and the last step's
     *       truncated output instead of the steps themselves. What is left is run metadata and
     *       input context; the size no longer grows with step output. Since TODO 7fd9f654 the read
     *       side does not load the step rows either: two queries per page count them and cut the
     *       last output in the database ({@link #runListsLoadNoStepRows}).</li>
     *   <li>{@code /workflows/{wf}/runs}: added with TODO 7fd9f654 — the same list rows as
     *       {@code /executions} instead of every run with every step. {@code {wf}} is the first
     *       seeded workflow.</li>
     *   <li>{@code /search}: the per-hit {@code select workflow_runs} is gone — search now uses the
     *       same batched mapper as {@code /workflows} (51 → 32 statements, TODO 13d1e2a2). The
     *       {@code select containers} per workflow went with the first bullet (32 → 2).</li>
     *   <li>{@code /schedules}: fixed, 91 → 33 → 3 statements. The last 30 were one
     *       {@code select workflows} per schedule, because the EAGER association
     *       {@code ScheduledWorkflow.workflow} is not fetched along by {@code findAll()}; the list
     *       now runs through {@code findAllWithTargets()} with a LEFT JOIN FETCH over workflow,
     *       project, container and chain (TODO c9ec9844). The soft-delete question that blocked
     *       this is answered and pinned: a schedule pointing at a deleted workflow resolves to a
     *       null target instead of throwing — see
     *       {@code ScheduleSoftDeletedWorkflowIntegrationTest}. What is left is the query itself
     *       plus the two bundled run-status queries.</li>
     *   <li>{@code /todos}: 2 → 3 statements and 32 → 42 kB on purpose (TODO dc0566f8). The seed puts
     *       every todo into a goal, and each list row carries its {@code planning} (goal title, rank,
     *       rating, open question). The goal titles come from ONE batched {@code findAllById} per
     *       page in {@code TodoResponseAssembler} — a constant statement, which
     *       {@link #statementCountMustNotGrowWithTheNumberOfRows} keeps constant. 42 → 43 kB with
     *       TODO c6ea8f5c, and only because the seed changed, not the route: every fifth seeded todo
     *       now carries a {@code workflowRunId} and a {@code lastFixRunId} (measured 42 081 bytes).
     *       43 → 44 kB with TODO 259f4f66: {@code planning} carries {@code milestoneId}, and every tenth
     *       seeded todo now sits in a milestone (measured 43 201 bytes); the statement count stays 3.</li>
     *   <li>{@code /goals/{goal}/milestones}: added with TODO 259f4f66. Goal and milestones come from
     *       one entity join, the counts from the same lean {@code GoalTodoRow} query as the detail –
     *       2 statements, and the detail itself stays at 2 although it now carries the milestones.</li>
     *   <li>{@code /goals} and {@code /goals/{goal}}: added with TODO dc0566f8. Both read lean
     *       projections ({@code GoalStatusCounts}, {@code GoalTodoRow}) — no todo description ever
     *       reaches the goal view. The detail is the goal plus one row query (the project names
     *       are only looked up when a todo has a project; the seeded ones have none).</li>
     *   <li>{@code /goals/{goal}/relations}: added with TODO c6ea8f5c. Every fifth seeded todo
     *       points at a run of its batch (origin) and at another one as its last fix run (result),
     *       and each batch adds one active TodoTrigger that matches every seeded todo (worker). The
     *       route reads a lean projection and then one query per kind — goal check, rows, active
     *       triggers, run states, workflow names, audit links (6 statements; no project lookup,
     *       the seeded todos have none) — so the count stays flat when the second batch doubles
     *       runs, triggers and todos.</li>
     *   <li>{@code /schedules/{sched}/runs} and the calendar week: added with TODO 168e3d7f, both
     *       answered with every step of every run — 122 651 bytes for the two runs of one schedule,
     *       3 697 137 bytes for the week with 60 runs. They now send the same list rows as
     *       {@code /executions}. The runs route went 4 → 5 statements on purpose: counting the steps
     *       and cutting the last output takes two queries per list, where loading the step rows
     *       took one. The calendar went 37 → 7: its upcoming dates came from
     *       {@code findSchedulesInRange}, which resolved {@code ScheduledWorkflow.workflow} row by
     *       row until it got the same LEFT JOIN FETCH as {@code findAllWithTargets()}. That only
     *       showed once the seeded schedules carried a {@code nextRunAt} like real ones.
     *       {@code {sched}} is the schedule of the first seeded workflow.</li>
     *   <li>{@code /wishes} and {@code /audits}: added with TODO cda29eab. Their N+1 fixes had only
     *       been proven by counted repository calls in unit tests, so a relapse would have passed
     *       the gate. {@code /wishes} is the list plus one name lookup each for projects and
     *       workflows ({@code WishMapper}, TODO 5bf5a9c8) — 3 statements, measured 7 761 bytes for
     *       ten wishes. {@code /audits} reads two lean projections: the report rows without their
     *       markdown and the finding counts per audit, status and severity in one GROUP BY
     *       ({@code AuditCenterService.listAudits}, TODO 3d7e3987) — 2 statements, measured 3 301
     *       bytes for six audits, whose markdown alone would add 20 000 bytes each. Their
     *       {@code stale} flag (older than 30 days) turns true for the fixed seed on 2026-10-01: one
     *       byte less per audit, the same queries.</li>
     *   <li>{@code /schedules/{busy}/runs} and the busy calendar week: added with TODO 86777fb4. Both
     *       lists had no ceiling — a schedule every five minutes gathers ~26 000 runs in 90 days, and
     *       both would have sent all of them (for the 1 100 seeded busy runs about 630 000 bytes
     *       each). Now they send the newest 50 and 1 000 runs, and count the rest in one more
     *       statement only when the limit is reached: measured 6 statements and 28 727 bytes for the
     *       runs, 6 statements and 573 049 bytes for the week. {@code /schedules} went 20 → 21 kB
     *       only because the busy schedule is one more seeded row (measured 20 395 bytes).</li>
     *   <li>{@code /audits/{audit}/reports}: added with TODO cd50d011. The history of an audit sent
     *       every report with its full markdown — about 41 000 bytes for the two seeded reports of
     *       one audit, 20 000 of them per report. It now reads the same head projection as
     *       {@code /audits}; the text of the report on screen comes from {@code /audit-reports/{id}}.
     *       Measured 1 376 bytes; 3 statements: the page, the finding counts in one GROUP BY and the
     *       run origins (no chain and no version on the seeded runs, so no further lookups).</li>
     * </ul>
     */
    static final Map<String, Cost> BASELINE = new TreeMap<>(Map.ofEntries(
            Map.entry("/api/v1/todos", new Cost(3, 44_000)),
            Map.entry("/api/v1/projects", new Cost(2, 1_200)),
            Map.entry("/api/v1/workflows", new Cost(2, 26_000)),
            Map.entry("/api/v1/executions", new Cost(5, 60_000)),
            Map.entry("/api/v1/workflows/{wf}/runs", new Cost(4, 5_000)),
            Map.entry(DASHBOARD, new Cost(10, 650_000)),
            Map.entry("/api/v1/search?q=Workflow", new Cost(2, 18_000)),
            Map.entry("/api/v1/schedules", new Cost(3, 21_000)),
            Map.entry("/api/v1/schedules/{sched}/runs", new Cost(5, 5_000)),
            Map.entry("/api/v1/schedules/{busy}/runs", new Cost(6, 32_000)),
            Map.entry("/api/v1/goals", new Cost(2, 2_000)),
            Map.entry("/api/v1/goals/{goal}", new Cost(2, 40_000)),
            Map.entry("/api/v1/goals/{goal}/relations", new Cost(6, 35_000)),
            Map.entry("/api/v1/goals/{goal}/milestones", new Cost(2, 2_000)),
            Map.entry("/api/v1/wishes", new Cost(3, 9_000)),
            Map.entry("/api/v1/audits", new Cost(2, 4_000)),
            Map.entry(AUDIT_HISTORY, new Cost(3, 2_000)),
            Map.entry(CALENDAR, new Cost(7, 95_000)),
            Map.entry(BUSY_CALENDAR, new Cost(6, 600_000))));

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private ScheduledWorkflowRepository scheduledWorkflowRepository;
    @Autowired private GoalRepository goalRepository;
    @Autowired private GoalMilestoneRepository milestoneRepository;
    @Autowired private TodoTriggerRepository todoTriggerRepository;
    @Autowired private WishRepository wishRepository;
    @Autowired private AuditReportRepository auditReportRepository;
    @Autowired private AuditFindingRepository auditFindingRepository;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private ObjectMapper objectMapper;

    /** Target of {@code {wf}} in a route: the first workflow of the first seed batch. */
    private String firstWorkflowId;

    /** Target of {@code {sched}} in a route: that workflow's schedule. */
    private String firstScheduleId;

    /** Target of {@code {busy}} in a route: the schedule of {@link #seedBusySchedule}, on the last workflow of the first batch. */
    private String busyScheduleId;
    private String busyWorkflowId;
    /** Target of {@code {audit}} and {@code {auditProject}}: the first seeded audit and its project. */
    private String firstAuditKey;
    private String firstAuditProjectId;
    /** Das Ziel der ersten Charge; jede Charge ordnet ihm ihre Todos zu (TODO dc0566f8). */
    private String firstGoalId;

    @BeforeEach
    void seed() {
        nothingButTheSeedIsMeasured();
        seedBatch("a");
        seedBusySchedule();
    }

    /**
     * {@link #BUSY_SCHEDULE_RUNS} runs of one schedule, five minutes apart and without steps – seeded
     * once, not per batch: the growth criterion doubles the batches, and the busy routes have to stay
     * flat while it does. The runs belong to the last workflow of the first batch, so that
     * {@code /workflows/{wf}/runs} keeps measuring the first one.
     */
    private void seedBusySchedule() {
        transactions.executeWithoutResult(status -> {
            Workflow workflow = workflowRepository.getReferenceById(busyWorkflowId);
            busyScheduleId = scheduledWorkflowRepository.save(ScheduledWorkflow.builder()
                    .workflow(workflow)
                    .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                    .cronExpression("0 */5 * * * *")
                    .scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                    .enabled(true)
                    .description("Seeded busy schedule for the request cost ratchet")
                    .inputContext(Map.of())
                    .build()).getId();
            List<WorkflowRun> runs = new ArrayList<>();
            for (int r = 0; r < BUSY_SCHEDULE_RUNS; r++) {
                Instant started = BUSY_FROM.plusSeconds(300L * r);
                runs.add(WorkflowRun.builder()
                        .workflow(workflow)
                        .triggeredByScheduleId(busyScheduleId)
                        .status(RunStatus.COMPLETED)
                        .startedAt(started)
                        .completedAt(started.plusSeconds(5))
                        .durationMs(5_000L)
                        .inputContext(Map.of())
                        .metadata(Map.of())
                        .build());
            }
            runRepository.saveAll(runs);
        });
    }

    /**
     * Raeumt die Saat wieder ab. Ohne Testtransaktion rollt nichts mehr zurueck: Was
     * {@link #seedBatch} committet hat, laege sonst im naechsten Test unter dessen Saat. Geloescht
     * wird per SQL und in umgekehrter Reihenfolge von {@link #SEEDED_TABLES}, also jede Tabelle vor
     * der, auf die sie zeigt; per SQL auch deshalb, weil {@code @SQLRestriction} weich geloeschte
     * Workflows vor dem Repository verbirgt.
     */
    @AfterEach
    void removeTheSeed() {
        for (String table : SEEDED_TABLES.reversed()) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    /**
     * Stellt den Bestand vor der Saat fest: In den Tabellen, die die Saat beschreibt, darf nichts
     * stehen. Gezaehlt wird per SQL, damit auch weich geloeschte Workflows auffallen, die
     * {@code @SQLRestriction} vor dem Repository verbirgt. Schlaegt das fehl, misst die Klasse nicht
     * mehr gegen ihre eigene Datenbank, {@link #removeTheSeed} hat eine Tabelle vergessen — oder ein
     * Start-Seeder legt neuerdings Zeilen an; alles das verschiebt jede Obergrenze in
     * {@link #BASELINE}, ohne dass sich eine Route geaendert haette.
     */
    private void nothingButTheSeedIsMeasured() {
        Map<String, Long> foreignRows = new TreeMap<>();
        for (String table : SEEDED_TABLES) {
            long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            if (rows > 0) {
                foreignRows.put(table, rows);
            }
        }
        assertThat(foreignRows)
                .as("Rows the seed did not write sit in the measured tables — the ceilings would "
                        + "measure them too. Is the class still on its own database?")
                .isEmpty();
    }

    /**
     * Eine Saat-Charge: {@link #PROJECTS} Projekte mit je {@link #WORKFLOWS_PER_PROJECT}
     * Workflows, deren Runs und Steps, einem Zeitplan je Workflow, {@link #TODOS} Todos,
     * {@link #WISHES} Wuensche und {@link #AUDITS_PER_PROJECT} Audits je Projekt.
     *
     * <p>Ein zweiter Aufruf verdoppelt jede dieser Tabellen — genau darauf beruht
     * {@link #statementCountMustNotGrowWithTheNumberOfRows}. Alle Namen und Pfade tragen die
     * Chargen-Kennung, damit die zweite Charge neue Zeilen anlegt statt die erste zu treffen.
     *
     * <p>Die Charge entsteht in einer eigenen Transaktion und wird committet: Gemessen wird danach
     * ohne Transaktion, gegen das, was wirklich in der Datenbank steht (TODO 5c56b3d1).
     */
    private void seedBatch(String batch) {
        transactions.executeWithoutResult(status -> writeBatch(batch));
    }

    private void writeBatch(String batch) {
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

        if (firstWorkflowId == null) {
            firstWorkflowId = workflows.get(0).getId();
            busyWorkflowId = workflows.getLast().getId();
        }
        // Ein Zeitplan je Workflow, wie im Betrieb mit naechstem Termin, und die Runs des Workflows
        // tragen seine Id: So misst /schedules/{sched}/runs echte Zeitplan-Runs, und der Kalender
        // findet in seiner Woche beides, die Termine und die vergangenen Runs (TODO 168e3d7f).
        // Die Route /schedules laedt ueber ScheduledWorkflow.workflow (EAGER) und Workflow.project
        // (EAGER). Faellt der JOIN FETCH aus findAllWithTargets() weg, loest Hibernate die
        // Assoziationen wieder Zeile fuer Zeile auf und die Zahl der Statements springt sichtbar
        // auf ein Vielfaches von 30 (TODO 172a6a67, c9ec9844).
        List<String> runIds = new ArrayList<>();
        for (Workflow workflow : workflows) {
            ScheduledWorkflow schedule = scheduledWorkflowRepository.save(ScheduledWorkflow.builder()
                    .workflow(workflow)
                    .targetType(ScheduledWorkflow.TargetType.WORKFLOW)
                    .cronExpression("0 0 6 * * MON")
                    .scheduleType(ScheduledWorkflow.ScheduleType.CRON)
                    .enabled(true)
                    .nextRunAt(NEXT_SCHEDULED)
                    .description("Seeded for the request cost ratchet")
                    .inputContext(Map.of())
                    .build());
            if (firstScheduleId == null) {
                firstScheduleId = schedule.getId();
            }

            for (int r = 0; r < RUNS_PER_WORKFLOW; r++) {
                WorkflowRun run = runRepository.save(WorkflowRun.builder()
                        .workflow(workflow)
                        .triggeredByScheduleId(schedule.getId())
                        .status(RunStatus.COMPLETED)
                        .startedAt(RUN_STARTED)
                        .completedAt(RUN_STARTED.plusSeconds(5))
                        .durationMs(5_000L)
                        .inputContext(Map.of())
                        .metadata(Map.of())
                        .build());
                runIds.add(run.getId());

                for (int s = 0; s < STEPS_PER_RUN; s++) {
                    stepResultRepository.save(StepResult.builder()
                            .workflowRun(run)
                            .stepIndex(s)
                            .status(StepStatus.COMPLETED)
                            .input("seeded input")
                            .output(FAT_STEP_OUTPUT)
                            .startedAt(RUN_STARTED)
                            .completedAt(RUN_STARTED.plusSeconds(1))
                            .durationMs(1_000L)
                            .build());
                }
            }
        }

        // Ziele (TODO dc0566f8): jede Charge legt ein Ziel an und ordnet ihre Todos dem ERSTEN zu –
        // so waechst die Zielansicht mit der zweiten Charge, und /goals zaehlt zwei Ziele. Beide
        // Routen lesen schlanke Projektionen (GoalStatusCounts, GoalTodoRow); faellt eine davon auf
        // Entities zurueck, zeigt es sich hier als Vielfaches.
        String goalId = goalRepository.save(Goal.builder().title("Cost Goal " + batch).build()).getId();
        if (firstGoalId == null) {
            firstGoalId = goalId;
        }
        // Relationskarte (TODO c6ea8f5c): jedes fuenfte Todo stammt aus einem Lauf und hat einen
        // Fix-Lauf, und ein aktiver Trigger je Charge passt auf alle – so muss /relations Laeufe,
        // Workflow-Namen und Trigger nachschlagen, und ein Nachschlagen je Todo faellt als Wachstum auf.
        todoTriggerRepository.save(TodoTrigger.builder()
                .tagPattern("")
                .targetWorkflow(workflows.get(0))
                .description("Cost Trigger " + batch)
                .build());
        // Meilensteine (TODO 259f4f66): jede Charge legt zwei am ersten Ziel an und ordnet jedes zehnte
        // Todo einem zu – Detail und Meilenstein-Liste muessen sie mit dem Ziel in einer Abfrage holen.
        List<String> milestoneIds = new ArrayList<>();
        for (int m = 0; m < 2; m++) {
            milestoneIds.add(milestoneRepository.save(GoalMilestone.builder().goalId(firstGoalId)
                    .title("Cost Milestone " + batch + m).dueDate(RUN_STARTED.plusSeconds(86_400L * (m + 1))).build()).getId());
        }
        for (int t = 0; t < TODOS; t++) {
            boolean fromRun = t % 5 == 0;
            todoRepository.save(Todo.builder()
                    .milestoneId(t % 10 == 0 ? milestoneIds.get(t / 10 % 2) : null)
                    .goalId(firstGoalId)
                    .goalRank(t % 2 == 0 ? t + 1 : null)
                    .title("Cost Todo " + batch + t)
                    .description("Seeded for the request cost ratchet")
                    .status(Todo.Status.OPEN)
                    .priority(Todo.Priority.MEDIUM)
                    .tags("performance,testing")
                    .workflowRunId(fromRun ? runIds.get(t % runIds.size()) : null)
                    .lastFixRunId(fromRun ? runIds.get((t + 1) % runIds.size()) : null)
                    .fixAttempts(fromRun ? 1 : 0)
                    .build());
        }
        seedWishes(batch, workflows);
        seedAudits(batch, workflows, runIds);
    }

    /**
     * Wuensche (TODO cda29eab), gleichmaessig ueber die Workflows der Charge und damit ueber ihre
     * Projekte verteilt: Jeder hat ein Projekt, zwei erzeugte Workflows und eine Vorlage. GET
     * /wishes loest Projekt- und Workflow-Namen fuer die ganze Liste in je einer Abfrage auf
     * ({@code WishMapper}, TODO 5bf5a9c8) — ein Nachschlagen je Wunsch faellt als Wachstum auf.
     */
    private void seedWishes(String batch, List<Workflow> workflows) {
        for (int w = 0; w < WISHES; w++) {
            int first = w * workflows.size() / WISHES;
            Workflow generated = workflows.get(first);
            wishRepository.save(Wish.builder()
                    .originalText("Cost Wish " + batch + w)
                    .status(WishStatus.COMPLETED)
                    .projectId(generated.getProject().getId())
                    .generatedWorkflowIds(List.of(generated.getId(),
                            workflows.get((first + 1) % workflows.size()).getId()))
                    .referenceWorkflowIds(List.of(workflows.get((first + 2) % workflows.size()).getId()))
                    .iterationCount(1)
                    .resultSummary("Seeded for the request cost ratchet")
                    .build());
        }
    }

    /**
     * Audits (TODO cda29eab): je Projekt {@link #AUDITS_PER_PROJECT}, jedes mit zwei gueltigen
     * Reports — der neueste und sein Vorgaenger fuer den Trend — und drei Findings, eins davon
     * erledigt. GET /audits liest zwei schlanke Projektionen, die Reports ohne Markdown und die
     * Findings als GROUP BY je Audit, Status und Schwere ({@code AuditCenterService.listAudits},
     * TODO 3d7e3987). Das Markdown ist absichtlich fett: Geraet es in die Liste, zeigen es die Bytes.
     */
    private void seedAudits(String batch, List<Workflow> workflows, List<String> runIds) {
        int nextRun = 0;
        for (int p = 0; p < PROJECTS; p++) {
            Workflow auditWorkflow = workflows.get(p * WORKFLOWS_PER_PROJECT);
            String projectId = auditWorkflow.getProject().getId();
            for (int a = 0; a < AUDITS_PER_PROJECT; a++) {
                String auditKey = "cost-audit-" + batch + p + "-" + a;
                if (firstAuditKey == null) {
                    firstAuditKey = auditKey;
                    firstAuditProjectId = projectId;
                }
                List<AuditReport> reports = new ArrayList<>();
                for (int r = 0; r < 2; r++) {
                    reports.add(auditReportRepository.save(AuditReport.builder()
                            .projectId(projectId)
                            .workflowId(auditWorkflow.getId())
                            .workflowRunId(runIds.get(nextRun++))
                            .auditKey(auditKey)
                            .category("quality")
                            .title("Cost Audit " + batch + p + "-" + a)
                            .markdownContent(FAT_REPORT_MARKDOWN)
                            .gateStatus(r == 0 ? GateStatus.YELLOW : GateStatus.GREEN)
                            .ingestionStatus(AuditIngestionStatus.VALID)
                            .generatedAt(RUN_STARTED.plusSeconds(3_600L * r))
                            .build()));
                }
                AuditReport previous = reports.getFirst();
                AuditReport latest = reports.getLast();
                List<AuditSeverity> severities = List.of(AuditSeverity.HIGH, AuditSeverity.MEDIUM, AuditSeverity.LOW);
                for (int f = 0; f < severities.size(); f++) {
                    auditFindingRepository.save(AuditFinding.builder()
                            .projectId(projectId)
                            .auditKey(auditKey)
                            .fingerprint("cost-finding-" + f)
                            .ruleKey("cost-rule")
                            .subject("Cost subject " + f)
                            .severity(severities.get(f))
                            .title("Cost Finding " + batch + p + "-" + a + "-" + f)
                            .status(f == severities.size() - 1 ? AuditFindingStatus.RESOLVED : AuditFindingStatus.OPEN)
                            .firstSeenReportId(previous.getId())
                            .lastSeenReportId(latest.getId())
                            .firstSeenRunId(previous.getWorkflowRunId())
                            .lastSeenRunId(latest.getWorkflowRunId())
                            .firstSeenAt(previous.getGeneratedAt())
                            .lastSeenAt(latest.getGeneratedAt())
                            .build());
                }
            }
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
     *
     * <p>Zurzeit leer: {@code /workflows}, {@code /search} und {@code /learning/dashboard} standen
     * hier, bis ihr {@code select containers} je Zeile behoben war (TODO 13d1e2a2). Seitdem gilt
     * das Wachstums-Kriterium fuer alle Routen.
     */
    static final Map<String, String> KNOWN_GROWING = new TreeMap<>();

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
     * <p>Gemessen wird erst mit einer Saat-Charge, dann mit zweien, und beide Male ohne
     * umschliessende Transaktion: Jede Anfrage beginnt ohne Persistenzkontext, die zweite Messung
     * findet also keine Zeile der ersten im First-Level-Cache, hinter dem sich ein N+1 verstecken
     * koennte.
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

    /**
     * Two routes show a time window only: the dashboard the days up to today, the calendar the week
     * of {@link #CALENDAR}. With the seed outside its window a route measures nothing — below every
     * ceiling, flat in the growth criterion, and blind to any N+1 (the dashboard's default window
     * would have lost the seed on 2026-10-02, TODO 5c56b3d1). Each of them therefore has to show the
     * whole seed, not just something.
     */
    @Test
    void windowedRoutesSeeTheWholeSeed() throws Exception {
        int seededRuns = PROJECTS * WORKFLOWS_PER_PROJECT * RUNS_PER_WORKFLOW;

        JsonNode dashboard = json(DASHBOARD);
        int runsInWindow = 0;
        for (JsonNode day : dashboard.path("timeSeries")) {
            runsInWindow += day.path("count").asInt();
        }
        assertThat(runsInWindow).as("seeded runs in the dashboard window").isEqualTo(seededRuns + BUSY_SCHEDULE_RUNS);
        assertThat(dashboard.path("recentRuns").size()).as("recent runs on the dashboard").isPositive();

        JsonNode calendar = json(CALENDAR);
        assertThat(calendar.path("pastRuns").size()).as("seeded runs in the calendar week")
                .isEqualTo(seededRuns);
        assertThat(calendar.path("upcoming").size()).as("seeded schedules due in the calendar week")
                .isEqualTo(PROJECTS * WORKFLOWS_PER_PROJECT);
        assertThat(calendar.path("pastRunsTotal").asInt()).isEqualTo(seededRuns);
    }

    /**
     * The busy schedule has more runs than its lists send (TODO 86777fb4): each list sends the newest
     * up to its limit and says how many there are — otherwise its ceiling would measure a list the
     * limit never cut.
     */
    @Test
    void busyListsSendTheNewestAndCountTheRest() throws Exception {
        JsonNode busyWeek = json(BUSY_CALENDAR);
        assertThat(busyWeek.path("pastRuns").size()).as("runs the calendar sends").isEqualTo(1_000);
        assertThat(busyWeek.path("pastRunsTotal").asInt()).as("runs of the busy week").isEqualTo(BUSY_SCHEDULE_RUNS);
        assertThat(busyWeek.path("pastRuns").get(0).path("startedAt").asText())
                .as("newest first").isEqualTo(BUSY_FROM.plusSeconds(300L * (BUSY_SCHEDULE_RUNS - 1)).toString());

        JsonNode busyRuns = json("/api/v1/schedules/{busy}/runs");
        assertThat(busyRuns.path("workflowRuns").size()).as("runs the schedule list sends").isEqualTo(50);
        assertThat(busyRuns.path("workflowRunsTotal").asInt()).isEqualTo(BUSY_SCHEDULE_RUNS);
    }

    private JsonNode json(String route) throws Exception {
        MvcResult result = mockMvc.perform(get(resolve(route))).andReturn();
        assertThat(result.getResponse().getStatus()).as("%s", route).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * The run lists show a step count and a preview of the last output — and must get both without
     * loading a step row (TODO 7fd9f654). Two checks: Hibernate loads no {@link StepResult} while the
     * request runs, and the response does not grow when every step output grows tenfold.
     *
     * <p>The loads are counted by Hibernate's statistics, switched on for this test only. They are
     * global, not per thread — which is fine here: the integration profile runs no scheduled jobs
     * ({@code SchedulingConfig}), so nothing but the request loads a step while it is measured.
     * The persistence context cannot tell any more: without a surrounding transaction, whatever
     * the request loaded is gone with it.
     */
    @Test
    void runListsLoadNoStepRows() throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsWereEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            // The probe first: a step loaded on purpose has to show up, or the zero below means nothing.
            long probe = stepRowsLoaded(statistics);
            stepResultRepository.findAll(PageRequest.of(0, 1));
            assertThat(stepRowsLoaded(statistics))
                    .as("Hibernate's statistics do not count step loads — the check below would be blind")
                    .isGreaterThan(probe);

            for (String route : List.of("/api/v1/executions", "/api/v1/workflows/{wf}/runs",
                    "/api/v1/schedules/{sched}/runs", CALENDAR)) {
                long stepsLoadedBefore = stepRowsLoaded(statistics);
                Cost before = measure(route);
                assertThat(stepRowsLoaded(statistics) - stepsLoadedBefore)
                        .as("%s loaded step rows — the list must count and preview them in the database", route)
                        .isZero();

                MvcResult result = mockMvc.perform(get(resolve(route))).andReturn();
                String body = result.getResponse().getContentAsString();
                assertThat(body).contains("\"stepCount\":" + STEPS_PER_RUN)
                        .contains("\"lastStepStatus\":\"COMPLETED\"")
                        .contains("\"lastStepPreview\":\"" + "x".repeat(WorkflowRunListItemResponse.PREVIEW_LENGTH) + " […]\"");

                jdbc.update("UPDATE step_results SET output = ?", FAT_STEP_OUTPUT.repeat(10));
                Cost after = measure(route);
                assertThat(after.responseBytes())
                        .as("%s grew with the step output — something serialises or reads the full output", route)
                        .isEqualTo(before.responseBytes());
                assertThat(after.statements()).isEqualTo(before.statements());
            }
        } finally {
            statistics.setStatisticsEnabled(statisticsWereEnabled);
        }
    }

    /** Step rows Hibernate has loaded or fetched since the statistics were switched on. */
    private static long stepRowsLoaded(Statistics statistics) {
        EntityStatistics steps = statistics.getEntityStatistics(StepResult.class.getName());
        return steps.getLoadCount() + steps.getFetchCount();
    }

    private String resolve(String route) {
        return route.replace("{wf}", firstWorkflowId).replace("{sched}", firstScheduleId).replace("{busy}", busyScheduleId)
                .replace("{goal}", firstGoalId).replace("{audit}", firstAuditKey)
                .replace("{auditProject}", firstAuditProjectId);
    }

    private Cost measure(String route) throws Exception {
        // The request has to open its own transactions, or none — exactly as in production. Inside
        // an open transaction it would share that transaction's persistence context, and a service
        // without a transaction of its own would look as cheap as one with (TODO 5c56b3d1).
        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("%s would be measured inside an open transaction — the request would share its "
                        + "persistence context, which it never has in production", route)
                .isFalse();

        SqlStatementCounter.start();
        MvcResult result;
        int statements;
        try {
            result = mockMvc.perform(get(resolve(route))).andReturn();
            statements = SqlStatementCounter.count();
        } finally {
            SqlStatementCounter.stop();
        }

        assertThat(result.getResponse().getStatus())
                .as("%s must answer 200 for the measurement to mean anything (answer: %s)", route,
                        result.getResponse().getContentAsString())
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
