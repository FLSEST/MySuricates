# Development

This guide is about working **on** the MySuricates code — building, testing, shipping. If you
only want to use the platform, start at [GETTING-STARTED.md](GETTING-STARTED.md).

> Short identifiers in parentheses such as `(182bd224)` or `TODO 63ecc124` are
> provenance markers: commits of this repository, or items in the accompanying
> MySuricates instance. They record when and why something came about — from the
> outside, only the commit can be looked up.

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java (JDK) | 21+ | `java --version` |
| Maven | optional (wrapper `mvnw` included) | `./mvnw --version` |
| Node.js | 20.19+, 22.12+ or 24+ (`.nvmrc`) | `node --version` |
| npm | 10+ | `npm --version` |
| Docker | 24+ (optional) | `docker --version` |

Docker is only needed for container-execution workflow blocks — the backend boots even without a running Docker daemon (the Docker client is initialized lazily). For first-time setup on a new machine, see **[GETTING-STARTED.md](GETTING-STARTED.md)** (includes `scripts/setup.sh`).

## Local Setup

### Starting the Backend

```bash
# From the repo root — builds a fat JAR, copies it to ~/.local/state/autotrigger-dev/runtime/
# and starts it from there (port 9090). Idempotent; also starts the frontend.
scripts/start-autotrigger-dev.sh            # start
scripts/start-autotrigger-dev.sh restart    # apply code changes: stop → build JAR → start
scripts/start-autotrigger-dev.sh stop

# Runtime: host (default) or container — the same commands (TODO a1236a64)
scripts/start-autotrigger-dev.sh start --container
AUTOTRIGGER_DEV_RUNTIME=container scripts/start-autotrigger-dev.sh   # permanently
```

**Host or container?** The host way is the default and unchanged. `--container` starts the backend
through `docker compose` from `ai-automation-backend/docker-compose.dev-container.yml` instead (image
`Dockerfile.stable`, so with Node — the dev `docker-compose.yml` builds from a JRE Alpine image
**without** Node, where no AI CLI runs). The Vite dev server stays on the host either way. Tunable via
`AUTOTRIGGER_DEV_CONTAINER_PORT`, `AUTOTRIGGER_DEV_CONTAINER_NAME`, `AUTOTRIGGER_DEV_COMPOSE_PROJECT`,
`AUTOTRIGGER_DEV_PROJECT_ROOT`, `AUTOTRIGGER_DEV_PRESET` — so a probe instance can run next to the real
one. A probe on another port also needs `MYSURICATES_API_BASE=http://localhost:<port>/api/v1`,
otherwise the starter checks port 9090.

What of the machine's AI providers goes into the container is set by `AUTOTRIGGER_DEV_AI_FROM_HOST`
(`login` = the programs and just the login of each provider, the default; `programs`; `all` = whole
provider directories as before), plus `AUTOTRIGGER_DEV_AI_PROVIDERS` (selection from
`claude,codex,pi`) and `AUTOTRIGGER_DEV_DOCKER_SOCKET` (default `1`). Claude needs a one-time
`claude setup-token` for it — background in
[deployment.md → AI providers in the container](deployment.md#ai-providers-in-the-container). The
"Startup configuration" card in the settings shows what the container runs with.

> Do **not** use `mvn spring-boot:run` — the command is **deliberately blocked**: the backend aborts
> at startup from `target/classes` with a message (`RuntimeClasspathGuard`), see
> [Builds next to the running dev backend](#builds-next-to-the-running-dev-backend).
> Escape hatch for special cases: `-Dautomation.runtime.allow-classes-dir=true`.

The backend automatically creates `data/automation.db` on first start and runs Flyway migrations.

### Starting the Frontend

```bash
cd ai-automation-frontend

# Install dependencies
npm install

# Start dev server (port 3000)
npm run dev
```

The Vite dev server automatically proxies `/api/*` and `/ws/*` to `localhost:9090`.

### Starting Both in Parallel

Terminal 1 (Backend):
```bash
scripts/start-autotrigger-dev.sh
```

Terminal 2 (Frontend):
```bash
cd ai-automation-frontend && npm run dev
```

Open: `http://localhost:3000`

## Build

### Backend

```bash
cd ai-automation-backend

# Compile
mvn clean compile

# Build JAR (without tests)
mvn clean package -DskipTests

# Build JAR (with tests)
mvn clean package

# Output: target/ai-automation-backend-0.7.3-SNAPSHOT.jar
```

**Docs on the classpath:** `src/main/resources/docs/` (architektur, WORKFLOW-GUIDE, audit-workflow-protocol,
assistant-system, vision) is a build artifact — the `maven-resources-plugin` copies the files from the
repo-root `docs/` during `generate-resources`. The directory is git-ignored; edit `docs/` only. The
assistant reads the copy at runtime via `ClassPathResource`.

### Frontend

```bash
cd ai-automation-frontend

# TypeScript check + Vite build
npm run build

# Output: dist/ (static files)
```

### Docker Images

```bash
cd ai-automation-backend

# Backend image
mvn clean package -DskipTests
docker compose build backend

# All agent images
docker compose --profile build-only build
```

## Builds next to the running dev backend

The dev backend runs from a **JAR copy** at `~/.local/state/autotrigger-dev/runtime/current.jar`
(`scripts/start-autotrigger-dev.sh`), not from `target/classes`. Reason: a JVM with `-cp target/classes`
lazily loads classes from disk for its entire lifetime. Any build that rewrites this directory pulls
the classes out from under it → `NoClassDefFoundError`, sticky (the JVM caches the resolution
failure), only a restart heals it. There were three writers: Maven from workflows/sessions
(2026-07-27: eleven dead runs), `staging.sh`, and the **IDE** — VS Code's Java extension (Eclipse
compiler) deleted the javac helper classes for enum switches overnight on 2026-09-13 (three dead
scheduled runs) and replaced MapStruct implementations with error stubs the following morning (498
false test errors).

Three protection layers, all mechanical:

1. **JAR copy** — `restart` builds `mvn clean package -DskipTests`, copies the JAR into the state
   folder (name `backend-<time>-<sha>[-dirty].jar`, the last two are kept) and starts `java -jar` with
   cwd `ai-automation-backend` (`./data/automation.db` stays put). `restart` checks via the API
   beforehand whether workflow runs are active and aborts if so (`--force` overrides).
2. **IDE profile** — the `pom.xml` profile `ide-eclipse-output` (active only when m2e sets the
   `m2e.version` property) places the IDE build under `target-ide/`. VS Code/Eclipse therefore never
   write into `target/`. Check with: `mvn help:evaluate -Dexpression=project.build.directory -DforceStdout -Dm2e.version=1.0`.
3. **Fail-fast** — `RuntimeClasspathGuard` in `AiAutomationApplication.main` aborts a start from
   `target/classes` with exit code 2. `/api/v1/system/info` shows `runtimeMode` (`jar` |
   `classes-dir`); in the escape-hatch case (`-Dautomation.runtime.allow-classes-dir=true` or
   `AUTOMATION_ALLOW_CLASSES_DIR=true`) the ActivityLog warns on startup.

This makes `mvn test`, `mvn clean`, and `npm run build` in the main checkout harmless for the running
backend. **For workflows, the QA worktree remains the right place**, because two concurrent Maven
runs in the same `target/` (e.g. a scheduled run + a Claude session) produce incorrect results for
each other:

```bash
WT=$(scripts/qa-worktree.sh prepare)   # creates/syncs ~/.mysuricates/qa-worktree/autotrigger to HEAD
cd "$WT/ai-automation-backend" && mvn test
```

The worktree is detached at the main checkout's `HEAD` and persists between runs (`target/` and
`node_modules/` are reused). **Uncommitted changes are not included** — whatever runs there tests
the committed state. The "AutoPilot Backend Quality Gate" and "self-care-platform-health" workflows
use exactly this worktree.

