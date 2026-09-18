# Development

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
```

`mvn spring-boot:run` is **deliberately blocked**: the backend aborts at startup from `target/classes`
with a message (`RuntimeClasspathGuard`), see [Builds next to the running dev backend](#builds-next-to-the-running-dev-backend).
Escape hatch for special cases: `-Dautomation.runtime.allow-classes-dir=true`.

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

**Class-size ratchet:** `architecture/SourceSizeRatchetTest` counts the lines of every file under `src/main/java` and fails as soon as a file exceeds 300 lines without being on the test's allowlist — or a listed file grows past its recorded ceiling. The list only moves one way: whoever shrinks a listed class lowers its ceiling; once it drops to ≤300 lines the entry is removed. New files above 300 lines get split, not listed (audit TODO 1e248ab9). No external dependency, plain JUnit.

**No scheduling under the integration test profile:** `@EnableScheduling` lives in `config/SchedulingConfig` with `@Profile("!integration")`. Every `@SpringBootTest` class runs with `@ActiveProfiles("integration")` and therefore without background jobs. Reason: those jobs otherwise write into the same SQLite file while tests run — `HealthAutoTodoService`, for example, creates a TODO as soon as the stable or test instance is unreachable. Such rows survive the `@Transactional` rollback (separate thread, separate transaction) and make globally counting assertions depend on whether a container happens to be running. Scheduled methods are invoked directly in tests, so their logic stays covered. When adding a new `@Scheduled` job, test it by calling the method directly — do not wait for a tick.

**Integration encryption key pinned to a temp path in the test profile:** Since 2026-09-12, just like `integration.db.path`, `IntegrationTestDbSessionListener` also sets `integration.key.path` (a per-JVM temp file, deleted on teardown), which `application-integration.yml`'s `automation.integration.key-file` points to — without this pin, `IntegrationEncryptionService.init()` would generate a fresh AES key into the developer's real `~/.autotrigger/` on every `@SpringBootTest` with empty `app_settings` (happened 2026-08-06, shadowing the real key for months).

**Backups disabled and pinned to a temp directory in the test profile:** The same trap a third time (2026-09-13): `automation.backup` defaults to `enabled: true` and `directory: ${user.home}/.autotrigger/backups/dev`, and `application-integration.yml` did not override either — every Spring context of an integration test backed up its throwaway test DB into the real Dev backup directory on startup, rotating away real backups in the process (retain count 30). Since then the test profile sets `automation.backup.enabled: false` and points `directory` at `integration.backup.dir`, which `IntegrationTestDbSessionListener` points at a temp directory per test JVM and deletes recursively at session end. The rotation in `DatabaseBackupService` is additionally hardened: it only counts and deletes files matching its own naming scheme `mysuricates-backup-yyyy-MM-dd_HH-mm-ss.db` (`BackupFileNaming.isOwnBackupFile`); manual copies or foreign `.db` files in the same directory stay visible but are never rotated. Check: `ls ~/.autotrigger/backups/dev | wc -l` must stay the same before and after `mvn test -Dtest=ContainerServiceStaleRowIntegrationTest`.

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

## Versioning & Releases

### Where the Version Is Defined

| File | Format | Example |
|---|---|---|
| `ai-automation-backend/pom.xml` | `X.Y.Z-SNAPSHOT` | `0.7.3-SNAPSHOT` |
| `ai-automation-frontend/package.json` | `X.Y.Z` | `0.7.3` |
| `application.yml` | via `@project.version@` (Maven filtering) | automatic |

The dashboard displays the version via the `/api/v1/system/info` endpoint.

### Release Script

```bash
./scripts/release.sh           # Patch: 0.1.0 → 0.1.1 (default)
./scripts/release.sh minor     # Minor: 0.1.1 → 0.2.0
./scripts/release.sh major     # Major: 0.2.0 → 1.0.0
```

The script:
1. Reads the current version from `pom.xml` (without `-SNAPSHOT`)
2. Calculates the new version (increments the segment)
3. Updates `pom.xml` (`X.Y.Z-SNAPSHOT`) and `package.json` (`X.Y.Z`)
4. Creates a git commit: `Release vX.Y.Z`
5. Sets a git tag: `vX.Y.Z`

**Prerequisite:** Clean working tree (no uncommitted changes).

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
  - Among the shipped definitions, one built-in is affected (`saas-continuous-improve.json`); its
    following block is an AI prompt, whose behavior does not change.

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
| **Forms** | Controlled components with React state |

### Annotation Processors

The backend uses three annotation processors that must be correctly ordered in the compiler configuration (`pom.xml`):

1. **Lombok** — Generates getters, setters, builders, etc.
2. **MapStruct** — Generates mapper implementations
3. **lombok-mapstruct-binding** — Bridge between Lombok and MapStruct

## Architecture Notes for Developers

### Backend Structure

The backend comprises **43 REST controllers** and **19 block types**:

**Controllers:** ActivityLog, Assistant, Audit, Chain, ChainTemplate, CompletionTrigger, Conversion, DashboardLayout, DeadLetter, Dialog, Docs, Execution, Integration, Learning, Notification, OnboardingProfile, PageSuggestion, Project, Reports, Schedule, Search, Skill, Staging, System, SystemAccess, SystemAiProvider, SystemMaintenance, SystemModules, SystemSettings, Testing, Todo, TodoTrigger, WebSocketTicket, Webhook, Wish, Workflow, WorkflowDebug, WorkflowFactory, WorkflowImport, WorkflowMemory, WorkflowPool, WorkflowSeal, WorkflowTemplate

**Block types:** `simple`, `shell`, `retry`, `conditional`, `foreach`, `while`, `switch`, `agentic`, `approval`, `sub-workflow`, `skill-capability`, `human-input`, `document`, `integration`, `todo-create`, `todo-update`, `todo-close`, `compress`, `compress-ai`

**Flyway migrations:** V1–V73 (SQLite)

### Gate Status System

Workflow blocks can emit quality gate markers:
```
gate_status: GREEN|YELLOW|RED
gate_category: <category>
gate_description: <description>
```
- YELLOW → RunStatus `COMPLETED_WITH_ERRORS`
- RED → `AutoTodoService` creates a TODO with HIGH priority
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

`scripts/install-hooks.sh` installs the hook via `core.hooksPath` (and runs as part of `setup.sh`).

### When the gate fires

Do not reach for the bypass. `git commit --no-verify` exists, but a hit is almost always real: a
blanket `git add` picked up something that does not belong in the repository. Look at what is
staged first.

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
- Monaco Editor loads external bundles (requires internet connection on first load).
