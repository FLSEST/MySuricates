# MySuricates - AI Workflow Automation

## PFLICHT: TODO-Tracking bei JEDER Code-Änderung

WICHTIG: Dieser Abschnitt hat höchste Priorität. BEVOR du eine Datei änderst, MÜSSEN diese Schritte erfolgen.

### Checkliste (IMMER befolgen)

1. **TODO suchen/anlegen** — `GET /api/v1/todos?status=OPEN&status=IN_PROGRESS` prüfen. Falls kein passendes TODO existiert → neues anlegen (`POST /api/v1/todos`)
2. **IN_PROGRESS setzen** — `PATCH /api/v1/todos/<id>/status` mit `{"status": "IN_PROGRESS", "sessionId": "$SHELL_SESSION_ID"}` — BEVOR die erste Datei geändert wird
3. **Arbeiten** — Code ändern, testen, verifizieren
4. **DONE setzen** — `PATCH /api/v1/todos/<id>/status` mit `{"status": "DONE", "sessionId": "$SHELL_SESSION_ID"}` — NACHDEM die Arbeit abgeschlossen ist

### Wann gilt das?

ALWAYS: Features, Bugfixes, Refactorings, Config-Änderungen, Dokumentations-Updates
NEVER: Reine Informationsfragen oder triviale Antworten ohne Code-Änderung

### Beispiele

```
❌ FALSCH: User fragt nach Änderung → sofort Datei editieren → fertig
✅ RICHTIG: User fragt nach Änderung → TODO anlegen → IN_PROGRESS → Datei editieren → DONE
```

### API-Key auslesen

```bash
python3 -c "import sqlite3; conn = sqlite3.connect('ai-automation-backend/data/automation.db'); c = conn.cursor(); c.execute(\"SELECT value FROM app_settings WHERE key = 'api-key'\"); print(c.fetchone()[0]); conn.close()"
```

### TODO-Felder

```bash
curl -X POST http://localhost:9090/api/v1/todos \
  -H "Content-Type: application/json" \
  -H "X-API-Key: <key>" \
  -d '{
    "title": "Kurzer Titel im Imperativ",
    "description": "Markdown mit Kontext und Umsetzungsschritten",
    "priority": "HIGH | MEDIUM | LOW",
    "tags": ["bereich", "typ"],
    "projectId": "ebf4f50c-20c2-4c35-a84a-a0b350124523",
    "source": "AI",
    "sourceAgent": "claude-code",
    "sessionId": "$SHELL_SESSION_ID"
  }'
```

### Konventionen

- **Projekt**: `AutoPilot` (ID: `ebf4f50c-20c2-4c35-a84a-a0b350124523`) für MySuricates-eigene TODOs
- **Source**: `AI` mit `sourceAgent: "claude-code"`, `sessionId: "$SHELL_SESSION_ID"`
- **Sprache**: Deutsch für Titel
- **Priority**: `HIGH` = blockierend, `MEDIUM` = bald, `LOW` = nice-to-have
- **Tags**: `backend`, `frontend`, `architektur`, `security`, `performance`, `ux`, `ui`, `feature`, `bug`, `testing`, `documentation`, `workflow-execution`, `container-execution`, `staging`

---

AI-powered workflow automation platform with a skill system, Docker-based execution, and a React frontend.

**Workflow Building Guide**: See [docs/WORKFLOW-GUIDE.md](docs/WORKFLOW-GUIDE.md) for a practical guide on building workflows — block types, patterns, timeouts, and pitfalls.

## Vision

Two pillars: (1) universal problem solver for everyday life and work, (2) self-improving AI platform that gets smarter with every run. See [VISION.md](VISION.md) for the full vision document.

## Project Structure

