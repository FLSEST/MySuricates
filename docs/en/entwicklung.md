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
# From the repo root — builds a fat JAR, copies it to ~/.local/state/mysuricates-dev/runtime/
# and starts it from there (port 9090). Idempotent; also starts the frontend.
scripts/start-mysuricates-dev.sh            # start
scripts/start-mysuricates-dev.sh restart    # apply code changes: stop → build JAR → start;
                                            # also starts Vite if it is missing (e.g. after a terminal crash)
scripts/start-mysuricates-dev.sh stop

# Runtime: host (default) or container — the same commands (TODO a1236a64)
scripts/start-mysuricates-dev.sh start --container
MYSURICATES_DEV_RUNTIME=container scripts/start-mysuricates-dev.sh   # permanently

# Open to the outside (default: 127.0.0.1 only) — backend and Vite at once
MYSURICATES_DEV_BIND=0.0.0.0 scripts/start-mysuricates-dev.sh restart
```

**This machine only.** Backend and Vite listen on `127.0.0.1` only by default: the starter passes
`--server.address` to the backend, `vite.config.ts` sets `host` from the same variable (also for
`npm run dev` by hand). `MYSURICATES_DEV_BIND` opens both on purpose — `0.0.0.0` for the local network
(phone, PWA) or a single address such as the Tailscale IP. Only IP addresses are accepted; anything
else stops the start before anything is stopped or built. With a single address the backend no longer
answers on `localhost`: the starter's checks and the Vite proxy follow it, other tools on this machine
(plugin, `curl localhost:9090`) do not — prefer `0.0.0.0` then. In container mode the backend's port
follows `MYSURICATES_DEV_CONTAINER_BIND` (see below), the Vite proxy still follows
`MYSURICATES_DEV_BIND` — so set a single address in both. Whoever opens the UI from another device adds
its origin to `AUTOMATION_CORS_ORIGINS` ([deployment.md → Where it listens](deployment.md#where-it-listens)).

**Host or container?** The host way is the default and unchanged. `--container` starts the backend
through `docker compose` from `ai-automation-backend/docker-compose.dev-container.yml` instead (image
`Dockerfile.stable`, so with Node — a plain JRE Alpine image without Node would not be enough here,
no AI CLI would run there). The Vite dev server stays on the host either way. Tunable via
`MYSURICATES_DEV_CONTAINER_PORT`, `MYSURICATES_DEV_CONTAINER_NAME`, `MYSURICATES_DEV_COMPOSE_PROJECT`,
`MYSURICATES_DEV_PROJECT_ROOT`, `MYSURICATES_DEV_PRESET` — so a probe instance can run next to the real
one. In container mode the starter's checks (is a backend already running?, readiness, run check
before `restart`) follow the port from `MYSURICATES_DEV_CONTAINER_PORT`; `MYSURICATES_API_BASE` still
overrides it.

The image (`Dockerfile.stable`) compiles nothing, it copies the JAR from
`ai-automation-backend/target/`. That is why `restart --container` builds the JAR first
(`mvn clean package -DskipTests`, the container is only stopped afterwards), `start --container` only
with `--rebuild` or when there is none yet, and `build --container` builds just the JAR (3bfa4e10).

What of the machine's AI providers goes into the container is set by `MYSURICATES_DEV_AI_FROM_HOST`
(`login` = the programs and just the login of each provider, the default; `programs`; `all` = whole
provider directories as before), plus `MYSURICATES_DEV_AI_PROVIDERS` (selection from
`claude,codex,pi`) and `MYSURICATES_DEV_DOCKER_SOCKET` (default `0`; `1` gives the container full control
over Docker on the machine and is needed for project containers, the terminal and `container_image` blocks).
The port is published on `127.0.0.1` only by default, `MYSURICATES_DEV_CONTAINER_BIND=0.0.0.0` opens it to the
local network on purpose. Claude needs a one-time
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

The Vite dev server listens on `127.0.0.1` (`MYSURICATES_DEV_BIND` opens it, see above) and
automatically proxies `/api/*` and `/ws/*` to `localhost:9090`.
`scripts/start-mysuricates-dev.sh` already starts it; running `npm run dev` by hand is only needed
without the starter. Open: `http://localhost:3000`

## Getting into the code

The repository holds two applications and the tooling around them: `ai-automation-backend/` is the
Spring Boot backend (Java 21), `ai-automation-frontend/` the React UI (TypeScript, Vite). `scripts/`
holds the starter, installer, staging and the gates, `workflow-pool/` the shipped workflow definitions,
`deploy/` the container route. The inner structure is described in [architektur.md](architektur.md),
tables and fields in [datenmodell.md](datenmodell.md), the endpoints in [api-referenz.md](api-referenz.md).
Which libraries apply in which version is in `pom.xml` and `package.json`.

**Reading order, backend** (under `com.automation`; `LayerRuleTest` pins the layers controller → service →
repository):

1. `service/workflow/WorkflowExecutor` — accepts a start, has `RunAdmission` check it and runs the
   blocks on a virtual thread; `BlockStepRunner` creates the step for each block.
2. `executor/ExecutionContext` — the run state every block receives: variables, the predecessor's
   output, pause and cancel, approvals; the `{{ … }}` placeholders are resolved there.
3. `executor/BlockExecutor` and the executors under `executor/blocks/` — one per block type;
   `AgenticBlockExecutor` shows the REASON/ACT loop.
4. `service/cli/CommandExecutorService` and `service/ai/HostClaudeService` — how an AI call becomes a
   process on the host (prompt via stdin, provider via `AiProviderRegistry`).
5. `service/workflow/WorkflowService` → `WorkflowMutationGateway` — the only write path for
   definitions (versions, seals).

**Reading order, frontend** (under `ai-automation-frontend/src/`):

1. `App.tsx` — every route, each page via `lazy()`, pages of optional modules inside `ModuleRoute`.
2. `api/client.ts` — the single HTTP client `api`, which sends the API key from the `settingsStore`.
3. `hooks/useWorkflows.ts` — the pattern of all hooks: `useQuery` to read, `useMutation` with
   invalidation to write.
4. `types/index.ts` and `stores/` — shared types (including `BLOCK_TYPES`) and the client state.

**New block type.** A `@Component` implementing `BlockExecutor`; `supportedTypes()` is the single source
of which types exist (`BlockTypeRegistry` collects them, an unknown type is refused on save). Then add
the type to `BLOCK_TYPES` (`src/types/index.ts`), `BlockTypesIndexSection` and
`AssistantToolValidator.KNOWN_BLOCK_TYPES` — `BlockTypeListsGuardTest` and
`types/__tests__/blockTypes.test.ts` keep the lists in step with the executors —, write a spec in
`BlockSpecs` (`BlockSpecConfigKeysGateTest` checks every key named there against the executor) and,
for the editor, add the starting config and the keys to `src/data/blockDefaultConfig.json` and
`src/data/blockConfigSchema.json` (`blockConfigSchema.test.ts`). If the type belongs to a module, it goes
into `executor/BlockTypeModules`. If the type calls an AI, it also goes into `AI_BLOCK_TYPES`
(`src/types/index.ts`: the editor fields `provider`/`model`/`effort`, equal to `ai.blockTypes` in the
schema, `blockConfigSchema.test.ts`), into `executor/ModelUse` (`needsAi`: the AI badge and the "Runs
without AI" filter of the templates) and into `ALWAYS_AI_BLOCK_TYPES` in `components/workflow/stepMetadata.ts`
(the step kind in a run).

**New table.** A Flyway migration `V{N}__description.sql` under `src/main/resources/db/migration/` —
determine the next number fresh (`ls ai-automation-backend/src/main/resources/db/migration | sort -V | tail -1`),
never guess —, then entity, repository and service. Lazy associations are loaded by a `@Transactional`
method in the service: Open Session In View is off.

**New controller.** Assign it to a module with `@RequiresModule(…)` or list it consciously as core in
`CORE_CONTROLLERS` of `ControllerModuleGateTest`.

**New page.** A file under `src/pages/`, a route in `App.tsx` via `lazy()`, a menu entry in
`utils/navItems.ts` with the required field `hint`: `navHints.test.ts` checks that the page renders its
hint. Visible text only through `de.ts` and `en.ts`.

**Module catalog changed.** `scripts/lib/module-catalog.generated.sh` is not maintained by hand but
generated from `modules/ModuleCatalog` and the display texts (`src/data/modules.<lang>.json`):
`cd ai-automation-backend && ./mvnw test -Dtest=ModuleCatalogTextsTest -Dmodule.catalog.write=true`.
Without it the test fails. `scripts/lib/module-preset.sh` reads the file before every build, on purpose
without a backend or `jq`.

**Soft delete.** `@SQLRestriction("deleted_at IS NULL")` hides deleted workflows from every query.
Projects are filtered through `archived_at` and repository methods, not via `@SQLRestriction` — that
would break the eagerly loaded `Workflow.project`.

Common symptoms when setting up:

| Symptom | Remedy |
|---|---|
| Port 9090 in use | stop the other process or set `AUTOMATION_PORT` |
| `SQLITE_BUSY` | only one backend instance per database file |
| `claude` not found during a run | the AI CLIs run on the host, so `claude` must be on the backend's `PATH` |
| `permission denied` on the Docker socket | add the user to the `docker` group |
| CORS error (403 `Invalid CORS request`) | add the address, together with the localhost entries, to `AUTOMATION_CORS_ORIGINS` — switching security off changes nothing about CORS |
| `mvn: command not found` | use the wrapper `./mvnw` |

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

# Output: target/ai-automation-backend-<version>.jar
```

**Docs on the classpath:** `src/main/resources/docs/` (architektur, WORKFLOW-GUIDE, audit-workflow-protocol,
assistant-system, vision) is a build artifact — the `maven-resources-plugin` copies the files from the
repo-root `docs/` during `generate-resources`. The directory is git-ignored; edit `docs/` only. The
assistant reads the copy at runtime via `ClassPathResource`.

**Block config schema:** `ai-automation-frontend/src/data/blockConfigSchema.json` describes the config keys of
every block type (type, allowed values, legacy value, required, de/en text). The Monaco config editor imports
the file directly, the backend gets a build-time copy in `classpath:schema/` (execution
`sync-block-config-schema` in `pom.xml`, like the docs above via the git-ignored `src/main/resources/schema/`;
`deploy/Dockerfile` copies it into the backend stage for that). The analyzer (`ConfigKeyRules`) thus checks
the same keys the editor offers. Two guards: `blockConfigSchema.test.ts` holds the schema against the shipped
definitions, `ExecutorConfigKeyDriftTest` against the keys the executors read in their source. Whoever
teaches an executor a new config key adds it to the JSON file.

### Frontend

```bash
cd ai-automation-frontend

# TypeScript check + Vite build
npm run build

# Output: dist/ (static files)
```

### Docker Images

The agent images for shell blocks with `container_image` are built by `scripts/build-agent-images.sh` (with the
checksum of the Dockerfile as a label that the **Containers** menu entry compares); when they are needed and how to
refresh their third-party repos is in [deployment.md → Docker Compose](deployment.md#docker-compose).
An image for a running instance exists only through
[deploy/](deployment.md#one-instance-in-a-container-deploy).

## Builds next to the running dev backend

The dev backend runs from a **JAR copy** at `~/.local/state/mysuricates-dev/runtime/current.jar`
(`scripts/start-mysuricates-dev.sh`), not from `target/classes`. Reason: a JVM with `-cp target/classes`
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
backend. **For workflows, a dedicated worktree remains the right place**, because two concurrent
Maven runs in the same `target/` (e.g. a scheduled run + a Claude session) produce incorrect results
for each other:

```bash
git worktree add --detach ~/.mysuricates/qa-worktree/mysuricates HEAD
cd ~/.mysuricates/qa-worktree/mysuricates/ai-automation-backend && mvn test
```

Such a worktree is detached at a commit and can persist between runs (`target/` and `node_modules/`
are then reused — first `git checkout --detach` / `git reset --hard` to the commit you want).
**Uncommitted changes are not included** — whatever runs there always tests the committed state.

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
if you only check it, or a truncated `tail`. What actually matters is the exit code of the whole
Maven run; count tests via the `<testcase>`, `<failure>` and `<error>` elements in
`target/surefire-reports/TEST-<Class>.xml` (every `@Nested` class ends up there too, there is no
separate file per inner class) or via the per-class console lines.

**Caveat for same-named methods in `@Nested` classes:** the final `Results: Tests run: N` line
merges results under the key outer class + method name. When methods in two `@Nested` classes of
the same outer class share a name, they count only once there; several failures of the same name
show up under `Failures:` as `Run 1`, `Run 2`, … like reruns. The `<testsuite tests=… failures=…>`
header of the XML is no good for counting either, and what it shows depends on the Surefire
version — which comes unpinned from the Spring Boot parent (this repo: 3.5.6). Under 3.5.4 it only
carries the numbers of the outer class itself, like its `.txt`, so mostly 0; under 3.5.6 it merges
by method name like the `Results` line and has an additional `flakes` attribute. Targeted failing
probe (2026-09-25, the same test class under both versions): three `@Nested` classes with a red
`sameName()` in each, one unique red method in two of them and a green `passesEverywhere()` in two.

| Source (runs / failures) | Surefire 3.5.4 | Surefire 3.5.6 |
|---|---|---|
| Per-class console lines, summed | 7 / 5 | 7 / 5 |
| `Results: Tests run:` | 5 / 3 | 5 / 3 |
| `<testsuite>` header of the XML | 0 / 0 | 4 / 3 |
| `<testcase>` / `<failure>` elements of the XML | 7 / 5 | 7 / 5 |
| `.txt` of the outer class | 0 / 0 | 0 / 0 |
| Exit code | 1 | 1 |

It turns dangerous with `-Dsurefire.rerunFailingTestsCount` > 0 (checked under 3.5.6): a red test
whose namesake in a sibling `@Nested` class is green then counts as a "flake", and the build ends
with `BUILD SUCCESS` and exit code 0 although the test failed in both attempts. The `pom.xml`
enables no reruns; keep it that way.

**`ERROR` lines in a green run:** Many tests exercise failure paths — a mock throws, the code
catches it and correctly logs it at `ERROR`, stack trace included. A green run therefore writes
around a hundred `ERROR` lines and several thousand stack-trace lines; none of them is a failure.
CI therefore redirects each test class's output to
`target/surefire-reports/<Class>-output.txt` (`-Dmaven.test.redirectTestOutputToFile=true`) and
uploads that directory as the artifact `backend-surefire-reports` only when the run fails.
Locally the same option works; it is deliberately not the default in `pom.xml`, because the
`mysuricates-health-check` workflow greps `target/surefire-reports/*.txt` for `ERROR`.

**Class-size ratchet:** `architecture/SourceSizeRatchetTest` counts the lines of every file under `src/main/java` and fails as soon as a file exceeds 300 lines without being on the test's allowlist — or a listed file grows past its recorded ceiling. The list only moves one way: whoever shrinks a listed class lowers its ceiling; once it drops to ≤300 lines the entry is removed. New files above 300 lines get split, not listed (audit TODO 1e248ab9). No external dependency, plain JUnit.

**Request cost ratchet:** `architecture/RequestCostRatchetTest` measures two **deterministic** costs for the routes the UI hits on every page load (which ones is in the test's `BASELINE` map) and compares them against the ceilings there: the number of SQL statements and the response size in bytes. No milliseconds — wall-clock time on a developer machine depends on host load, JIT warm-up and who currently holds the single SQLite connection, so it would flake. The two pinned costs are reproducible and are exactly the ones that went wrong before: an N+1 turns 3 statements into 33 (`1e465a9e perf(api)`), a fetched TEXT blob turns a small response into a huge one (`443fa57e perf(dashboard)`). Counting goes through a Hibernate `StatementInspector` (`metrics/CountingStatementInspector` → `metrics/SqlStatementCounter`, ThreadLocal, registered in `config/StatementInspectorConfig`). Every route is measured without a surrounding transaction, the way production serves it (OSIV is off): the seed is committed, each request opens its own transactions or none, and the test empties the seeded tables after every test. Inside the test transaction the request had shared its persistence context — a service without a transaction of its own looked as cheap as one with, and `/learning/dashboard` measured 10 statements instead of 52 (TODO 5c56b3d1). The test measures against a database file of its own, freshly migrated, instead of the one the test JVM shares: rows another integration test leaves behind without a rollback would otherwise shift every ceiling — in one `-Dtest` selection two projects and three workflows were enough for a red run (TODO 5695d482); before every seed it checks that the seeded tables are empty. Like the class-size ratchet the list only moves one way: ceilings drop when a route gets cheaper (the hint test prints the new number). **A ceiling may temporarily pin a known defect, not a target** — the first run found three N+1 patterns and one unbounded response (TODOs 13d1e2a2, 9db34f3b, a05c9504); they were pinned so the gate stayed green and nothing got *worse*, and all of them are fixed by now. Since 13d1e2a2 every measured route is also held to the growth criterion: its statement count must not rise when the seed doubles. Raising a ceiling needs a reason in the commit message.

**No AI call inside a transaction:** `architecture/SlowCallInTransactionRatchetTest` reads the compiled bytecode (with the ASM copy inside spring-core, no new dependency) and fails as soon as a transaction encloses a call that starts a process: every `execute*` of `CommandExecutorService` (AI CLI, shell, container), the CLI wrappers `HostClaudeService`/`HostCodexService`/`HostPiService`, `ShellCommandService`/`ContainerShellService`, and `AiProvider.reason`/`executeAction` – also through an implementing class or a provider taken from `AiProviderRegistry`. Likewise every HTTP call that waits on a foreign server: `HttpClient.send`/`sendAsync` and opening, streams and reading the answer of `URLConnection`, `HttpURLConnection` and `HttpsURLConnection` (`connect`, `getInputStream`, `getOutputStream`, `getResponseCode`, `getHeaderField`, `getErrorStream` …; the scan compares the declared type, so each one is listed in `HTTP_CALLS`) – a webhook target that runs into its timeout would hold the connection just as long. Transactions are `@Transactional` methods (also through the class annotation, not with `NOT_SUPPORTED`/`NEVER`) and the callbacks of `TransactionTemplate.execute`; every call on the same object is followed – private helpers, lambdas, method references. With pool-size 1 such a call would hold the only connection for its whole duration. Calls into other beans stay invisible to the scan: it pins the direct shape and is no proof over the whole call graph. The exception list `KNOWN_EXCEPTIONS` is empty and only ever shrinks. The fix always has the same shape – a short read burst through `TransactionTemplate`, the AI call without a transaction, a short write burst (`WorkflowMemoryService#extractKnowledge`, `PageSuggestionService#refreshSuggestions`). Unit tests check the boundary with `testsupport/FakeTransactions`: a transaction manager without a database and a proxy that applies `@Transactional` the way the application context does; inside the mocked call, `FakeTransactions.active()` tells whether a transaction is open right now.

**Measuring request cost in production:** `RequestCostFilter` (package `metrics`, ordered outermost so authentication and rate limiting are included) records four numbers for every `/api/v1` request: total duration, **time waited for the DB connection**, SQL statements and response bytes. The key is the route template (`GET /api/v1/todos/{id}`), never the URL with the id — otherwise every id becomes its own metric. The wait share is the most important number under `maximum-pool-size: 1`: is a view slow because its own query is expensive, or because another request or a workflow step holds the single connection? That is measured in `TimingDataSource`, a `DelegatingDataSource` around `getConnection()` (registered by `DataSourceTimingPostProcessor`; `unwrap`/`isWrapperFor` stay transparent, or `ConnectionPoolWatchdogService` would lose its pool handle). `RequestMetricsCollector` keeps a bounded window per route and reports **percentiles, never averages** — an average hides exactly the one request people complain about. `RequestMetricsSnapshotService` writes one row per route into `request_metrics_daily` (V74) just before midnight and prunes past `retention-days`; the measurement itself never touches the database. A window belongs to **one calendar day**: the first request of a new day closes the running window under the day it measured, so a snapshot that only catches up at 01:04 after a standby no longer files the numbers under the new day. A **clean shutdown** (`ContextClosedEvent`) writes the running window too; before, every restart lost the day so far. A second window of the same day is **merged** into the row, not written over it: calls add up, maxima stay maxima, averages are exact, percentiles and wait share are weighted by calls (an approximation — two p95 values make no p95; TODO cab7d1d9). A day without a running backend (killed hard, machine off) stays a gap; the fact sheet names it. Served by `GET /api/v1/system/request-metrics` (live window) and `/daily?route=…&days=…` (history).

For diagnosis while developing: `Server-Timing: wait;dur=… work;dur=… total;dur=… sql;desc="n=…"` appears in the DevTools network tab of every response (`automation.request-metrics.server-timing-enabled`, **default `true`**, explicitly `false` in `application-stable/prod/test.yml`). On by default rather than tied to a dev profile: the dev instance starts with no active profile (`No active profile set, falling back to "default"`), so a dev-profile entry would have switched the header off on the very instance it is for. Not runtime-switchable, so a leaked API key cannot turn it on. Hibernate additionally logs every query over 200 ms (`hibernate.log_slow_query`, startup configuration). Runtime-switchable through the settings: `request-metrics-enabled`, `request-metrics-retention-days`, `request-metrics-slow-request-ms`. A single slow request is logged at WARN, **not** written to the ActivityLog: under pool-size=1 an extra database write on the thread of an already-slow request would compete for the very connection it is waiting for.

**Drift watchdog:** `RequestMetricsDriftWatchdogService` compares the running window against a route's own past every hour and reports regressions — the half of the measurement that does not wait to be asked. The rule lives in `RequestMetricsDriftDetector` (pure comparison, no I/O, therefore testable): a route is reported only when it had at least `drift-min-calls` calls today, a baseline exists, that baseline is not itself near zero (1 ms against 3 ms is a factor of three and means nothing), and today's p95 exceeds the baseline by `drift-factor`. The baseline is the **median** of the stored days, not their mean: one bad day (a big import, a backup running) would raise a mean and hide the next real regression. The message names both costs (`/todos p95 120 -> 900 ms (x7.5), SQL 3.0 -> 47.0`) and, where the numbers allow, the likely cause: statement count growing alongside time is an N+1; a dominant wait share points away from the route. Each route is reported at most **once a day** — an alert repeating hourly teaches people to ignore it. Written to the ActivityLog (WARN) and broadcast over WebSocket, always from the watchdog thread. Runtime switches: `request-metrics-drift-detection-enabled`, `-drift-factor`, `-drift-min-calls`, `-baseline-days`.

The same values become `FAKT` lines in the fact sheet of the `mysuricates-performance-audit.json` workflow — through the `{{ request_metrics }}` context variable (`RequestMetricsFactSheet` → `WorkflowContextDataService`), **not** through the REST API: shell blocks do not call it. That closes the gap the audit until now declared explicitly as "business /api/v1 endpoints are deliberately not called". Covered by `scripts/tests/test-audit-facts.sh`.

The fact sheet has two parts (TODO f625c113). **Live window:** its start and age (`request_metrics_window_start` — after a restart often just minutes), the call count, and the five most expensive routes by p95 and by wait share, **by name**; only routes with at least `drift-min-calls` calls are ranked, the rest is counted (`request_metrics_routes_below_min_calls`) — a p95 from two cold requests is no finding. **History** (`RequestMetricsHistoryFacts`, pure, no I/O; one query over 30 days): which days are stored and which are missing, and per route the median of the last 7 days against the days before — slower/faster from a factor of 1.5, growing responses from +20 % and 100 KB, SQL change from a factor of 1.5 in either direction (an N+1 arriving and one being fixed). A day only counts with at least `trend-min-daily-calls` calls (runtime switch `request-metrics-trend-min-daily-calls`, default 5 — lower than `drift-min-calls`, because the trend takes a median over many days). Plus the drift of the last 7 days, judged day by day from the stored rows with the watchdog's own rule — its alerts used to reach only the ActivityLog, and the audit reported "0 drifting routes" because its live window had only been running since the restart.

**Dashboard widget "API Latency":** `components/dashboard/ApiLatencyCard.tsx` (presentational) plus `ApiLatencyWidget` in `DashboardDataWidgets.tsx` (binds `useRequestMetrics`), registered as id `api-latency` in `widgetRegistry.ts`. Lists the most expensive routes by p95 with call count and SQL statements, and above them the **wait share** tile: the call-weighted mean of `waitShare` — weighted, because otherwise a single slow request would count as much as nine hundred fast ones. At 50% or more the tile turns `danger` and says the cause is more likely whatever holds the connection than the routes listed.

**Digging deeper with JFR:** When the widget or the watchdog shows a hot route but the costs do not explain it, the Java Flight Recorder gives the profile — built into the JDK, no async-profiler, no agent, no overhead in normal operation:

```bash
PID=$(cat ~/.local/state/mysuricates-dev/backend.pid)
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
| Suspected leak or GC | JFR `jdk.GCPhasePause`, plus `/actuator/metrics/jvm.memory.used` (with the API key) or, without a key, the heap numbers from `/api/v1/system/jvm-memory` |

**No scheduling under the integration test profile:** `@EnableScheduling` lives in `config/SchedulingConfig` with `@Profile("!integration")`. Every `@SpringBootTest` class runs with `@ActiveProfiles("integration")` and therefore without background jobs. Reason: those jobs otherwise write into the same SQLite file while tests run — `HealthAutoTodoService`, for example, creates a TODO as soon as the stable or test instance is unreachable (the two container environments next to the dev instance, see [deployment.md → Three-Environment Setup](deployment.md#three-environment-setup)). Such rows survive the `@Transactional` rollback (separate thread, separate transaction) and make globally counting assertions depend on whether a container happens to be running. Scheduled methods are invoked directly in tests, so their logic stays covered. When adding a new `@Scheduled` job, test it by calling the method directly — do not wait for a tick.

**Integration encryption key pinned to a temp path in the test profile:** Since 2026-09-12, just like `integration.db.path`, `IntegrationTestDbSessionListener` also sets `integration.key.path` (a per-JVM temp file, deleted on teardown), which `application-integration.yml`'s `automation.integration.key-file` points to — without this pin, `IntegrationEncryptionService.init()` would generate a fresh AES key into the developer's real `~/.mysuricates/` on every `@SpringBootTest` with empty `app_settings` (happened 2026-08-06, shadowing the real key for months).

**Backups disabled and pinned to a temp directory in the test profile:** The same trap a third time (2026-09-13): `automation.backup` defaults to `enabled: true` and `directory: ${user.home}/.mysuricates/backups/dev`, and `application-integration.yml` did not override either — every Spring context of an integration test backed up its throwaway test DB into the real Dev backup directory on startup, rotating away real backups in the process (retain count 30). Since then the test profile sets `automation.backup.enabled: false` and points `directory` at `integration.backup.dir`, which `IntegrationTestDbSessionListener` points at a temp directory per test JVM and deletes recursively at session end. The rotation in `DatabaseBackupService` is additionally hardened: it only counts and deletes files matching its own naming scheme `mysuricates-backup-yyyy-MM-dd_HH-mm-ss.db` (`BackupFileNaming.isOwnBackupFile`); manual copies or foreign `.db` files in the same directory stay visible but are never rotated. Check: `ls ~/.mysuricates/backups/dev | wc -l` must stay the same before and after `mvn test -Dtest=ContainerServiceStaleRowIntegrationTest`.

**Summarization and factory setup directories pinned to a temp directory in the test profile:** The trap a fourth time (2026-09-24): `SummarizationSetupService` and `FactorySetupService` write their instruction files into `automation.summarization.base-dir` and `automation.factory.base-dir` on every context start (`@PostConstruct`), defaulting to `${user.home}/.mysuricates/summarization` and `.../factory` — every Spring context of an integration test wrote `summarization/claude/CLAUDE.md`, `summarization/codex/AGENTS.md` and `factory/codex/AGENTS.md` into the real home. Since then both point at `integration.setup.dir`, which `IntegrationTestDbSessionListener` sets to a temp directory per test JVM and deletes recursively at session end. `config/IntegrationProfileHomeIsolationTest` pins this and also checks every key of the `application*.yml` files: if one resolves into the real `~/.mysuricates` under the test profile, the test fails. That only catches the next writer of this kind when its path is a key in an `application*.yml`, though; paths the code builds from `user.home` itself (such as the API key file via `InstanceAddress.apiKeyFile()`) stay invisible to the test.

**Scenario tests without an AI CLI:** `ScenarioIntegrationTest` runs workflow definitions from `src/test/resources/scenarios/*.json` through the real executor. Every AI call goes to a `ScriptedAiProvider` (provider ids `SCRIPTED`, `SCRIPTED_B`) with fixed answers, which also records every call. That way prompt, model, timeout and provider can be checked before the call, and gate, auto-TODO and run status after it, all without `claude`/`codex`/`pi` and without a display. Instead of one run, a scenario can also drive several runs of the same workflow (`runs`, e.g. for the audit lifecycle) or a chain (`chain`), and answer approvals (`answers`). A new case is a new JSON file. Every file names its spec source and the incident it was derived from. Catalog and format are in `scenarios/README.md`.

### Frontend Tests

```bash
cd ai-automation-frontend

# TypeScript type checking
npx tsc --noEmit

# Linting
npm run lint

# Unit and component tests (Vitest + msw)
npx vitest run
```

**No test reaches a real backend:** in jsdom a relative `fetch('/api/v1/…')` would go to
`localhost:3000` and, with `npm run dev` running, through the Vite proxy to the dev backend, writing
requests included. `src/test/setup.ts` therefore replaces `fetch`, XHR `send` and `WebSocket` with
guards (`src/test/msw.ts`) before any msw server starts, and every msw server listens with
`onUnhandledRequest: failOnUnhandledRequest`. Any request without a handler is halted and fails the
test it happened in (or the file, if it arrives after the last test). The fix is always the missing
handler (`server.use(…)` in the test or a neutral default in `src/test/handlers.ts`), never `'bypass'`.

### CI on GitHub

`.github/workflows/ci.yml` runs on every push to `main`, on every `v*` tag, on every pull request
(no branch filter, so external PRs too) and by hand (`workflow_dispatch`). Working branches from
worktrees are not pushed; `origin` only has `main`. A new run for the same ref cancels the running
one (`concurrency: ci-<ref>`) — except on `main`, where every commit gets its own result.

The backend and frontend suites only run when the change needs them. The `changes` job classifies
the changed paths with `scripts/ci/changed-scopes.sh` (plain `git diff`, no third-party action;
self-test `scripts/tests/test-changed-scopes.sh`). The backend is filtered **inversely**: it is
skipped only when *every* changed file is under `ai-automation-frontend/` outside `src/data/` and
`src/i18n/`, or a `.md` outside `docs/`, `ai-automation-backend/` and `workflow-pool/` (not
`CLAUDE.md`) — backend tests also read `docs/`, `workflow-pool/`, `scripts/lib/`,
`deploy/Dockerfile` and parts of the frontend; the list is in the script header. The frontend runs
on changes under `ai-automation-frontend/`, to the backend templates (`src/main/resources/templates/`)
and to `workflow-pool/`. Tags, `workflow_dispatch`, a missing comparison base (new ref, force push)
and any change to `ci.yml` or the script run both, as does the weekly run on Mondays at 04:00 UTC —
so interactions and drift (npm, OSV data) surface even without a matching change. A suite is skipped
only on an explicit `false`; if the classification fails, both run. The hygiene gate and the secret
scan always run.

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

`cypress.config.ts` looks for the API key in this order, never touching the DB: `CYPRESS_API_KEY`,
`CYPRESS_DEV_API_KEY`, the file `~/.autotrigger/api-key` (while the old directory exists) or `~/.mysuricates/api-key` (read-only; the
backend writes its key there on every start, `ApiKeyService`/`InstanceAddress`), finally
`AUTOMATION_API_KEY`. For the curl examples on this page, then, `export DEV_KEY=$(cat ~/.mysuricates/api-key)`.
With `CYPRESS_TARGET=test` a different order applies (the test instance runs in a container, so the
file isn't reachable without `docker exec`): `CYPRESS_API_KEY`, `CYPRESS_TEST_API_KEY`, `AUTOMATION_API_KEY`.
If Cypress finds no key at all, it logs a console warning instead of running silently — runs against
an instance with security enabled then fail on every authenticated request.

### Test Target (isolated, no business test data in Dev)

**Prerequisite:** Test instance running on port `8090`.

```bash
# Example: Start test instance with fixed API key — <test-api-key> is a value of your choice that
# the instance adopts; without it the instance creates its own on first start
cd ai-automation-backend
AUTOMATION_API_KEY=<test-api-key> docker compose -f docker-compose.test.yml up -d

# Optional: sync current definitions from Dev to Test
curl -s --noproxy '*' -X POST http://localhost:9090/api/v1/testing/sync-definitions \
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

Against a side instance (own port, own key) the frontend address is needed too, because `baseUrl`
is fixed to `:3000` — for example Vite on `5173` against a backend on `19090`:

```bash
CYPRESS_API_KEY=<key> CYPRESS_API_BASE_URL=http://localhost:19090/api/v1 CYPRESS_WS_BASE_URL=ws://localhost:19090 \
  npx cypress run --config baseUrl=http://localhost:5173
```

Cypress 16 removed `Cypress.env()`: `API_BASE_URL`, `WS_BASE_URL` and `E2E_TARGET` sit under `expose` in
`cypress.config.ts` (`Cypress.expose()`), the API key under `env`, readable only through the command
`cy.env()` — `cypress/support/e2e.ts` fetches it once per spec.

### Starting a side instance for E2E

A throwaway instance (say, from a worktree JAR) gets a directory `$S` of its own and runs entirely
inside it — including the AI CLIs it starts:

```bash
S=$(mktemp -d); cp ai-automation-backend/target/ai-automation-backend-*.jar "$S/app.jar"
mkdir -p "$S/home" "$S/config" "$S/data"
HOME="$S/home" XDG_CONFIG_HOME="$S/config" \
AUTOMATION_PORT=19090 AUTOMATION_DB_PATH="$S/data/automation.db" AUTOMATION_API_KEY=<key> \
AUTOMATION_BACKUP_DIR="$S/backups" AUTOMATION_TRAY_ENABLED=false AUTOMATION_MODULES_PRESET=full \
AUTOMATION_RATE_LIMIT_RPM=6000 AUTOMATION_DISCOVERY_AUTO_TAGGING_ENABLED=false \
  java -Duser.home="$S/home" -jar "$S/app.jar"
```

- **`-Duser.home`**: Java reads `user.home` from the user account, not from `$HOME`. Without it the
  instance reads and writes the dev instance's files under `~/.mysuricates/` (API key file, keys for
  integrations and seals, backups).
- **`HOME` and `XDG_CONFIG_HOME` in the scratch directory**: the AI CLIs the backend starts inherit `HOME` —
  with the real one they use your login, i.e. real tokens. Under `XDG_CONFIG_HOME` the backend looks for
  `mysuricates/ai.env` (Claude token for project containers). For the same reason
  `CLAUDE_CODE_OAUTH_TOKEN` and `ANTHROPIC_API_KEY` must not be set in the starting shell.
- **`AUTOMATION_DISCOVERY_AUTO_TAGGING_ENABLED=false`**: otherwise every created or changed workflow — and
  many specs create some — starts a background `claude` call for tags and category
  (`WorkflowTaggingService`), plus one per untagged workflow at startup. With `HOME` in the scratch
  directory it fails without a login, with the real one it costs tokens. The integration tests set the
  same switch (`application-integration.yml`, guarded by `IntegrationProfileAiIsolationTest`).
- **`AUTOMATION_RATE_LIMIT_RPM=6000`**: the limit of 120 requests per minute would otherwise abort a Cypress run.
- Vite for the UI on port `5173` (CORS admits only 3000/5173):
  `VITE_CACHE_DIR="$S/vite-cache" VITE_HTTPS=false VITE_API_BASE_URL=http://localhost:19090/api/v1 VITE_WS_BASE_URL=ws://localhost:19090 npx vite --port 5173`.
- **`VITE_CACHE_DIR`**: in a worktree `node_modules` is a symlink to the main checkout. Without a directory
  of its own, this Vite's dependency optimizer writes to the main checkout's `node_modules/.vite` — the
  same cache the running Vite on `:3000` serves from. `vite.config.ts` sets `cacheDir` to the path from the
  variable.

### Test Data and Cleanup

- **Dev target:** Cypress-created projects, workflows, chains, and runs end up in Dev
- **Test target:** The same data ends up on the test instance
- `cy.apiCleanup()` only cleans up Cypress test data with the prefix `Cypress`
- `chains.cy.ts` really executes a chain; every workflow run starts the project container
  `automation-<projectId>` along the way. The spec therefore waits for the chain run to end before deleting
  the chain, and cleans up in `after()` through the instance's API (let the runs settle,
  `POST /projects/{id}/stop`, delete workflows and project) — not through Docker directly
- For the test instance, `POST /api/v1/testing/reset` can additionally be used if a complete reset is desired

Specs live under `cypress/e2e/`, the helpers (API key, target, custom commands, test data through the API) under `cypress/support/`.

**Conventions:**
- English locale and the seen welcome tour are set via `localStorage` before every page load (otherwise the tour covers every page)
- Selectors use `aria-label`, button text, `h1` text, `placeholder`, `role="switch"` (no `data-testid`)
- `before()` / `after()` hooks ensure clean test data via `cy.apiCleanup()`

Clean up old workflow runs: one at a time via `DELETE /api/v1/executions/{id}` (see
[API reference](api-referenz.md#apiv1executions)), in bulk automatically via run retention
(default 90 days) — see [architektur.md → Database maintenance](architektur.md#database-maintenance-sqlite-pool-size1)
for retention and how to switch it off.

### Installer on a fresh machine (installer probe)

`scripts/installer-probe.sh` checks `scripts/setup.sh` the way a new user experiences it: in a fresh
container (default `ubuntu:24.04`, with `--image rockylinux:9` also a dnf system) that has nothing but
git, sudo and tzdata. It always checks a committed state (default `HEAD`, otherwise `--ref`), never
the working tree. Requires Docker.
`setup.sh` installs Docker itself via get.docker.com, but on RHEL rebuilds (Rocky, AlmaLinux, Oracle
Linux) from Docker's CentOS repository: Docker's Rocky repository has no `docker-ce`, and get.docker.com
does not know the others (`deps_rhel_clone` in `scripts/lib/deps.sh`).

```bash
./scripts/installer-probe.sh --interactive          # fresh machine as a shell — click through yourself
./scripts/installer-probe.sh --ref main             # automatic run with report
./scripts/installer-probe.sh --image rockylinux:9   # the same on a dnf system
./scripts/installer-probe.sh --ref main --keep      # then look at the fresh instance in the browser
./scripts/installer-probe.sh --setup-flags "--with-pi"   # phase 2 with extra flags, here installing pi too
./scripts/installer-probe.sh --way container        # the default way: the instance in a container
./scripts/installer-probe.sh --way staged           # Dev · Test · Prod, three containers
./scripts/installer-probe.sh --cleanup <run>        # remove what a run with --keep left behind
```

The interactive machine shares the host network (`--network host`): open the address `setup.sh`
names at the end directly in the browser; the installer skips ports that are busy on the host. Pick
"on this machine" in the mode step — there is no Docker inside the probe.

The automatic run takes one way from [GETTING-STARTED.md](GETTING-STARTED.md) (`--way`, default
`host` = "one instance on this machine") in two phases, each under a pseudo terminal:
`setup.sh --check` (check only) and `setup.sh --yes …` (one pass up to the running instance) — on the
machine with `--runtime host --bind 0.0.0.0`, in a container (`--way container`) with the defaults and
only `--port`, staged (`--way staged`) with `--mode staged --port`. Each phase writes its own exit code to a file inside the container — through
`script` and `docker exec` an abort once came back as 0. The run turns red when phase 1 does not name
the missing tools, phase 2 does not end with exit 0, health or the interface on the one port do not
answer, the login with the generated key fails, the key shows up in a log, the backend is not built
exactly once (on the machine only), or the clone is no longer clean afterwards (`git status`). The
container ways additionally check per instance that it is published on `127.0.0.1` only and has no
Docker socket (the defaults); staged also three healthy instances on different ports and Prod with
mandatory seals (`environment` Prod, `sealing` and `require-sealed-for-unattended` on — from the
instance's own `/api/v1/system/capabilities`). The result is a single HTML
page with terminal transcript and screenshots under
`~/.local/state/mysuricates/installer-probe/<time>-<sha>/report.html` (`latest` points to the last
run). A run downloads JDK, Node, npm packages and Maven dependencies fresh every time.

For the container ways the probe machine gets the host's Docker: the socket (the `docker` group in it
has the socket's group ID; `setup.sh` installs Docker itself as for any user, the daemon is the
host's), the host network, and a host folder under the same path for the clone and the projects
folder — the bind mounts of the inner compose are resolved by the host's Docker daemon. The host
network rather than a special binding: `setup.sh` then sees the ports that are busy on the host by
itself, the instances stay on the default `127.0.0.1`, and the probe checks them from outside right
there. The probe picks ports from 19190 on and never 8090, 9090, 9091 or 9095 (Dev, Test, Stable,
Tryout — even when they are not running). Every Docker name of a run carries its prefix
`mysuricates-installer-probe-<time>` (`MYSURICATES_PREFIX` for `instance.sh`: container, compose
project, volumes `…-data`/`-secrets`, image); at the end — also after an abort — the probe removes
exactly those, checking each name against the prefix first. Running instances on the host stay
untouched. Whoever holds the socket controls the host's Docker: only run it on states whose
`setup.sh` you trust. What it does not check is whether `setup.sh` starts a Docker daemon — there is
none inside the container.
The screenshots are taken by Playwright from `ai-automation-frontend/node_modules` on the host. If
the browser for the installed Playwright version is missing (typically after an update), the login
check turns red with "Browser fehlt — im Frontend `npm run pw:install` ausführen" (browser missing,
run `npm run pw:install` in the frontend); the probe downloads nothing.

Before a release it runs as the workflow **Installer probe** (an entry in the workflow pool — the
bundled workflow definitions under `workflow-pool/` that any instance can install, see
[architektur.md → Workflow Pool](architektur.md#workflow-pool) —, entry
`mysuricates-installer-probe`, variable `ref`, default `main`). The workflow takes the probe script
from the state under test, not from the branch that happens to be checked out. The script tests run
without Docker: `bash scripts/tests/test-installer-probe.sh` and
`python3 -m unittest discover -s scripts/tests -p 'test_installer_probe_report.py'`.

### AI CLI versions (installer and tryout)

Claude, Codex and pi come in a fixed version (pi c3a4cbf6, Claude and Codex 38f30352): a new CLI
version can change flags or the output format that `HostClaudeService`, `HostCodexService` and
`HostPiService` rely on (`--output-format`, `exec`/`-m`, `--thinking`, `<backend>/<model>`) without
anyone noticing. The version lives in one place per provider,
`ClaudeAiProvider`/`CodexAiProvider`/`PiAiProvider.PINNED_VERSION`; from it come the install command
in the installer's catalog (status card, `--with-pi`; for Claude also the native installer's
`bash -s <version>`) and — kept equal by a test — `ARG CLAUDE_VERSION`, `ARG CODEX_VERSION` and
`ARG PI_VERSION` of the tryout stage in `deploy/Dockerfile`. The native Claude CLI updates itself on
the machine (auto-update): there the pin only sets the initial state of a fresh install. In the
tryout image it stays, because `DISABLE_AUTOUPDATER=1` is set there. Moving to a new
version:

1. Install the new version on your own machine (`npm install -g @anthropic-ai/claude-code@<version>`,
   `@openai/codex@<version>` or `@earendil-works/pi-coding-agent@<version>`) and run a workflow with
   that provider — for pi with a `<backend>/<model>` model and `effort` set.
2. Set `PINNED_VERSION` in the provider and the matching `ARG …_VERSION` in `deploy/Dockerfile`.
3. Regenerate the catalog:
   `cd ai-automation-backend && ./mvnw test -Dtest=ProviderCatalogTest -Dprovider.catalog.write=true`
   (the same test then checks it matches the Dockerfile).
4. `./scripts/installer-probe.sh` (for pi with `--setup-flags "--with-pi"`) and, before the next
   tryout image, `scripts/tryout.sh build --ref HEAD`.

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
4. Creates the git commit `Release vX.Y.Z` and sets an **annotated** git tag `vX.Y.Z` (`git tag -a`, carries date/author/message) — the basis for the GitHub release that gets created automatically for every `v*` tag ([„GitHub release on every tag"](#github-release-on-every-tag) below)
5. Sets `pom.xml` **and** `package.json` to the next development version `X.Y.(Z+1)-SNAPSHOT` and commits `Start development of vX.Y.(Z+1)` — otherwise every commit after the tag would carry the release number and the version tile would report drift permanently

**Prerequisite:** Clean working tree (no uncommitted changes).
**Test:** `bash scripts/tests/test-release.sh` runs the flow in a throwaway repository.

### Load test before a release

Every tag is measured under load first (TODO 1a6058dc). `release.sh` looks for a result of `scripts/load-test.sh` for exactly the commit being tagged (HEAD before the bump). **GREEN** passes, **YELLOW** passes with a note. **Missing** or **RED** makes `release.sh` ask; without a terminal it stops. `--skip-load-test` or `RELEASE_SKIP_LOAD_TEST=1` releases without one and says so.

```bash
scripts/load-test.sh                 # measure HEAD (builds the JAR from the commit, ~5 min)
scripts/load-test.sh --ref v0.7.8    # another commit; --jar PATH skips the build
```

- **Throwaway instance** from exactly that commit (`git archive` + `mvn package`, JAR cached per commit under `~/.cache/mysuricates/load-test/jars/`): own port, `-Duser.home`/`HOME`/`XDG_CONFIG_HOME` in the work directory, own DB and API key, `env -i` (no `AUTOMATION_*` from the dev shell), no Docker, no backup, no tray, no rate limit, no auto-tagging. `claude`/`codex`/`pi` are stand-ins on the PATH: an AI call costs no tokens but turns the run RED.
- **Synthetic seed** from `scripts/load-test/scenario.json` (workflows, some with 200 KB of step output, finished runs, TODOs) — reproducible and shareable, no copy of the dev DB. Any change to amounts, mix or pace bumps `version`; comparisons only use runs of the same version.
- **Load like real users** (`driver.py`): readers open the UI pages with exactly their API calls, run starters start shell workflows and poll the run page until done, a TODO writer creates and switches status. Warm-up first, then the measured phase. Every request with client time and the instance's `Server-Timing` (connection wait, work, SQL).
- **Evaluation** (`report.py`): p50/p95/p99 per route, wait share, SQL, response size, runs, heap; RED above 1 % errors, on a failed run, p95 over 5 s or an AI call, YELLOW for a read route over 1 s p95, heap over 85 % or p95 ×1.5 against the baseline. The driver also measures the host load (1-minute load per CPU, `FAKT load_host_load`): with a median above 1 the machine was busy with something else — the verdict is then YELLOW with "repeat the measurement", and regressions against the baseline are listed but not counted (a trial run next to parallel builds was 5 to 20 times slower; TODO b8e1de5e). The wait share alone is no finding — under load with pool-size 1 nearly every request waits (50–79 % at p95 below 450 ms in the first run); it only explains a route that is slow anyway. The **baseline** is the newest run of the same scenario on an ancestor commit, tagged commits first.
- **Result** under `~/.local/state/mysuricates/load-test/<time>-<sha12>/` (`MYSURICATES_LOAD_TEST_DIR`): `facts.txt` (FAKT lines), `result.json`, `summary.txt` (for `release.sh`), `raw.json`, `instance.log`.

From the UI it runs as the pool workflow **Load test before a release** (`workflow-pool/mysuricates-load-test.json`, variable `ref`): the script comes from the commit under test, Opus explains the fact sheet (bottlenecks, comparison, recommendations), the gate follows `FAKT load_verdict`, never the AI. **Test:** `python3 -m unittest discover -s scripts/tests -p 'test_load_test_*.py'` (evaluation, verdict, baseline, driver against a test server); `test-release.sh` covers the prompt.

### Interaction with Staging

After a release, `./scripts/staging.sh promote` brings the new version into the stable environment, a
Docker container on port 9091 next to the dev instance. Version comparison and promote recommendation in
the settings: [deployment.md → Version Display](deployment.md#version-display).

### Git Tags

| Tag Format | Created by | Kind | Purpose |
|---|---|---|---|
| `v0.1.1` | `release.sh` | annotated (`git tag -a`) | Semver release marker, triggers `release.yml` |
| `stable-20250225-143022` | `staging.sh promote` | lightweight | Deployment timestamp, stays local |

**Push:** `git push origin main "vX.Y.Z"` or `git push --follow-tags` — both only take the annotated tag
along. **Not** `git push --tags`: that would push every local `stable-*` tag to GitHub too, mixing it into
the release list.

### GitHub release on every tag

`.github/workflows/release.yml` runs on every push of a `v*` tag and automatically creates a
GitHub release (`gh release create "$TAG" --verify-tag --title "$TAG" --notes-file notes.md`). The
notes are generated **deterministically, without AI** by `scripts/release-notes.sh <prev-tag>
<tag>`:

- **Overview**: subject lines of the merge commits between the two tags
  (`git log --first-parent --merges`). Internal TODO-shorthand brackets are stripped for the public
  notes (`scripts/lib/strip-todo-brackets.py`): a trailing bracket whose content starts with an
  8-hex code (title, comma lists and nested brackets afterward don't matter, e.g.
  `(<8-hex> title (with bracket))` or `(<8-hex>, <8-hex>, <8-hex>)`), a trailing bracket whose last
  comma-separated element is a bare code (`(as of 2026-09-22, <8-hex>)`), and a bracket anywhere in
  the subject that consists only of codes (`(TODO <8-hex>)`). An ordinary bracket without a code is
  left alone.
- **Changes by type**: non-merge commits, grouped by conventional prefix
  (`feat`/`fix`/`perf`/`refactor`/`docs`/`test`/…).
- **Behavior changes**: entries from [„Behavior Changes"](#behavior-changes) below that are dated
  after the previous tag — without such entries, the section is left out.
- **Comparison**: link to the compare view `prev...tag` (left out for the very first release, which
  has no previous tag).

The script runs without a previous tag (`scripts/release-notes.sh "" v0.1.0`) and also when the
current repo has no matching merge commits (e.g. in the curated public repo, which does not inherit
the working repo's merge history) — the affected sections are simply left out instead of raising an
error. The action uses a supplied notes file (`release-notes/<tag>.md`) if one exists — for example
from the [handover to the public repo](#handover-to-the-public-repo), which brings a verified
commit plus tag there — and otherwise generates the notes itself from the history of the repo the
workflow runs in. The links (comparison, behavior changes) point to the GitHub repo from `origin`;
`RELEASE_NOTES_REPO_SLUG=<owner>/<repo>` sets it from outside, set to empty the links are left out,
and an `origin` that is not a GitHub address yields none either.

**Test:** `bash scripts/tests/test-release-notes.sh` runs `release-notes.sh` in a throwaway repo
(merges, type sections, TODO bracket gets stripped, first release without a previous tag).

### Handover to the public repo

The working repo stays private; the public repository gets **one** squashed, verified commit plus
an annotated tag per release. The logic lives deterministically in `scripts/release-handover.sh`,
a MySuricates workflow runs the sequence with an approval.

```bash
scripts/release-handover.sh v0.8.0 ~/path/to/public-clone               # gates without build
scripts/release-handover.sh --with-build v0.8.0 ~/path/to/public-clone  # + mvn test, tsc, lint
scripts/release-handover.sh --discard v0.8.0 ~/path/to/public-clone     # take back, if not pushed
```

The script:
1. Checks the preconditions: tag in the working repo, target is the root of a clean clone on a
   branch, not behind its upstream, the tag does not exist there yet and neither does a newer one,
   author and committer in the target use the GitHub noreply form, the private hygiene lists can be
   found (`$HYGIENE_PRIVATE_LIST`/`$HYGIENE_PRIVATE_TERMS`, `~/.config/mysuricates/` or the
   untracked copies in the working repo, which it passes on).
2. Exports the tag's tree via `git archive` without the patterns from
   `scripts/release-handover.exclude` (read from the tag itself; older tags without a list use the
   one from the working tree) and replaces the target's tracked content with it. `.git`,
   `release-notes/` (the notes of earlier releases) and ignored files such as build caches stay.
   Exactly the archive's file list is staged, with `-f` (tracked files under an ignore rule, like
   the tray icons, belong to it) and without line-ending conversion.
3. Stops with exit 3 if the result equals the target — nothing is left behind then.
4. Writes the notes from `scripts/release-notes.sh` to `release-notes/<tag>.md` (the previous tag is
   the last handed-over release, the links point to the public repo) and commits `Release <tag>`.
5. Checks exactly this commit: fidelity (every file has the same blob and mode as in the tag,
   nothing else apart from `release-notes/`), `public-hygiene.sh --tracked`,
   `pool-neutrality.sh`, the README sync state (`readme_sync.py check --current`: `README.md`
   reflects the tag's `README.de.md` — in the public repo it resolves only via the content hash in
   the marker, so run the sync before tagging), gitleaks (if installed), the docs gates (`pre-push-checks.sh --tree`),
   with `--with-build` also `mvn test` plus `npm ci` (only without `node_modules`),
   `tsc --noEmit` and `npm run lint`. Any finding takes the commit back; the target is as before.
6. Sets the annotated tag and ends with the diff statistics, the gate summary, the lines
   `handover_status:`/`handover_commit:`/`handover_branch:` and the push command. **It never
   pushes.**

Exit codes: 0 ready (or discarded), 1 stopped (finding or precondition), 2 usage error, 3 nothing to
hand over.

**Exclusion list** (`scripts/release-handover.exclude`): git pathspecs with glob magic, each entry
with a reason. It goes out itself and therefore names nothing private — such files are covered by a
pattern. It is a safety net for the private, untracked paths from `.gitignore`, among them the
one-off workflow migration of one particular instance (`scripts/workspace-isolation/prepare-*.py`,
`pool-overrides/`) plus its test: they live only locally, and the entries take effect only if one of
them is tracked again by mistake. Excluding a file that a test, the build or the docs
refer to shows up at the latest in a run with `--with-build`.

**The workflow** lives only in the maintainer's instance (not in the pool: it presumes a public repo
of one's own) and consists of four blocks without AI:

| Block | Type | Task |
|---|---|---|
| `vorbereiten` | `shell` | `release-handover.sh` with the context variables `{{ tag }}`, `{{ public_clone }}` and `{{ with_build }}` (`1` = `--with-build`), read via `set -- {{ tag }}; TAG="$*"` with `shell_escape_variables`; exit 3 becomes 0, `timeout_seconds` covers the test suite |
| `freigabe` | `approval` | only with `handover_status: READY` in the output of `vorbereiten` (`run_if`); the question shows that output — diff statistics and gates; answers `Pushen`/`Verwerfen` in `handover_decision` |
| `push` | `shell` | only on `Pushen`: checks that HEAD and tag in the clone are still the approved commit, then `git push --atomic origin refs/heads/<branch> refs/tags/<tag>` |
| `verwerfen` | `shell` | only on `Verwerfen`: `release-handover.sh --discard` takes commit and tag back, so a new run for the same tag is possible |

In the public repo, `.github/workflows/release.yml` then creates the GitHub release from the
supplied notes file.

**Gate before the push:** the "Public Readiness" chain can measure the public clone instead of the
working repo — started with the context `{"readiness_repo": "<path to the public clone>"}`, which
the chain passes on to evidence and claims. The shell blocks only read there; the `stand` block then
reports, instead of "commits ahead of origin/main", the working repo's commits since the last
handover (newest `v*` tag in the clone) and whether the handover has been pushed yet. Without the
value (default: empty) both workflows measure the run's isolated clone as before.

**Way back** (contributions from the public repo, per `CONTRIBUTING.md` only after agreement):
since every handed-over file has the same blob as in the tag, a patch from the public clone applies
in the working repo with a three-way merge:

```bash
git -C ~/path/to/public-clone fetch origin pull/<nr>/head:pr-<nr>
git -C ~/path/to/public-clone format-patch --stdout origin/main..pr-<nr> | git am -3
```

`git am` keeps the contributor as author; the change then goes out again with the next release tag.

**Test:** `bash scripts/tests/test-release-handover.sh` runs the script between a throwaway working
repo and a local throwaway "public" repo (bare + clone): exclusion, replaced content, fidelity even
for CRLF files, commit, annotated tag, notes file, stop and complete reset on a gate finding, stop
without a change, preconditions, `--discard` and that nothing gets pushed.

### Release overview and probe

Where the archive and the public repo stand, which checks the way between them has and how the kinds
of release run is shown by a page that `scripts/release-overview.py` generates deterministically from
both repos and an event log. It lives next to the log in the state directory, not in the repo:
`~/.local/state/mysuricates/release/lagebild.html`, in the probe
`~/.local/state/mysuricates/release-probe/lagebild.html`. The last step of every release run prints
the path; in the run view it is a link that opens the page. Four workflows of the maintainer's
instance run the process and write an event after each step (`release-overview.py event`):

| Workflow | Task |
|---|---|
| "Release: Lagebild" | regenerate the page; status and summary as a dashboard widget |
| "Release: Probe zurücksetzen" | rebuild the probe (`scripts/release-probe.sh reset`) |
| "Release: Erstschnitt" | pre-check, backup as a bundle, `rewrite.sh`, approval, push of the cleaned history with its tag into an empty target, create the public clone |
| "Release: Übergabe" | `release-handover.sh`, approval, atomic push or `--discard`; in the probe `release-probe.sh advance` first |

The context value `mode` decides: `probe` (default) replaces both GitHub repos with local bare repos
under `~/.local/state/mysuricates/release-probe` — a clone of the working repo (`work/`, `main` starts
at the cut commit) with `archive.git` as `origin`, and an empty `public.git`. That way the whole way can
be played through beforehand. `live` takes the working repo (`archive_repo`) and the public clone
(`public_clone`); there the first cut requires that `origin` already points to `MySuricates-archive`
and that the target (`public_remote`) is empty. The paths per mode are in
`scripts/lib/release-paths.sh`.

**Test:** `python3 -m unittest discover -s scripts/tests -p 'test_release_overview.py'` (check lines of
the three scripts, event log, page and status, paths per mode, `release-probe.sh` with throwaway
repos).

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

### Language in Code

| What | Language |
|---|---|
| **Comments** (Javadoc, inline comments, comments in shell, Python, YAML and SQL files) | English, even when the session runs in German |
| **Test descriptions** (`describe`/`it`/`test`, `@DisplayName`) | English |
| **Technical names** (classes, paths, endpoints, enum constants, keys) | English |
| **UI text** | through i18n (`de.ts`/`en.ts`), never in code |
| **Commit messages** | English since 2026-10-06 (TODO 440473d4); the older history is translated when it is published (TODO 0bf5eed4). The TODO goes into a trailer `Todo: <uuid> (<title>)` at the end, not into the subject (TODO ee4fc06a) – `scripts/todo-batch/batchlib.py` reads the same form |
| **Log and error messages, docs, TODO titles** | unchanged, as before |

The existing code has been translated (TODO 9468432d). What remains German are quoted UI texts, quotes and doc
headings inside otherwise English comments, the pre-push gate's rule files and comments inside embedded heredocs.
Existing Flyway migrations (`V*.sql`) stay untouched, including their comments – they are part of the checksum.

`scripts/tools/german_comments.py` keeps it that way: `ratchet` fails when a file has more German comment lines
than its ceiling in `scripts/tools/german-comments-baseline.txt` (new files: 0); the Python test
`test_german_comment_ratchet.py` runs in the CI's discover step. Detection is a heuristic (an umlaut or two German
function words) and does not catch every German sentence. When translating comments, lower the ceilings with
`ratchet --update` and verify with `check-diff [BASE] [PATHS]` that only comments, test descriptions and Python
docstrings changed (TS/TSX exactly via the TypeScript parser).

### Backend

| Convention | Description |
|---|---|
| **Entities** | JPA entities with Lombok (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`) |
| **DTOs** | Java records (`record` instead of classes) for request and response objects |
| **Mapper** | MapStruct for entity ↔ DTO conversion (automatic code generation) |
| **IDs** | UUID as string (`@GeneratedValue(strategy = GenerationType.UUID)`) |
| **JSON fields** | `@Convert(converter = JsonMapConverter.class)` for `Map<String, Object>` in TEXT columns |
| **Auditing** | `@CreatedDate`, `@LastModifiedDate` via the own `@EntityListeners(AuditTimestampListener.class)` — not via Spring Data auditing (`@EnableJpaAuditing` would need `spring-aspects` and with it `aspectjweaver`, EPL-2.0, not AGPL-compatible) and not via Hibernate's `@CreationTimestamp` (set only at flush, truncated to whole seconds in SQLite). `AgplDependencyGuardTest` pins this |
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
| **Hints in place** | `<Hint id="…">` sits in the component it explains, never in a central list — a page that is missing (preset, Simple Mode, module off) takes its hint with it. Registry with version in `src/data/hints.ts` (raise the version only for a change in substance; the hint then shows again), copy in `src/data/hints.<lang>.json`: what and why, never where to click. `i18n/__tests__/hints.test.ts` keeps registry, copy and usage in step. **No menu entry without an explanation:** every entry in `utils/navItems.ts` names its page's hint in the required field `hint`, and `utils/__tests__/navHints.test.ts` checks through the routes in `App.tsx` that the full (non-Simple) page renders it; the module of each hint for the home-field search (`HINT_MODULES`) follows from it. Conversely, a hint may appear precisely because a module is missing: `build-from-description` in the template picker names the assistant, the factory and wishes on core, and how to switch them on |
| **Forms** | Controlled components with React state |

### Annotation Processors

The backend uses three annotation processors that must be correctly ordered in the compiler configuration (`pom.xml`):

1. **Lombok** — Generates getters, setters, builders, etc.
2. **MapStruct** — Generates mapper implementations
3. **lombok-mapstruct-binding** — Bridge between Lombok and MapStruct

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
3. **Pool neutrality** via `scripts/ci/pool-neutrality.sh`: the definitions under `workflow-pool/`
   ship with the repository and readers install them, so they must contain nothing specific to an
   instance or a person. The built-in template
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
   A line prefixed with `low:` (`low: <term>`) marks a **weak** term: it stays on the list, but a
   hit is only a warning ("privater Begriff, schwach (Zeile N …)") — no violation, no exit 1,
   neither in the tracked set nor on commit or push; the evidence workflow rates it LOW. Meant for
   terms that should stay visible without holding up a release. Every reader of the list (tracked
   set, added lines, commit messages in the pre-push gate) parses it through the same helper
   `hygiene_private_terms_load` in `scripts/lib/hygiene-rules.sh`.

**Run workspace retention:** every isolated checkout (`RunWorkspaceService`, default
`~/.mysuricates/workspaces/<project>/<run>/` — even when the data directory is still `~/.autotrigger`;
otherwise set by `automation.run-workspace.root`/`AUTOMATION_RUN_WORKSPACE_ROOT`) stays on disk after the run — nothing removes it
automatically. `scripts/tools/prune-workspaces.py` conservatively removes only the `repo/` checkout
of completed, clean runs: the matching run must be `COMPLETED` per the API and older than
`--retention-days` (default 30), the checkout unchanged (`git status --porcelain` empty), and the
commit matching the manifest — logs and manifests are kept. Without `--apply` it is a dry run only
(`WOULD REMOVE …`). The script uses the same root (`AUTOMATION_RUN_WORKSPACE_ROOT`, else
`~/.mysuricates/workspaces`); `--root` overrides it.

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
every push event and every pull request (there merge base..PR head); the rules live in
`scripts/lib/hygiene-rules.sh`.

Checked per push range (remote state..local; for a new branch everything no remote ref knows yet):

1. **Commit metadata.** Author and committer carry the GitHub noreply form
   (`<id>+<login>@users.noreply.github.com`, `HYGIENE_COMMIT_EMAIL_REGEX`); the message contains no
   forbidden literals, no private terms (weak ones, prefix `low:`, only as a warning) and no e-mail
   address outside a trailer (`Co-Authored-By`, `Generated-By`, `Signed-off-by`, …). The address
   itself appears nowhere in the repository, only its form. The subject has at most 72 characters
   (`HYGIENE_SUBJECT_MAX`, TODO 7ce5c71a); the TODO goes into a trailer `Todo: <uuid> (<title>)` at
   the end. `commit-msg` always checks this, the push range and CI only for commits whose author
   date is not before `HYGIENE_SUBJECT_SINCE` – older commits do not block, not even after a rebase,
   which keeps the author date. Subjects Git writes itself (`Revert "…"`, `fixup!`, `squash!`,
   `amend!`) are exempt.
2. **Hygiene gate on the range** (`public-hygiene.sh --range`): what is newly added. Its warnings
   show up even when the range is clean.
3. **CI tree gates that depend on the code:** documentation numbers, documentation links with DE/EN
   parity, diagram drift. A missing gate script is a hard error.
4. **gitleaks on the range** — only if installed, otherwise skipped with a notice. Nothing is
   installed on the fly; CI scans anyway.
5. **Exception files in the range.** If the range changes a file from which the gate and CI read
   their exceptions — `.gitleaksignore`, `.gitleaks.toml`, `scripts/lib/hygiene-rules.sh`,
   `scripts/ci/pre-push-checks.sh`, `scripts/ci/public-hygiene.sh`, `scripts/ci/changed-scopes.sh`
   (its list skips builds and tests), `scripts/git-hooks/*`,
   `.github/workflows/ci.yml` —, the gate blocks and shows the extent of the diff. Otherwise whoever
   loosens such a file in the same push would get green from the very check they are weakening.
   After review, release it for exactly this state only: `MYSURICATES_PREPUSH_ALLOW_RULES=<sha of
   the tip> git push` (at least 7 characters, several separated by commas; the message names the
   command). A permanently set `=1` or `MYSURICATES_PREPUSH_SKIP` releases nothing, and the release
   stays visible as a warning. CI does not block here (`--rules-report-only`); it reports the range
   as a warning and in the job summary.
6. **Build check by scope.** The same script as in CI decides which suite is affected
   (`scripts/ci/changed-scopes.sh`): frontend suite → `tsc --noEmit` and `npm run lint` (about a
   minute); backend suite → `mvn -q test-compile` (seconds). The backend is filtered the other way
   round — it also builds for `docs/`, `workflow-pool/`, `scripts/lib/` and `CLAUDE.md`, because
   its tests read those files; only frontend code and Markdown outside them leave it out. The
   reasoning is printed as a `Scope:` line.

Not here: the full test suite (CI and the commit rule in CLAUDE.md), the tracked set (`--tracked`
in CI and in the "Public Readiness" chain) and anything that needs judgement.

Skipping when it must be: `MYSURICATES_PREPUSH_SKIP=tree,build git push` — every omission is
reported; a gate that does not measure is not green. `git push --no-verify` remains the bypass; do
not reach for it by reflex. A hit on message or address is healed by `git commit --amend` (with
`--reset-author` after `git config user.email …`), older commits by `git rebase -i`.
Self-test: `bash scripts/tests/test-pre-push-checks.sh`.

## Diagram pages

The diagram documents under `docs/` exist twice: as Markdown (`<name>-diagrams.md`) and as HTML
(`<name>-diagrams.html`). **The Markdown file is the source** — GitHub renders ```mermaid fences
natively but does not render HTML files in the repository at all. The HTML version is meant for
GitHub Pages and is generated, as is the index [docs/en/diagramme.md](diagramme.md):

```bash
python3 scripts/docs-diagrams.py                # regenerate HTML, English pages and indexes
python3 scripts/docs-diagrams.py --check        # reports drift and translation gaps, exit code 1
python3 scripts/docs-diagrams.py --add-missing  # add missing translation keys with empty values
python3 scripts/docs-diagrams.py --site _site   # pages for GitHub Pages into an empty directory
python3 -m unittest discover -s scripts/tests -p 'test_docs_diagrams.py'
```

Generated means complete, not just the diagrams. The prose, tables, cards and file trees of the HTML
page come from the same Markdown file; otherwise the rest of the page would be a second,
hand-maintained version. Editing an HTML file by hand therefore does not last — the next run
overwrites it, and `--check` reports it in CI first.

The HTML shell (colors, layout, Mermaid integration) is a template in the script, the same for all
pages. Which Markdown features the template supports is described in the header comment of
`scripts/docs-diagrams.py`; headings, paragraphs, lists, tables, code fences and the usual inline
markup are all that is needed.

**English version.** The German page is the source. `docs/en/<name>-diagrams.md` and `.html` as well
as `docs/en/diagramme.md` are generated from it — there is no second, hand-maintained version.
Structure, diagram skeletons, code blocks and file trees come from the German page, the text from one
translation table per page, `docs/i18n/<name>-diagrams.en.json` (the same pattern as the UI's
`de.ts`/`en.ts`): the key is the German text segment, the value its English translation. Segments are
titles, subtitles, headings with their tagline, paragraphs, list items, table cells and the Mermaid
labels — node texts, edge labels, `subgraph` titles, notes, sequence messages, `alt`/`loop` labels;
the rules per diagram type live in `scripts/lib/mermaid_labels.py`. In code blocks and file trees the
comment after `←` is a segment (without its marker); the markers `[NEU]`/`[ENTFERNT]` read
`[NEW]`/`[REMOVED]` on the English page, without a table entry (08e6fa4d). Node IDs are never translated,
even where Mermaid displays them (`PENDING --> RUNNING`, `subgraph Compose`): whatever should read
differently in the two languages needs a label (`ID["Text"]`). A segment without letters outside
inline code needs no entry; a value equal to its key is fine (class names, labels that are already
English).

`--check` fails when a segment has no translation (key missing or value empty), when a key is
orphaned, when a value states inline code or numbers differently from the original, or when the
translation changes the page structure or a diagram's skeleton. Whoever changes a German diagram page
therefore updates the table in the same commit: `--add-missing` adds new keys with an empty value in
reading order; orphaned keys stay until someone deletes them — often their value, slightly adjusted,
is the translation of the new key. Unknown Mermaid syntax (a new diagram type, a line without a rule)
aborts with exit code 2 instead of letting German text through silently.

**GitHub Pages.** `.github/workflows/pages.yml` publishes the pages on every push to `main` that changes
a diagram source, a translation table or the script (and by hand via `workflow_dispatch`): first
`--check`, then `--site _site`, then `actions/upload-pages-artifact` and `actions/deploy-pages`. `--site`
builds from the Markdown source, not from the HTML files in the repository, and writes `index.html`
(index from the same table as `diagramme.md`), `de/<name>-diagrams.html`, `en/index.html` and
`en/<name>-diagrams.html`; every page links the index and the other language. If a translation is
open, nothing is written (exit code 1). Needed once in the repository settings: set Pages to GitHub
Actions (Settings → Pages → Source **GitHub Actions** — not "Deploy from branch", which would publish
all of `docs/` including `plans/`) and set the repository variable `PAGES_ENABLED=true` (Settings →
Secrets and variables → Actions → Variables). Without the variable only the build including `--check`
runs; the deploy job is skipped.

## Generated references

Two reference pages are generated from the code instead of maintained by hand, each in German and English:

| Page | Source |
|---|---|
| [api-referenz.md](api-referenz.md) | REST controllers under `ai-automation-backend/src/main/java/com/automation/controller/`, `ErrorCode`, module names from `ModuleCatalog` |
| [block-referenz.md](block-referenz.md) | `ai-automation-frontend/src/data/blockConfigSchema.json` |

```bash
python3 scripts/docs-reference.py --write   # rewrite both pages in both languages
python3 scripts/docs-reference.py --check   # compare only: exit code 1 names every stale page
python3 -m unittest discover -s scripts/tests -p 'test_docs_parity_check.py'
```

Every generated page starts with the comment `<!-- Erzeugt von scripts/docs-reference.py – nicht von
Hand ändern -->` and a hand-written introduction from `scripts/docs-reference/<page>.<de|en>.md`;
everything after it comes from the source, a change by hand does not last. Whatever a table lacks belongs
in the source: the purpose of an endpoint is the first sentence of its handler method's Javadoc (without
Javadoc the cell stays empty), the explanation of a config key is its `doc` text in the schema.
The first sentence is English, because the same cell appears in the English version too. Further
sentences of the Javadoc appear below the area's table as “Notes” – that is where the rules a caller must
know belong (status codes and their meaning, limits, conflicts, side effects), not field lists.

`docs_parity_check.py` calls `--check` and reports a stale page as a finding. So the check applies
wherever the documentation gate runs, the pre-push gate included: whoever changes an endpoint, its
Javadoc or a config key regenerates the pages in the same commit. `api-endpoint-diff.sh` keeps comparing
the endpoints with both versions of the API reference independently.

[datenmodell.md](datenmodell.md) is not generated: the JPA entities name tables and columns, but many
foreign keys (those of `todos`, for example) are plain text fields there, and `NOT NULL`, defaults and
`ON DELETE` exist only in the Flyway migrations.

## Dependency advisories (frontend)

`npm audit` in the frontend is the source for the current state. One trap when fixing: if a package is
pinned through an `overrides` entry in `package.json` — like `dompurify`, which arrives via
`@monaco-editor/react` → `monaco-editor` —, a plain update does not move it. Raise the entry first, then
`npm audit fix` without `--force` for everything else in range. Re-check with `npm audit`, `npm test` and
`npm run build` (including the TypeScript check).

## Known Limitations

### SQLite

- **Concurrent writes:** SQLite only allows one write operation at a time. HikariCP is configured with pool-size=1 to serialize all DB operations and avoid `SQLITE_BUSY` errors under concurrent workflow runs.
- **No native types:** Booleans as INTEGER, timestamps as TEXT, JSON as TEXT.
- **No ALTER COLUMN:** Schema changes are limited (ADD COLUMN only).
- **Type expression columns in native queries:** for a result column without a declared type
  (`json_extract(…)`, `CASE …`), sqlite-jdbc takes the type of the value in the **first** row. If that is
  NULL, the driver reports NUMERIC, and Hibernate fails on a later text with "Bad value for type
  BigDecimal" – depending on the row order, so it flickers. In `nativeQuery = true`, give every such
  column a fixed type, e.g. `COALESCE(CAST(… AS TEXT), '')`, and have the test fix the order so that the
  first row yields NULL. Model: `WorkflowRunProjectionRepository.findOriginColumns` with
  `WorkflowRunProjectionRepositoryTest`. Columns with a declared type (`w.name`) and JPQL queries are not
  affected.
- **NEVER access the DB directly, neither reading nor writing** (via `sqlite3` CLI or SQL – not from workflow shell blocks or scripts either). Always use the REST API or service/repository layer — direct access bypasses the Hibernate cache and competes for the single connection (HikariCP pool-size=1).

### Docker Dependency

- Without Docker, shell blocks with `container_image` cannot run; their agent images must be built
  beforehand (`scripts/build-agent-images.sh`; the **Containers** menu entry shows whether they are current).
- **AI CLIs (Claude, Codex, Pi) in workflow blocks always run on the host**, not in the project container, so they need no Docker. Only ad-hoc commands during a run (`claude` via `docker exec`) and the terminal use the project container.

### Security

Started from source, the instance listens on all interfaces. What follows from that — API key, Docker
socket, CORS, rate limit — is in
[deployment.md → Trust model and network exposure](deployment.md#trust-model-and-network-exposure).

### Frontend

- No offline mode (PWA shell is cached, but API data is not).
- Monaco Editor is served from the app's own bundle (no CDN, the CSP allows `'self'` only), but the PWA does not precache it — offline, it only opens if the browser has already cached the `monaco` chunk.
- The lazy chunks load on demand: `monaco` and the Monaco workers with the first editor, `TerminalPanel` when the terminal of a running run is opened, `BpmnDiagramView` once a workflow diagram is shown. `npm run build` ends with `scripts/check-lazy-chunks.mjs`: the build fails as soon as one of them appears as a `modulepreload` in `dist/index.html` or is statically imported from the entry — for instance because Rolldown put a shared module such as Vite's preload helper into that chunk (groups and priorities: `codeSplitting` in `vite.config.ts`).