## Tests

### Backend Tests

```bash
cd ai-automation-backend

# Run all tests
mvn test

# Single test class
mvn test -Dtest=ProjectControllerTest

# Tests with coverage
mvn test jacoco:report
```

The tests use `spring-boot-starter-test` (JUnit 5, MockMvc) and `spring-security-test`. Each controller has its own test class in `src/test/java/com/automation/controller/`.

**Targeted test selection and architecture ratchets:** A topic-scoped `-Dtest` selection (e.g.
`*Todo*,ControllerModuleGateTest`) does not automatically cover `PackageCycleRatchetTest`,
`LayerRuleTest` and `SourceSizeRatchetTest` — these three measure the compiled classes via jdeps or
file sizes and only catch anything when they actually run. Whoever creates a new class, moves a
class between packages, or renames a package runs them additionally — either targeted
(`mvn test -Dtest='PackageCycleRatchetTest,LayerRuleTest,SourceSizeRatchetTest'`) or via
`bash scripts/tests/arch-guard.sh` (runs exactly these three and reports the runtime). Without this
extra run, a new package cycle or layer violation only surfaces at the full integration checkpoint,
not already on the feature branch (seen on 2026-09-18: a new cycle between `com.automation.event`
and `com.automation.modules` had to be fixed after the fact on main because the implementer's
targeted test selection did not include the three classes).

**Targeted run against a class with `@Nested` tests:** 73 test classes in the repo (as of
2026-09-18, `grep -rl "@Nested" --include="*.java" . | grep -v .claude/worktrees | grep /src/test/ | wc -l`)
use `@Nested`. Convention: use `-Dtest='Class*'` instead of `-Dtest='Class'` — the wildcard form is
guaranteed to also match every `@Nested` inner class and stays reliable regardless of the Surefire
version/configuration. **Warning:** whichever form you pick, the outer class's own text report
(`target/surefire-reports/<Class>.txt`) always reports `Tests run: 0` for the outer class itself,
because it has no `@Test` methods of its own — that is normal, not empty. A run whose `@Nested`
tests genuinely did not execute for some other reason can still look "green" from that single line
if you only check it, or a truncated `tail`. What actually matters is the final
`Results: Tests run: N` line of the whole Maven run, or its exit code.

**`ERROR` lines in a green run:** Many tests exercise failure paths — a mock throws, the code
catches it and correctly logs it at `ERROR`, stack trace included. A green run therefore writes
around a hundred `ERROR` lines and several thousand stack-trace lines; none of them is a failure.
CI therefore redirects each test class's output to
`target/surefire-reports/<Class>-output.txt` (`-Dmaven.test.redirectTestOutputToFile=true`) and
uploads that directory as the artifact `backend-surefire-reports` only when the run fails.
Locally the same option works; it is deliberately not the default in `pom.xml`, because the
`mysuricates-health-check` workflow greps `target/surefire-reports/*.txt` for `ERROR`.

**Class-size ratchet:** `architecture/SourceSizeRatchetTest` counts the lines of every file under `src/main/java` and fails as soon as a file exceeds 300 lines without being on the test's allowlist — or a listed file grows past its recorded ceiling. The list only moves one way: whoever shrinks a listed class lowers its ceiling; once it drops to ≤300 lines the entry is removed. New files above 300 lines get split, not listed (audit TODO 1e248ab9). No external dependency, plain JUnit.

**Request cost ratchet:** `architecture/RequestCostRatchetTest` measures two **deterministic** costs for the routes the UI hits on every page load (`/todos`, `/workflows`, `/projects`, `/executions`, `/learning/dashboard`, `/search`) and compares them against the `BASELINE` map in the test: the number of SQL statements and the response size in bytes. No milliseconds — wall-clock time on a developer machine depends on host load, JIT warm-up and who currently holds the single SQLite connection, so it would flake. The two pinned costs are reproducible and are exactly the ones that went wrong before: an N+1 turns 3 statements into 33 (`1e465a9e perf(api)`), a fetched TEXT blob turns a small response into a huge one (`443fa57e perf(dashboard)`). Counting goes through a Hibernate `StatementInspector` (`metrics/CountingStatementInspector` → `metrics/SqlStatementCounter`, ThreadLocal, registered in `config/StatementInspectorConfig`); the test clears the persistence context before each measurement, otherwise the request would find the seeded entities in the first-level cache and a lazy N+1 would stay invisible. The test measures against a database file of its own, freshly migrated, instead of the one the test JVM shares: rows another integration test leaves behind without a rollback would otherwise shift every ceiling — in one `-Dtest` selection two projects and three workflows were enough for a red run (TODO 5695d482); before every seed it checks that the seeded tables are empty. Like the class-size ratchet the list only moves one way: ceilings drop when a route gets cheaper (the hint test prints the new number). **Several ceilings currently pin a known defect, not a target** — the first run found three N+1 patterns and one unbounded response (TODOs 13d1e2a2, 9db34f3b, a05c9504); they are pinned so the gate is green and nothing gets *worse* while they are fixed. Raising a ceiling needs a reason in the commit message.