```
ai-automation-backend/     Spring Boot 3.4 (Java 21) REST API
  src/main/java/com/automation/
    controller/            REST controllers (Workflow, WorkflowTemplate, Skill, Execution, Learning, Schedule, Webhook, System, Project, Conversion, Todo, ActivityLog, WorkflowMemory, Staging, Chain, Testing)
    entity/                JPA entities (Workflow, WorkflowTemplate, Skill, Project, Container, ScheduledWorkflow, WebhookTrigger, WorkflowRun, Todo, ActivityLog, WorkflowMemory, StepResult, BlockDefinition, WorkflowChain, ChainRun, ChainEntry, ChainRunEntry, TemplateVerification, Capability, GateStatus, ...)
    dto/                   Request/response DTOs
    mapper/                MapStruct mappers (Entity <-> DTO)
    repository/            Spring Data JPA repositories
    service/               Business logic
    executor/              Docker-based skill execution
    executor/blocks/       Block executors (Simple, Shell, Retry, Conditional, ForEach, While, Agentic, Approval, SubWorkflow, SkillCapability, HumanInput)
    service/ai/            Host CLI integration (HostClaudeService, HostCodexService)
    service/cli/           CLI execution and parsing (CommandExecutorService, CliSessionManager, ClaudeOutputParser, ClaudeJsonResponse, ShellCommandService)
    service/conversion/    Agentic-to-workflow conversion (WorkflowConversionService)
    service/chain/         Workflow chain CRUD and sequential execution (ChainService, ChainExecutor)
    service/memory/        Knowledge extraction and summarization (WorkflowMemoryService, ClaudeSummarizer, CodexSummarizer, KnowledgeSummarizer)
    service/staging/       Staging lifecycle, sync, and test instance (StagingService, TestingService, InstanceSyncHelper)
    service/todo/          Todo management and gate resolution (TodoService, AutoTodoService, GateStatusResolver)
    service/workflow/      Workflow execution, templates, optimization (WorkflowExecutor, ExecutionBroadcaster, WorkflowService, TemplateVerificationService)
    security/              API key auth, rate limiting, Tailscale
    config/                Spring configuration, built-in template/skill seeders
    websocket/             WebSocket for real-time updates
    event/                 Spring application events (WorkflowRunCompletedEvent)
    exception/             Custom exception hierarchy + GlobalExceptionHandler
    util/                  JPA converters for JSON/custom types
  src/main/resources/
    db/migration/          Flyway migrations (V1-V24, SQLite)
    application.yml        App config
  docker/                  Dockerfiles (agent containers: Python/Node/Go, backend, stable)

ai-automation-frontend/    React 18 SPA (TypeScript)
  src/
    api/client.ts          Shared API client (fetch-based, /api/v1 prefix)
    pages/                 Route pages (Dashboard, Projects, ProjectDetail, Workflows, WorkflowDetail, WorkflowExecution, Runs, Skills, Schedules, Webhooks, Settings, Todos, Chains, ChainExecution, ActivityLog)
    components/            UI components (ui/, layout/, dashboard/, workflow/, shared/, skill/, schedule/)
    hooks/                 TanStack Query hooks (useWorkflows, useSkills, useProjects, useSchedules, useWebhooks, useConversion, useInsights, useTemplates, useChains, useActivityLog, useLearning, useStaging, useTesting, useTodos, useWorkflowMemory, useSystem, useIsMobile, useToast, useWebSocket)
    data/                  Skill constants, static data
    utils/                 Utility functions (formatTime, extractWorkflowVariables, templateUtils, workflowIcon)
    stores/                Zustand stores (settings, sidebar, theme, toast)
    i18n/                  Translations (de.ts, en.ts)
    types/index.ts         Shared TypeScript types
    theme/                 Design tokens (colors, spacing)
    test/                  Test utilities (MSW handlers, setup)
  cypress/                 E2E tests (Cypress 13)
```

## Build & Run

```bash
# Backend
cd ai-automation-backend
mvn clean package                        # Build JAR
mvn test                                 # Run tests (unit + integration)
mvn test -Dtest="FlywayMigrationTest,RepositoryIntegrationTest,ControllerIntegrationTest"  # Integration tests only
mvn spring-boot:run                      # Start dev server (port 9090)

# Frontend
cd ai-automation-frontend
npm install                              # Install dependencies
npm run dev                              # Start dev server (port 3000, proxies /api to :9090)
npm run build                            # Production build (tsc + vite)
npm run lint                             # ESLint (flat config, eslint.config.js)

# E2E Tests
cd ai-automation-frontend
npm run test:e2e                         # Run Cypress headless
npm run cy:open                          # Open Cypress UI
```

