> *Last updated: [auto-generated]*

# Development

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java (JDK) | 21+ | `java --version` |
| Maven | optional (wrapper `mvnw` included) | `./mvnw --version` |
| Node.js | 20+ (`.nvmrc`) | `node --version` |
| npm | 10+ | `npm --version` |
| Docker | 24+ (optional) | `docker --version` |

Docker is only needed for container-execution workflow blocks — the backend boots even without a running Docker daemon (the Docker client is initialized lazily). For first-time setup on a new machine, see **[GETTING-STARTED.md](GETTING-STARTED.md)** (includes `scripts/setup.sh`).

## Local Setup

### Starting the Backend

```bash
cd ai-automation-backend

# Download dependencies and compile
mvn clean compile

# Start (port 9090)
mvn spring-boot:run

# Alternative: build JAR and run
mvn clean package -DskipTests
java -jar target/ai-automation-backend-0.7.3-SNAPSHOT.jar
```

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
cd ai-automation-backend && mvn spring-boot:run
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

The backend comprises **33 REST controllers** and **14 block types**:

**Controllers:** Workflow, WorkflowTemplate, Skill, Execution, Learning, Schedule, Webhook, System, Project, Conversion, Todo, TodoTrigger, ActivityLog, WorkflowMemory, Staging, Testing, Chain, ChainTemplate, WorkflowPool, WorkflowFactory, Wish, Docs, Assistant, PageSuggestion, CompletionTrigger, Notification, Dialog, DeadLetter, Integration, OnboardingProfile, Reports, Search, WorkflowDebug

**Block types:** `simple`, `shell`, `retry`, `conditional`, `foreach`, `while`, `switch`, `agentic`, `approval`, `sub-workflow`, `skill-capability`, `human-input`, `document`, `integration`

**Flyway migrations:** V1–V66 (SQLite)

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

- **SkillSeeder:** Creates 19 built-in skills on startup (idempotent) and registers them in the `SkillRegistry`
- **WorkflowTemplateSeeder:** Creates 66 built-in templates on startup (idempotent, updates on version bump)
- Template definitions as JSON under `src/main/resources/templates/builtin/`

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