**Measuring request cost in production:** `RequestCostFilter` (package `metrics`, ordered outermost so authentication and rate limiting are included) records four numbers for every `/api/v1` request: total duration, **time waited for the DB connection**, SQL statements and response bytes. The key is the route template (`GET /api/v1/todos/{id}`), never the URL with the id — otherwise every id becomes its own metric. The wait share is the most important number under `maximum-pool-size: 1`: is a view slow because its own query is expensive, or because another request or a workflow step holds the single connection? That is measured in `TimingDataSource`, a `DelegatingDataSource` around `getConnection()` (registered by `DataSourceTimingPostProcessor`; `unwrap`/`isWrapperFor` stay transparent, or `ConnectionPoolWatchdogService` would lose its pool handle). `RequestMetricsCollector` keeps a bounded window per route and reports **percentiles, never averages** — an average hides exactly the one request people complain about. `RequestMetricsSnapshotService` writes one row per route into `request_metrics_daily` (V74) just before midnight and prunes past `retention-days`; the measurement itself never touches the database. Served by `GET /api/v1/system/request-metrics` (live window) and `/daily?route=…&days=…` (history).

For diagnosis while developing: `Server-Timing: wait;dur=… work;dur=… total;dur=… sql;desc="n=…"` appears in the DevTools network tab of every response (`automation.request-metrics.server-timing-enabled`, **default `true`**, explicitly `false` in `application-stable/prod/test.yml`). On by default rather than tied to a dev profile: the dev instance starts with no active profile (`No active profile set, falling back to "default"`), so a dev-profile entry would have switched the header off on the very instance it is for. Not runtime-switchable, so a leaked API key cannot turn it on. Hibernate additionally logs every query over 200 ms (`hibernate.log_slow_query`, startup configuration). Runtime-switchable through the settings: `request-metrics-enabled`, `request-metrics-retention-days`, `request-metrics-slow-request-ms`. A single slow request is logged at WARN, **not** written to the ActivityLog: under pool-size=1 an extra database write on the thread of an already-slow request would compete for the very connection it is waiting for.

**Drift watchdog:** `RequestMetricsDriftWatchdogService` compares the running window against a route's own past every hour and reports regressions — the half of the measurement that does not wait to be asked. The rule lives in `RequestMetricsDriftDetector` (pure comparison, no I/O, therefore testable): a route is reported only when it had at least `drift-min-calls` calls today, a baseline exists, that baseline is not itself near zero (1 ms against 3 ms is a factor of three and means nothing), and today's p95 exceeds the baseline by `drift-factor`. The baseline is the **median** of the stored days, not their mean: one bad day (a big import, a backup running) would raise a mean and hide the next real regression. The message names both costs (`/todos p95 120 -> 900 ms (x7.5), SQL 3.0 -> 47.0`) and, where the numbers allow, the likely cause: statement count growing alongside time is an N+1; a dominant wait share points away from the route. Each route is reported at most **once a day** — an alert repeating hourly teaches people to ignore it. Written to the ActivityLog (WARN) and broadcast over WebSocket, always from the watchdog thread. Runtime switches: `request-metrics-drift-detection-enabled`, `-drift-factor`, `-drift-min-calls`, `-baseline-days`.

The same values become `FAKT` lines in the fact sheet of the `mysuricates-performance-audit.json` workflow — through the `{{ request_metrics }}` context variable (`RequestMetricsFactSheet` → `WorkflowContextDataService`), **not** through the REST API: shell blocks do not call it. That closes the gap the audit until now declared explicitly as "business /api/v1 endpoints are deliberately not called". Covered by `scripts/tests/test-audit-facts.sh`.

**Dashboard widget "API Latency":** `components/dashboard/ApiLatencyCard.tsx` (presentational) plus `ApiLatencyWidget` in `DashboardDataWidgets.tsx` (binds `useRequestMetrics`), registered as id `api-latency` in `widgetRegistry.ts`. Lists the most expensive routes by p95 with call count and SQL statements, and above them the **wait share** tile: the call-weighted mean of `waitShare` — weighted, because otherwise a single slow request would count as much as nine hundred fast ones. At 50% or more the tile turns `danger` and says the cause is more likely whatever holds the connection than the routes listed.

**Digging deeper with JFR:** When the widget or the watchdog shows a hot route but the costs do not explain it, the Java Flight Recorder gives the profile — built into the JDK, no async-profiler, no agent, no overhead in normal operation:

```bash
PID=$(cat ~/.local/state/autotrigger-dev/backend.pid)
jcmd "$PID" JFR.start duration=60s filename=/tmp/hot.jfr settings=profile name=diag
jcmd "$PID" JFR.check                      # is the recording still running?
jfr summary /tmp/hot.jfr                   # which events were captured
jfr print --events jdk.ThreadPark /tmp/hot.jfr | head -60
```

**Important for this platform:** `jdk.ExecutionSample` (CPU) is usually the wrong trail here. Under `maximum-pool-size: 1` the time is not spent computing but waiting, and the event for that is `jdk.ThreadPark` (Hikari parks the thread via `LockSupport`). A trial recording against the dev backend produced 22 CPU samples against 22 park events totalling 832 ms of waiting; the waiting was the whole story. Second: **load has to be running during the recording.** On an idle instance the profile contains nothing — trigger the requests that are slow first, then record.

Which tool when:

| Symptom | First tool |
|---|---|
| One view is slow | `Server-Timing` header in the DevTools network tab |
| Many SQL statements | statement count in the header, or `RequestCostRatchetTest` |
| High waiting instead of work | wait-share tile on the dashboard, then the pool watchdog's thread dump |
| A single slow query | `hibernate.log_slow_query` (>200 ms) in the backend log |
| Everything slow, CPU high | JFR `jdk.ExecutionSample` |
| Everything slow, CPU low | JFR `jdk.ThreadPark` |
| Suspected leak or GC | JFR `jdk.GCPhasePause`, plus `/actuator/metrics/jvm.memory.used` |