## Key Conventions

- **Backend ORM**: Lombok for boilerplate, MapStruct for entity-DTO mapping
- **Database**: SQLite with Flyway migrations. Naming: `V{N}__description.sql`
- **NEVER write to the SQLite database directly** (via `sqlite3` CLI or raw SQL). Always use the REST API or the Spring service/repository layer. Direct DB writes bypass Hibernate's entity cache and cause inconsistent state, "Internal server error" responses, and hard-to-debug issues. This applies to testing, debugging, and seeding alike — use the API.
- **API prefix**: All endpoints under `/api/v1`
- **Frontend state**: Zustand for client state (settings, theme, sidebar), TanStack Query for server state
- **API client**: Singleton `api` instance in `src/api/client.ts` — all hooks use this
- **i18n**: German (`de.ts`) and English (`en.ts`), both must be updated together
- **Styling**: TailwindCSS utility classes, dark mode support via theme store
- **PWA**: Vite PWA plugin configured, app name "MySuricates"
- **WebSocket**: Backend sends real-time updates via `/ws`, frontend connects via `useWebSocket` hook

## Architecture Notes

- Workflows consist of ordered steps; each step runs a Skill
- Skills are categorized (see `SkillCategory` enum) and have associated Docker containers (Python/Node/Go)
- Docker containers are used for project file mounting, lifecycle management, and ad-hoc terminal access
- **All Claude CLI execution happens on the host** (not inside Docker containers) to avoid nested-session detection
  - `HostClaudeService.executeAction()` runs `claude -p` on the host with `ProcessBuilder.directory(projectPath)`
  - `CLAUDE_CODE` and `CLAUDECODE` env vars are removed from the process environment to prevent nested-session errors
  - `CommandExecutorService` provides `executeCommandOnHost()` / `executeStepOnHost()` / `injectSkillContextOnHost()` methods
- Docker containers have resource limits: 4GB memory, CPU quota, 100 PIDs, 60min idle timeout
- Security is enabled by default: API key auth via `X-API-Key` header (auto-generated if not configured), rate limiting
- Block types: simple, shell, retry, conditional, forEach, while, switch, agentic, approval, sub-workflow, skill-capability, human-input
- Agentic blocks use a Goal→Reason→Act→Observe loop powered by Host Claude CLI
- Workflow Conversion: Successful agentic runs can be converted to deterministic workflows (rule-based + AI)
- Terminal WebSocket at `/ws/terminal` for real-time container access
- **Soft-Delete Pattern**: Workflows use `@SQLRestriction("deleted_at IS NULL")` on the entity class. Projects use `archived_at` but filter via repository methods (`findByArchivedAtIsNull()`, `findByArchivedAtIsNotNull()`) instead of `@SQLRestriction` — because `@SQLRestriction` on Project would break eager-loaded `Workflow.project` associations. Archived projects retain all their workflows, runs, and data — nothing is cascade-deleted.
- **ESLint**: Flat config (`eslint.config.js`, ESLint 9+) with `typescript-eslint`, `react-hooks`, and `react-refresh` plugins. Test files (`**/*.test.{ts,tsx}`) have `no-explicit-any` disabled.

## Gate Status System

Workflow blocks can emit gate status markers in their output to signal quality gates:

```
gate_status: GREEN|YELLOW|RED
gate_category: <category>
gate_description: <description>
```

- `GateStatusOutputParser` — parses these markers from block output text (tolerant of markdown formatting)
- `WorkflowExecutor` scans **all** block outputs — highest severity wins (GREEN < YELLOW < RED by enum ordinal)
- Gate status is stored in `WorkflowRun.metadata` (`gate_status`, `gate_category`, `gate_description`)
- **YELLOW → `COMPLETED_WITH_ERRORS`**: If the highest gate is YELLOW, the run status is set to `COMPLETED_WITH_ERRORS` instead of `COMPLETED`
- **RED → `COMPLETED`**: RED gates don't change the run status — `AutoTodoService` handles them via the `WorkflowRunCompletedEvent`
- `GateStatusResolver` — derives gate status from run status when no explicit marker exists (`FAILED` → RED, `COMPLETED` → GREEN)
- `AutoTodoService` — creates follow-up TODOs based on gate status (RED → HIGH priority, YELLOW → MEDIUM priority)

## Agent Instructions System

Two-level instruction injection via Claude CLI `--append-system-prompt` / `--system-prompt` flags:

1. **Base instructions** (`src/main/resources/agent-instructions.md`) — loaded at startup by `AgentInstructionsService`, apply to all workflows
2. **Workflow-specific instructions** (`workflow.instructions` column) — per-workflow, editable in UI (Monaco editor)

Injection paths:
- **Block execution** (all block types): Instructions passed via `--append-system-prompt` flag on host Claude CLI (`HostClaudeService.executeAction()`)
- **Agentic orchestrator** (REASON phase): System prompt passed via `--system-prompt` flag to `HostClaudeService.reasonStructured()`, includes workflow instructions in `## Workflow Instructions` section

## Model Selection

- `workflow.model` field (e.g. `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5`)
- Passed via `--model` flag to Claude CLI on the host
- Agentic REASON phase uses the locally configured default model

## Per-Call Token Limit

- `HostClaudeConfig.maxTokens` (property `automation.host-claude.max-tokens`) — optional per-call output token limit
- Applied via `--max-tokens` flag in both `reasonStructured()` and `executeAction()`
- Distinct from the agentic block's `max_total_tokens` budget (which tracks cumulative input+output across all iterations)

## Per-Block Claude CLI Timeout

- All block types support an optional `timeout_seconds` field in block config
- Overrides the global `automation.host-claude.timeout-seconds` (default: 300s) for that block's Claude CLI calls
- Without `timeout_seconds` in config → global default applies
- For dual-mode blocks (shell/claude), `timeout_seconds` controls both the shell timeout and the Claude CLI timeout
- Applies to both `reasonStructured()` and `executeAction()` calls in agentic blocks

## Agentic Block Safety

| Config Key | Default | Description |
|---|---|---|
| `max_iterations` | 10 | Hard iteration cap |
| `iteration_timeout_seconds` | 300 | Per-iteration wall-clock timeout |
| `total_timeout_seconds` | 1800 | Overall block timeout (30min) |
| `max_total_tokens` | 200000 | Token budget (input+output, reason+act) |
| `max_repeated_actions` | 3 | Abort after N identical consecutive actions |
| `require_approval` | false | Approval gate before each action |

Additional safety:
- History compression (after 7 turns, older turns are summarized to manage context window)
- Token tracking warnings: `AgenticBlockExecutor` logs `WARN` when REASON or ACT phase returns no usage data, indicating the token budget is not being enforced
- All 6 safety fields are pre-populated in the frontend "Add Block" dialog defaults

## Workflow Templates

- Templates are stored in the `workflow_templates` DB table (V19 migration), not hardcoded
- 44 built-in templates are seeded on startup by `WorkflowTemplateSeeder` (idempotent, inserts missing, updates on version bump)
- Template definitions live as individual JSON files in `src/main/resources/templates/builtin/` (one file per template, loaded by `BuiltInTemplateLoader`)
- Name and description are i18n JSON maps: `{"de":"...","en":"..."}` — stored via `JsonStringMapConverter`
- Built-in templates (`builtIn=true`) cannot be deleted; only `sortOrder` and `category` can be updated
- Users can create custom templates via API or by saving an existing workflow as a template
- Workflows have an optional `templateId` back-reference to their source template
- Frontend resolves locale-aware template names via `resolveTemplateName()` in `utils/templateUtils.ts`
- CRUD API at `/api/v1/workflow-templates` (see `WorkflowTemplateController`)

## Built-in Skills