**No scheduling under the integration test profile:** `@EnableScheduling` lives in `config/SchedulingConfig` with `@Profile("!integration")`. Every `@SpringBootTest` class runs with `@ActiveProfiles("integration")` and therefore without background jobs. Reason: those jobs otherwise write into the same SQLite file while tests run — `HealthAutoTodoService`, for example, creates a TODO as soon as the stable or test instance is unreachable. Such rows survive the `@Transactional` rollback (separate thread, separate transaction) and make globally counting assertions depend on whether a container happens to be running. Scheduled methods are invoked directly in tests, so their logic stays covered. When adding a new `@Scheduled` job, test it by calling the method directly — do not wait for a tick.

**Integration encryption key pinned to a temp path in the test profile:** Since 2026-09-12, just like `integration.db.path`, `IntegrationTestDbSessionListener` also sets `integration.key.path` (a per-JVM temp file, deleted on teardown), which `application-integration.yml`'s `automation.integration.key-file` points to — without this pin, `IntegrationEncryptionService.init()` would generate a fresh AES key into the developer's real `~/.autotrigger/` on every `@SpringBootTest` with empty `app_settings` (happened 2026-08-06, shadowing the real key for months).

**Backups disabled and pinned to a temp directory in the test profile:** The same trap a third time (2026-09-13): `automation.backup` defaults to `enabled: true` and `directory: ${user.home}/.autotrigger/backups/dev`, and `application-integration.yml` did not override either — every Spring context of an integration test backed up its throwaway test DB into the real Dev backup directory on startup, rotating away real backups in the process (retain count 30). Since then the test profile sets `automation.backup.enabled: false` and points `directory` at `integration.backup.dir`, which `IntegrationTestDbSessionListener` points at a temp directory per test JVM and deletes recursively at session end. The rotation in `DatabaseBackupService` is additionally hardened: it only counts and deletes files matching its own naming scheme `mysuricates-backup-yyyy-MM-dd_HH-mm-ss.db` (`BackupFileNaming.isOwnBackupFile`); manual copies or foreign `.db` files in the same directory stay visible but are never rotated. Check: `ls ~/.autotrigger/backups/dev | wc -l` must stay the same before and after `mvn test -Dtest=ContainerServiceStaleRowIntegrationTest`.

**Scenario tests without an AI CLI:** `ScenarioIntegrationTest` runs workflow definitions from `src/test/resources/scenarios/*.json` through the real executor. Every AI call goes to a `ScriptedAiProvider` (provider ids `SCRIPTED`, `SCRIPTED_B`) with fixed answers, which also records every call. That way prompt, model, timeout and provider can be checked before the call, and gate, auto-TODO and run status after it, all without `claude`/`codex`/`pi` and without a display. A new case is a new JSON file. Every file names its spec source and the incident it was derived from. Catalog and format are in `scenarios/README.md`.

### Frontend Tests

```bash
cd ai-automation-frontend

# TypeScript type checking
npx tsc --noEmit

# Linting
npm run lint
```

### E2E Tests (Cypress)

The E2E tests run with [Cypress](https://www.cypress.io/) against the **real backend** (no mocking). Test data is created and deleted directly via the API using `cy.request()`.

There are two target modes:
- **Dev**: Frontend proxy and Cypress helper point to `localhost:9090`
- **Test**: Frontend proxy and Cypress helper point to `localhost:8090`

**Important:** Frontend and Cypress must always be started with the same `CYPRESS_TARGET`, otherwise the UI creates data on a different instance than the setup/cleanup.

### Dev Target (Default)

**Prerequisite:** Dev backend (port 9090) and frontend (port 3000) are running.

```bash
cd ai-automation-frontend

# Frontend starts with proxy to Dev (:9090)
npm run dev

# Open Cypress interactively (browser UI)
npm run cy:open

# Run Cypress headless (CI mode)
npm run cy:run

# Alias
npm run test:e2e
```

The dev API key is automatically read from `ai-automation-backend/data/automation.db` if available.

### Test Target (isolated, no business test data in Dev)

**Prerequisite:** Test instance running on port `8090`.

```bash
# Example: Start test instance with fixed API key
cd ai-automation-backend
AUTOMATION_API_KEY=<test-api-key> docker compose -f docker-compose.test.yml up -d

# Optional: sync current definitions from Dev to Test
curl -s -X POST http://localhost:9090/api/v1/testing/sync-definitions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d '{}'

cd ../ai-automation-frontend

# Frontend with proxy to Test (:8090)
CYPRESS_TARGET=test CYPRESS_TEST_API_KEY=<test-api-key> npm run dev:e2e:test

# Cypress against the same test instance
CYPRESS_TARGET=test CYPRESS_TEST_API_KEY=<test-api-key> npm run test:e2e:test
```

Optionally, the targets can also be overridden directly:

```bash
CYPRESS_API_BASE_URL=http://localhost:8090/api/v1
CYPRESS_WS_BASE_URL=ws://localhost:8090
```

### Test Data and Cleanup

- **Dev target:** Cypress-created projects, workflows, chains, and runs end up in Dev
- **Test target:** The same data ends up on the test instance
- `cy.apiCleanup()` only cleans up Cypress test data with the prefix `Cypress`
- For the test instance, `POST /api/v1/testing/reset` can additionally be used if a complete reset is desired

**Test structure:**

```
cypress/
  support/
    e2e.ts          ← Global beforeEach (set locale to English)
    commands.ts     ← Custom commands (apiCreateProject, shouldShowToast, navTo, ...)
    api.ts          ← HTTP helpers for test data setup (CRUD against backend API)
  e2e/
    activity-log.cy.ts       ← Activity log view
    assistant-panel.cy.ts    ← Assistant panel
    chains.cy.ts             ← Chains CRUD and execution
    dashboard.cy.ts          ← Stat cards, charts, quick actions
    navigation.cy.ts         ← Sidebar navigation, routing, collapse/expand
    project-detail.cy.ts     ← Project detail page
    projects.cy.ts           ← Projects CRUD (create, search, detail, delete)
    responsive-layout.cy.ts  ← Responsive layout
    runs.cy.ts               ← Workflow runs view
    schedules.cy.ts          ← Schedules CRUD, toggle, table
    settings.cy.ts           ← Theme, language, API key, system health
    skills.cy.ts             ← Skill catalog, filter, detail slide-over
    staging.cy.ts            ← Staging management
    todos.cy.ts              ← Todos CRUD
    webhooks.cy.ts           ← Webhooks CRUD, toggle, copy button
    wishes.cy.ts             ← Wishes
    workflow-execution.cy.ts ← Workflow execution
    workflows.cy.ts          ← Workflows CRUD, template selection, detail, blocks, skills
```

**Conventions:**
- English locale is enforced via `localStorage` before each test
- Selectors use `aria-label`, button text, `h1` text, `placeholder`, `role="switch"` (no `data-testid`)
- `before()` / `after()` hooks ensure clean test data via `cy.apiCleanup()`

`scripts/cleanup-runs.sh` runs `PRAGMA quick_check` on the file before touching it and aborts on
any finding (on 2026-08-04 a DELETE on an already corrupt DB made the damage worse). `--db <path>`
points the script at a different file. Script tests live under `scripts/tests/` and run without a
backend: `bash scripts/tests/test-cleanup-runs.sh`.

### Installer on a fresh machine (installer probe)

`scripts/installer-probe.sh` checks `scripts/setup.sh` the way a new user experiences it: in a fresh
container (default `ubuntu:24.04`, with `--image rockylinux:9` also a dnf system) that has nothing but
git, sudo and tzdata. It always checks a committed state (default `HEAD`, otherwise `--ref`), never
the working tree. Requires Docker.

```bash
./scripts/installer-probe.sh --interactive          # fresh machine as a shell — click through yourself
./scripts/installer-probe.sh --ref main             # automatic run with report
./scripts/installer-probe.sh --image rockylinux:9   # the same on a dnf system
./scripts/installer-probe.sh --ref main --keep      # then look at the fresh instance in the browser
```

The interactive machine shares the host network (`--network host`): open the address `setup.sh`
names at the end directly in the browser; the installer skips ports that are busy on the host. Pick
"on this machine" in the mode step — there is no Docker inside the probe.

The automatic run takes the "one instance on this machine" way from
[GETTING-STARTED.md](GETTING-STARTED.md) in two phases, each under a pseudo terminal:
`setup.sh --check` (check only) and `setup.sh --yes --runtime host --bind 0.0.0.0` (one pass up to
the running instance). Each phase writes its own exit code to a file inside the container — through
`script` and `docker exec` an abort once came back as 0. The run turns red when phase 1 does not name
the missing tools, phase 2 does not end with exit 0, health or the interface on the one port do not
answer, the login with the generated key fails, the key shows up in a log, the backend is not built
exactly once, or the clone is no longer clean afterwards (`git status`). The result is a single HTML
page with terminal transcript and screenshots under
`~/.local/state/mysuricates/installer-probe/<time>-<sha>/report.html` (`latest` points to the last
run). A run downloads JDK, Node, npm packages and Maven dependencies fresh every time. The container
way (Docker inside the container) is not part of the probe yet.
The screenshots are taken by Playwright from `ai-automation-frontend/node_modules` on the host. If
the browser for the installed Playwright version is missing (typically after an update), the login
check turns red with "Browser fehlt — im Frontend `npm run pw:install` ausführen" (browser missing,
run `npm run pw:install` in the frontend); the probe downloads nothing.

Before a release it runs as the workflow **Installer probe** (pool entry
`mysuricates-installer-probe`, variable `ref`, default `main`). The workflow takes the probe script
from the state under test, not from the branch that happens to be checked out. The script tests run
without Docker: `bash scripts/tests/test-installer-probe.sh` and
`python3 -m unittest discover -s scripts/tests -p 'test_installer_probe_report.py'`.

## Versioning & Releases

### Where the Version Is Defined

| File | Format | Example |
|---|---|---|
| `ai-automation-backend/pom.xml` | `X.Y.Z` in the tag, `X.Y.Z-SNAPSHOT` afterwards | `0.7.7` / `0.7.8-SNAPSHOT` |
| `ai-automation-frontend/package.json` | same as pom.xml: `X.Y.Z` in the tag, `X.Y.Z-SNAPSHOT` afterwards | `0.7.7` / `0.7.8-SNAPSHOT` |
| `application.yml` | via `@project.version@` (Maven filtering) | automatic |

The dashboard displays the version via the `/api/v1/system/info` endpoint. The **build type** comes
from the suffix (`SystemIdentityService`): no suffix means `RELEASE`, `-SNAPSHOT` means SNAPSHOT.
That is why the tagged commit carries the bare number — up to v0.7.6 it carried `-SNAPSHOT`, so every
installation of a release reported SNAPSHOT (TODO dd82775b).

Backend and frontend carry the **same** number, between releases as well: the version tile on the
dashboard shows both side by side (`BE … · FE …`) and warns when they drift apart — the "new
frontend, old backend JAR" case that produces 404s and pages stuck on "loading" in the test
container. If `package.json` stayed on the release number while `pom.xml` already moved to the next
`-SNAPSHOT`, that warning would be on screen permanently.

### Release Script

```bash
./scripts/release.sh           # Patch: 0.1.0 → 0.1.1 (default)
./scripts/release.sh minor     # Minor: 0.1.1 → 0.2.0
./scripts/release.sh major     # Major: 0.2.0 → 1.0.0
```

The script:
1. Reads the last released version from the newest git tag (without tags: from `pom.xml`)
2. Calculates the new version (increments the segment) and stops if that tag exists already
3. Writes it **without a suffix** into `pom.xml` plus `package.json` and `package-lock.json` (via `npm version`) — a lockfile left at the old version makes every fresh clone dirty after its first `npm install`; `packageLock.guard.test.ts` keeps both in sync
4. Creates the git commit `Release vX.Y.Z` and sets the git tag `vX.Y.Z`
5. Sets `pom.xml` **and** `package.json` to the next development version `X.Y.(Z+1)-SNAPSHOT` and commits `Weiter an vX.Y.(Z+1)` — otherwise every commit after the tag would carry the release number and the version tile would report drift permanently

**Prerequisite:** Clean working tree (no uncommitted changes).
**Test:** `bash scripts/tests/test-release.sh` runs the flow in a throwaway repository.

### Interaction with Staging

After a release, `./scripts/staging.sh promote` can be run to deploy the new version to the stable environment. The settings page then shows a version comparison (Dev vs. Stable) and recommends a promote if the versions differ.

### Git Tags

There are two types of git tags:

| Tag Format | Created by | Purpose |
|---|---|---|
| `v0.1.1` | `release.sh` | Semver release marker |
| `stable-20250225-143022` | `staging.sh promote` | Deployment timestamp |

### Behavior Changes

Changes that can affect the runtime behavior of existing, self-operated workflows — not just new
features. Read this before a release if you maintain your own workflow definitions.

**Since 2026-09-18 (commit afa0cdfc): platform data in shell fields taints the run.**
If you use `{{ previous_report }}` or `{{ open_todos }}` in a shell field of a workflow (e.g.
`command`, `items_command`), then from this version on, block outputs later in the same run
(`{{ previous_output }}`, `{{ block.<name>.output }}`, `{{ step_N_output }}`, `foreach` items) are
also automatically wrapped in POSIX single quotes — exactly as with a webhook-triggered run. If you
previously passed such an output unquoted to a following command and relied on the shell's word
splitting (`some_cmd {{ previous_output }}` became several arguments, one per word), you now get
**a single quoted argument** instead.

- **How to recognize it:** A following command that used to read several words/arguments from a
  block output (e.g. `$1`, `$2`, … or a `for word in {{ previous_output }}` loop) now sees only one
  combined argument, or a loop with exactly one iteration.
- **Why:** `previous_report` and `open_todos` are treated as untrusted (they can carry content from
  earlier, possibly webhook-processed runs, or titles from imported issues) and are therefore always
  quoted. As soon as a shell field actually substitutes one of these placeholders, the whole run is
  "tainted" from that point on — the same rule as for webhook payloads.
- **Getting the old behavior back:**
  - Write the platform data into your own variable first (`setVariable`, or a file) instead of using
    it directly in the same shell field — a value that comes from the definition or from a
    self-set variable counts as trusted again and does not taint the run.
  - Or deliberately work without quoting: redesign the following command so a single quoted argument
    is enough (e.g. have it expect the argument in quotes instead of splitting it itself).
  - Which of the shipped definitions put platform data into a shell field at all shifts with every
    new pool entry, so no fixed count stands here. That they use the safe, unquoted form is pinned
    by `TemplateHygieneGateTest`.

**Since 2026-09-18 (TODO 22e6bec8): the output of an AI block with platform data in its prompt is
coloured.** If you use `{{ previous_report }}` or `{{ open_todos }}` in the prompt of an AI block
(`simple`, `agentic`, `skill-capability`, plus the non-shell branches of `foreach`, `while`,
`conditional`, `retry`, `switch`), the output of **exactly that block** arrives in later shell fields
as **a single quoted argument** instead of raw. Once that output is recorded, the run counts as
coloured, and the colouring is sticky: every **further** block output and the variables derived from
it (`step_N_output`, `foreach` items, `document` extracts) are quoted from then on too, and
`exportTaint()` passes that on to sub-workflows and to a resume. In practice: nearly every audit or
report workflow that reads `previous_report` in a prompt runs in quoted mode from that block on.

- **How to recognize it:** a shell block that used to read `{{ previous_output }}` /
  `{{ block.<name>.output }}` of such an AI block — or any later block output of the same run — word
  by word (`$1`, `$2`, `for word in …`) now sees exactly one argument.
- **Why:** the path platform data → AI prompt → shell block bypassed the quoting entirely; an AI
  answer carrying platform bytes ended up raw on a command line.
- **The safe form (recommended, works either way):** set `"shell_escape_variables": true` in the
  following block and pull the unquoted placeholder into a variable via `set --`:
  `set -- {{ previous_output }}`, then `ANSWER="${1:-}"`. The block is then independent of whether
  the run is coloured.
- **Getting the old behavior back:** do not use the platform data in the prompt; write it into your
  own, self-set variable first — or build the following command so that a single argument is enough.
- In the shipped definitions every affected shell block already carries the opt-in;
  `PlatformPromptQuotingGateTest` pins that.

Details on the three trust classes and taint propagation:
[deployment.md → Trust Model and Network Exposure](deployment.md#trust-model-and-network-exposure).

## Project Conventions

### Backend

| Convention | Description |
|---|---|
| **Entities** | JPA entities with Lombok (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) |
| **DTOs** | Java records (`record` instead of classes) for request and response objects |
| **Mapper** | MapStruct for entity ↔ DTO conversion (automatic code generation) |
| **IDs** | UUID as string (`@GeneratedValue(strategy = GenerationType.UUID)`) |
| **JSON fields** | `@Convert(converter = JsonMapConverter.class)` for `Map<String, Object>` in TEXT columns |
| **Auditing** | `@CreatedDate`, `@LastModifiedDate` via Spring Data JPA Auditing |
| **Validation** | Jakarta Validation (`@NotBlank`, `@NotNull`, `@NotEmpty`) on DTOs |
| **Exceptions** | `GlobalExceptionHandler` with `@RestControllerAdvice`, unified error format |
| **Logging** | SLF4J with `@Slf4j` (Lombok) |
| **Transactions** | `@Transactional` on service methods |

### Frontend

| Convention | Description |
|---|---|
| **Styling** | Tailwind CSS with CSS variables, no CSS modules or styled-components |
| **State** | Server state via TanStack Query, client state via Zustand |
| **Components** | Function components with named exports |
| **Hooks** | Custom hooks for each API resource |
| **Types** | Centrally defined in `src/types/index.ts` |
| **i18n** | Type-safe translation objects (no runtime key lookup) |
| **Icons** | Lucide React (consistent icon set) |
| **Hints in place** | `<Hint id="…">` sits in the component it explains, never in a central list — a page that is missing (preset, Simple Mode, module off) takes its hint with it. Registry with version in `src/data/hints.ts` (raise the version only for a change in substance; the hint then shows again), copy in `src/data/hints.<lang>.json`: what and why, never where to click. `i18n/__tests__/hints.test.ts` keeps registry, copy and usage in step |
| **Forms** | Controlled components with React state |

### Annotation Processors

The backend uses three annotation processors that must be correctly ordered in the compiler configuration (`pom.xml`):

1. **Lombok** — Generates getters, setters, builders, etc.
2. **MapStruct** — Generates mapper implementations
3. **lombok-mapstruct-binding** — Bridge between Lombok and MapStruct

## Architecture Notes for Developers

### Backend Structure

The backend comprises **48 REST controllers** and **20 block types**:

**Controllers:** ActivityLog, Assistant, Audit, Chain, ChainTemplate, CompletionTrigger, Conversion, DashboardLayout, DashboardReportAssist, DashboardWorkflow, DeadLetter, Dialog, Docs, Execution, Integration, Learning, Notification, OnboardingProfile, PageSuggestion, Project, Reports, Schedule, Search, Skill, Staging, System, SystemAccess, SystemAiProvider, SystemMaintenance, SystemModules, SystemRequestMetrics, SystemSettings, Testing, Todo, TodoTrigger, WebSocketTicket, Webhook, Wish, Workflow, WorkflowAnalyzer, WorkflowDebug, WorkflowFactory, WorkflowImport, WorkflowMemory, WorkflowPool, WorkflowSeal, WorkflowTemplate

**Block types:** `simple`, `shell`, `retry`, `conditional`, `foreach`, `while`, `switch`, `agentic`, `approval`, `sub-workflow`, `skill-capability`, `human-input`, `document`, `integration`, `todo-create`, `todo-update`, `todo-close`, `compress`, `compress-ai`, `dashboard-report`

**Flyway migrations:** V1–V84 (SQLite)

### Gate Status System

Workflow blocks can emit quality gate markers:
```
gate_status: GREEN|YELLOW|RED
gate_category: <category>
gate_description: <description>
```
- The gate does not change the run status; `COMPLETED_WITH_ERRORS` only comes from a failed `checked_command` step
- With AutoTodo on the workflow: RED → TODO with HIGH, YELLOW → with MEDIUM priority (`AutoTodoService`)
- `GateStatusOutputParser` parses the markers from block outputs

### Chains

Chains sequence multiple workflows in order:
- `ChainService` / `ChainExecutor` for CRUD and execution
- Cross-project: a chain does not belong to any project
- `previous_chain_output` as a context variable between entries
- WebSocket status broadcasts to `/topic/chains/{chainRunId}/status`

### Workflow Memory

Workflows can accumulate knowledge across runs:
- `WorkflowMemoryService.extractKnowledge()` — AI-based extraction via `KnowledgeSummarizer`
- Active entries are injected into subsequent runs
- Provider: `CLAUDE` (default) or `CODEX`

### Soft-Delete Pattern

- **Workflows:** `@SQLRestriction("deleted_at IS NULL")` on the entity class — automatically excluded from all queries
- **Projects:** `archived_at IS NOT NULL` = archived — filtering via repository methods (no `@SQLRestriction`, as it would break eager-loaded `Workflow.project` associations)

### Seeder

- **SkillSeeder:** Creates 19 built-in skills on startup (idempotent, updates on version bump) and registers them in the `SkillRegistry`
- Skill definitions as JSON + prompt `.md` under `src/main/resources/skills/builtin/`
- **WorkflowTemplateSeeder:** Creates 66 built-in templates on startup (idempotent, updates on version bump)
- Template definitions as JSON under `src/main/resources/templates/builtin/`

### Module Catalog

Whoever adds or changes a module in `modules/ModuleCatalog` must regenerate
`scripts/lib/module-catalog.generated.sh` — the file is not hand-maintained, it is derived from the
catalog and the display texts (`ai-automation-frontend/src/data/modules.<lang>.json`) by
`ModuleCatalogTextsTest`:

```bash
cd ai-automation-backend && ./mvnw test -Dtest=ModuleCatalogTextsTest -Dmodule.catalog.write=true
```

Without regeneration, the test fails red as soon as the generated file no longer matches the
catalog. `scripts/lib/module-preset.sh` reads the generated file before every build — deliberately
without requiring a backend or `jq`. `scripts/setup.sh --lang de|en` only chooses the display
language of the module texts in the terminal (default: the `$LANG` prefix, else `en`); it never
changes the outcome (which modules are active). Details on the catalog itself:
[architektur.md](architektur.md#module-system-startup-configuration).

## Hygiene gate

`scripts/ci/public-hygiene.sh` checks what would end up in a public repository. The rules —
forbidden literals, backup-file patterns, allowlist path, thresholds — live in
`scripts/lib/hygiene-rules.sh` and are read by both CI and the pre-commit hook.

| Mode | Who | Checks | Behaviour |
|---|---|---|---|
| `--tracked` (default) | CI | tracked files | error |
| `--worktree` | local | additionally files that are neither tracked nor ignored | warning |
| `--staged` | pre-commit hook | staged changes only | error |
| `--range A..B` | pre-push gate | only the changes of a commit range, same checks as `--staged` | error |

What is checked:

1. **Tracked but ignored.** Formerly a hand-maintained list of ten directory names, now
   `git ls-files --cached --ignored --exclude-standard`, which covers every future ignore rule
   automatically. Deliberate exceptions live with a reason in `scripts/ci/hygiene-allowlist.txt` —
   paths only, never pattern literals, so the allowlist itself gives nothing away.
2. **Forbidden literals** in tracked files, or in added lines for the staged mode.
3. **Pool neutrality** via `scripts/ci/pool-neutrality.sh`. The built-in template
   `pool-neutrality-audit` runs the same script (with `--all`, because it audits the pool of a
   running instance rather than the tracked release set).
4. **Repository weight**: two tracked paths with an identical blob checksum from 256 KiB up are an
   error; a single tracked file above 5 MiB is a warning.
5. **Stray backup files** (`--worktree` only): neither tracked nor ignored. The same find is an
   error once it is staged.
6. **`.gitignore` gives away no private filenames.** The file is tracked and ships with the
   publication — comments included. If it names something listed in `.git/info/exclude`, that is a
   violation. CI has no exclude file, so the check simply does not fire there.
7. **Symlinks that point outside the repository.** Tracked (or staged) links with an absolute
   target, or a relative target above the repo root, are an error. `git grep` does not read symlink
   blobs, so check 2 does not catch a developer path inside the link target; also, such a link points
   at the original checkout instead of its own tree in every clone, worktree and run snapshot.
   Example: `ai-automation-backend/workflow-pool` must point at `../workflow-pool`, because the
   backend runs with cwd `ai-automation-backend/` and resolves the pool relatively.
8. **Private terms** — in the tracked set for `--tracked`/`--worktree`, in added lines for
   `--staged`/`--range`. Names of private projects, employers or people
   cannot go into check 2 without publishing them in the repository. They live in a list outside the
   tracked set instead: `scripts/ci/hygiene-private-terms.txt` (untracked, in `.git/info/exclude`),
   `$HYGIENE_PRIVATE_TERMS` or `~/.config/mysuricates/hygiene-private-terms.txt` — the last one is
   also found by clones and by the isolated run workspaces of the evidence workflows. Matching is
   literal and case-insensitive; the message names the list line, not the term. Without a list (CI,
   someone else's clone) the check is skipped with a notice.

`scripts/install-hooks.sh` installs the hooks (`pre-commit`, `commit-msg`, `pre-push`) via
`core.hooksPath` (and runs as part of `setup.sh`).

### When the gate fires

Do not reach for the bypass. `git commit --no-verify` exists, but a hit is almost always real: a
blanket `git add` picked up something that does not belong in the repository. Look at what is
staged first.

## Pre-push gate

`scripts/ci/pre-push-checks.sh` checks what is about to leave the repository — locally,
deterministically, in seconds to a minute. Two hooks call it: `commit-msg` (message and author
address only, while writing — a hit is cheap there) and `pre-push` (the whole push range, the last
net: it also catches commits from worktrees without hooks, from run workspaces and with
`--no-verify`). CI runs the same tree gates (`--tree`) and the same range check on the commits of
every push event; the rules live in `scripts/lib/hygiene-rules.sh`.

Checked per push range (remote state..local; for a new branch everything no remote ref knows yet):

1. **Commit metadata.** Author and committer carry the GitHub noreply form
   (`<id>+<login>@users.noreply.github.com`, `HYGIENE_COMMIT_EMAIL_REGEX`); the message contains no
   forbidden literals, no private terms and no e-mail address outside a trailer (`Co-Authored-By`,
   `Generated-By`, `Signed-off-by`, …). The address itself appears nowhere in the repository, only
   its form.
2. **Hygiene gate on the range** (`public-hygiene.sh --range`): what is newly added.
3. **CI tree gates that depend on the code:** documentation numbers, documentation links with DE/EN
   parity, diagram drift. A missing gate script is a hard error.
4. **gitleaks on the range** — only if installed, otherwise skipped with a notice. Nothing is
   installed on the fly; CI scans anyway.
5. **Build check by scope.** Frontend touched → `tsc --noEmit` and `npm run lint` (about a minute);
   backend touched → `mvn -q test-compile` (seconds). Docs-only pushes stay at seconds.

Not here: the full test suite (CI and the commit rule in CLAUDE.md), the tracked set (`--tracked`
in CI and in the "Public Readiness" chain) and anything that needs judgement.

Skipping when it must be: `MYSURICATES_PREPUSH_SKIP=tree,build git push` — every omission is
reported; a gate that does not measure is not green. `git push --no-verify` remains the bypass; do
not reach for it by reflex. A hit on message or address is healed by `git commit --amend` (with
`--reset-author` after `git config user.email …`), older commits by `git rebase -i`.
Self-test: `bash scripts/tests/test-pre-push-checks.sh`.

## Dependency advisories (frontend)

`npm audit` in the frontend reports **0 vulnerabilities** after the updates from 2026-09-12.
After an initial reduction from twenty to seven, the two remaining major upgrades were carried
out as well.

**Cleared:** the runtime chain first. `dompurify` arrived via `@monaco-editor/react` →
`monaco-editor` and sat at 3.4.12, the upper edge of the vulnerable range (GHSA-55q2-fjhq-7xh7,
XSS through a detached subtree). A plain update does not move it because an `overrides` entry pins
the version — that entry is now `^3.4.13`, installed is 3.4.15. Then `npm audit fix` without
`--force` for everything else in range.

**Further updates:**

| Package | Update |
|---|---|
| `cypress` and its transitives | Cypress 15.21.1 replaces the vulnerable dependencies of Cypress 13; `qs` is updated too. |
| `react-router-dom` / `react-router` | Version 7.18.3 fixes the reported router findings. The existing protection `isInternalAppPath()` against protocol-relative and backslash paths stays in place. |

Re-check with `npm audit`, `npm test` and `npm run build` (including the TypeScript check).
Cypress 15 requires Node.js 20/22/24+ and, on Linux, glibc >= 2.31;
see the [Cypress migration notes](https://www.cypress.io/blog/cypress-15-made-simple-upgrade-paths-from-13-and-14).

## Known Limitations

### SQLite

- **Concurrent writes:** SQLite only allows one write operation at a time. HikariCP is configured with pool-size=1 to serialize all DB operations and avoid `SQLITE_BUSY` errors under concurrent workflow runs.
- **No native types:** Booleans as INTEGER, timestamps as TEXT, JSON as TEXT.
- **No ALTER COLUMN:** Schema changes are limited (ADD COLUMN only).
- **NEVER write directly to the DB** (via `sqlite3` CLI or SQL). Always use the REST API or service/repository layer — direct writes bypass the Hibernate cache.

### Docker Dependency

- The backend boots even without a running Docker daemon (the Docker client is initialized lazily).
- Without Docker, container-execution blocks in workflows cannot be executed.
- Agent images must be built beforehand (`docker compose --profile build-only build`).
- **Claude CLI always runs on the host**, not in containers (avoids nested session detection).

### Security

- The Docker socket mount gives the backend host-level Docker access. In production environments, at minimum API key auth should be enabled.
- CORS is open by default (`*`). Specific origins should be configured for production.
- Rate limiting is active by default (120 req/min). Configurable at runtime in Settings → Execution.

### Frontend

- No offline mode (PWA shell is cached, but API data is not).
- Monaco Editor is served from the app's own bundle (no CDN, the CSP allows `'self'` only), but the PWA does not precache it — offline, it only opens if the browser has already cached the `monaco` chunk.