- Skills are stored in the `skills` DB table, not hardcoded in frontend or in-memory beans
- 16 built-in skills are seeded on startup by `SkillSeeder` (idempotent, seeds missing skills)
- Skill definitions live in `BuiltInSkills.java` (`config/` package)
- Built-in skills have stable IDs (e.g. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` also registers all skills in the in-memory `SkillRegistry` for capability lookups
- `SkillRegistry` no longer depends on `SkillProvider` beans — seeder handles registration
- Capability handler classes (e.g. `LintHandler`, `OCRHandler`) remain as independent `@Component` beans in `skills/handlers/`
- Users can create custom skills via the import API (`POST /api/v1/skills/import`)
- `Skill.java` entity uses `@PrePersist ensureId()` (not `@GeneratedValue`) to allow explicit IDs for built-ins

## Human-Input Block

- `HumanInputBlockExecutor` — pauses workflow execution to collect input from the user via WebSocket
- Broadcasts `human_input_required` event to `/topic/executions/{runId}/logs`, waits for response
- Config fields: `prompt`, `input_type` (text/select), `variable_name`, `options`, `default_value`, `required`, `validation_pattern`, `timeout_minutes` (default: 30), `use_default_on_timeout`
- User's answer is stored as a context variable (`variable_name`) for subsequent steps
- Reuses the `ApprovalRequest` mechanism from the approval block

## Sub-Workflow Block

- `SubWorkflowBlockExecutor` — runs another workflow as a nested block
- Circular reference detection via `__parent_workflow_ids` context variable
- Max nesting depth: 5 levels
- Polling-based completion with 30min timeout
- `@Lazy` injection on `WorkflowExecutor` to break Spring DI cycle

## Switch Block

- `SwitchBlockExecutor` — multi-way branching based on expression evaluation
- Expression supports `${varName}` and `{{ varName }}` syntax, resolved from context variables
- Cases are evaluated in order; first match wins. Pipe-separated alternatives (e.g. `"error|critical"`) supported
- `default` case executes when no other case matches
- Each case contains a list of nested blocks (shell or claude) executed sequentially
- Returns `COMPLETED` with `matchedCase: "none"` when no match and no default

## Workflow Chains

- Chains group multiple workflows for sequential execution as a single unit
- Cross-project: a chain belongs to no project and can reference workflows from any project
- `WorkflowChain` entity with `entries` (JSON list of `ChainEntry` value objects) sorted by `sortOrder`
- `ChainRun` entity tracks execution state with `entryRuns` (JSON list of `ChainRunEntry`)
- `ChainService` — CRUD with workflow ID validation
- `ChainExecutor` — async sequential execution using virtual threads, polling-based workflow completion (2s interval, 30min timeout)
- Each entry builds context: chain-level + entry-level + `previous_chain_output` from last completed workflow
- `stopOnFailure` flag at both chain and entry level — chain stops only if both are true
- Cancellation via `ConcurrentHashMap<String, AtomicBoolean>`
- WebSocket status broadcasts at `/topic/chains/{chainRunId}/status`
- CRUD + execution API at `/api/v1/chains`
- Frontend: Chains page with split-panel layout, pipeline visualization, create/edit modal with workflow search
- Frontend: ChainExecution page with live polling and navigation to individual workflow runs

## Workflow Memory

- Workflows can accumulate knowledge across runs via the memory system
- `WorkflowMemoryController` — CRUD API at `/api/v1/workflows/{workflowId}/memory`
- `WorkflowMemoryService` — extracts knowledge from completed runs, manages activation/deactivation
- `KnowledgeSummarizer` — interface for summarization backends, implemented by:
  - `ClaudeSummarizer` — uses Host Claude CLI for summarization (primary)
  - `CodexSummarizer` — uses OpenAI Codex CLI as alternative backend
- `HostCodexService` — runs OpenAI Codex CLI on the host for summarization tasks (analogous to `HostClaudeService`)
- `SummarizationConfig` — configuration for the summarization backend selection
- `WorkflowMemory` entity (V16 migration) — stores extracted knowledge entries per workflow
- Frontend hook: `useWorkflowMemory.ts`

## Template Verification

- `TemplateVerification` entity (V22 migration) — tracks quality status of workflow templates
- `TemplateVerificationService` — manages verification state per template+project pair
- Workflows created from templates can be test-run to verify the template works correctly
- Verification results visible in template CRUD API responses

## Staging (Stable + Dev + Test parallel)

Three environments can run side-by-side:
- **Dev**: `localhost:3000` → proxy → `localhost:9090` (from source, as usual)
- **Stable**: `localhost:9091` (Docker container, backend serves static frontend)
- **Test**: `localhost:8090` (Docker container, dedicated test instance for workflow testing)

### Auto-Start

Dev automatically starts the Stable container on boot (`ApplicationReadyEvent`) if:
1. A snapshot exists (`stable-frontend/` directory present, i.e. promote was run at least once)
2. The container is not already running

Controlled by `automation.staging.auto-start` (default: `true`, disabled in `application-stable.yml` to prevent self-start).

### Test Instance

- `TestingService` — manages a dedicated test Docker container on port 8090
- Auto-starts on boot if `automation.staging.test-auto-start` is true and a snapshot exists
- Auto-syncs definitions from Dev to Test on startup
- `TestingController` — REST API at `/api/v1/testing` (status, start, stop, sync)
- Uses `InstanceSyncHelper` (shared with `StagingService`) for definition sync logic
- Frontend hook: `useTesting.ts`

### Definition Sync (Dev → Stable)

`POST /api/v1/staging/sync-definitions` selectively syncs **definitions only** from Dev to Stable via Stable's REST API:

| Synced | Matching | Behavior |
|---|---|---|
| Projects | name + path | Skip if exists, create if new |
| Workflows | name + projectName | Update if exists, import if new |
| Custom Skills | name | Skip if exists, import if new |

**Not synced** (preserved on Stable): workflow_runs, step_results, scheduled_workflows, webhook_triggers, activity_log, todos, built-in skills/templates. Archived projects and soft-deleted workflows are automatically excluded via `@SQLRestriction`.

Requires Stable to be running and healthy. In the UI: Settings → Stable → "Definitionen synchronisieren" (only enabled when Stable is healthy).

### CLI Management

```bash
./scripts/staging.sh snapshot    # Build JAR + frontend + Docker image, tag git commit
./scripts/staging.sh start       # Start stable container on :9091
./scripts/staging.sh stop        # Stop stable container
./scripts/staging.sh status      # Container status + health check
./scripts/staging.sh log         # Tail container logs
./scripts/staging.sh db-seed     # Raw DB copy (emergency only, overwrites everything)
./scripts/staging.sh clean       # Remove container + volume + image
./scripts/staging.sh promote     # stop → snapshot → start (one-liner)
```

Key files:
- `ai-automation-backend/docker/Dockerfile.stable` — Ubuntu-based (glibc for Claude CLI)
- `ai-automation-backend/docker-compose.stable.yml` — Volume mounts for Claude CLI + projects
- `application-stable.yml` — Spring profile: port 9091, tray disabled, auto-start disabled, frontend serving enabled
- `StaticFrontendConfig.java` — Conditional `WebMvcConfigurer` for SPA static file serving
- `StagingService.java` — Auto-start, promote, sync-definitions, container lifecycle
- Dev tray icon shows status of both environments via health checks
- Staging status includes `stableVersion` and `stableBuildTimestamp` (fetched from Stable's `/system/info`)

## Releasing

```bash
./scripts/release.sh           # Patch: 0.1.0 → 0.1.1
./scripts/release.sh minor     # Minor: 0.1.1 → 0.2.0
./scripts/release.sh major     # Major: 0.2.0 → 1.0.0
```

The script:
1. Reads current version from `pom.xml` (strips `-SNAPSHOT`)
2. Bumps the requested segment (patch/minor/major)
3. Updates `pom.xml` (`X.Y.Z-SNAPSHOT`) and `package.json` (`X.Y.Z`)
4. Commits: `Release vX.Y.Z`
5. Tags: `vX.Y.Z`

Two kinds of Git tags:
- **`v*`** — Semver release tags (created by `release.sh`)
- **`stable-*`** — Deployment timestamps (created by `staging.sh promote`)

