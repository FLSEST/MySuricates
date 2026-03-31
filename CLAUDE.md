# MySuricates - AI Workflow Automation

## PFLICHT: TODO-Tracking bei JEDER Code-Änderung

WICHTIG: Dieser Abschnitt hat höchste Priorität. BEVOR du eine Datei änderst, MÜSSEN diese Schritte erfolgen.

### Checkliste (IMMER befolgen)

1. **TODO suchen/anlegen** — `GET /api/v1/todos?status=OPEN&status=IN_PROGRESS` prüfen. Falls kein passendes TODO existiert → neues anlegen (`POST /api/v1/todos`)
2. **IN_PROGRESS setzen** — `PATCH /api/v1/todos/<id>/status` mit `{"status": "IN_PROGRESS", "sessionId": "$SHELL_SESSION_ID"}` — BEVOR die erste Datei geändert wird
3. **Arbeiten** — Code ändern, testen, verifizieren
4. **DONE setzen** — `PATCH /api/v1/todos/<id>/status` mit `{"status": "DONE", "sessionId": "$SHELL_SESSION_ID"}` — NACHDEM die Arbeit abgeschlossen ist
5. **Dokumentation aktualisieren** — Falls nötig: CLAUDE.md, MEMORY.md oder andere Docs anpassen
6. **Committen** — Änderungen mit `git commit` festschreiben (aussagekräftige Commit-Message, kein Push ohne explizite Aufforderung)

### Wann gilt das?

ALWAYS: Features, Bugfixes, Refactorings, Config-Änderungen, Dokumentations-Updates
NEVER: Reine Informationsfragen oder triviale Antworten ohne Code-Änderung

### Beispiele

```
❌ FALSCH: User fragt nach Änderung → sofort Datei editieren → fertig
✅ RICHTIG: User fragt nach Änderung → TODO anlegen → IN_PROGRESS → Datei editieren → DONE → Docs aktualisieren → committen
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

### Claude-Code-Session-ID in TODO-Description speichern

Das `sessionId`-Feld im TODO enthält die Shell-Session-ID (`$SHELL_SESSION_ID`) — **nicht** die Claude-Code-Konversations-ID. Damit eine Session später via `claude --resume` wiederhergestellt werden kann, muss die Claude-Code-Session-ID in der **Description** gespeichert werden.

**Session-ID auslesen:**
```bash
ls -t ~/.claude/projects/-home-flo-Entwicklung-tools-autotrigger/*.jsonl | head -1 | xargs basename -s .jsonl
```

**Format in der Description:**
```markdown
## Claude Code Session
`claude --resume <uuid>`
```

Immer beim Anlegen eines TODOs (das eine längere Arbeitssequenz beschreibt) in die Description aufnehmen.

---

## PFLICHT: Tests müssen nach JEDER Code-Änderung grün sein

BEVOR eine Änderung als fertig gilt oder committet wird, MÜSSEN alle relevanten Tests bestehen.

### Ablauf

1. **Vor der Änderung** — Bestehende Tests im betroffenen Bereich laufen lassen, um den Ist-Zustand zu kennen
2. **Nach der Änderung** — ALLE betroffenen Tests laufen lassen (nicht nur neue):
   - Backend: `mvn test` (oder gezielt `mvn test -Dtest="BetroffeneTests"`)
   - Frontend: `npx tsc --noEmit` + `npm run lint`
   - E2E (falls betroffen): `npm run test:e2e`
3. **Wenn Tests brechen** — Sofort fixen. NICHT committen mit kaputten Tests. Ursache untersuchen, nicht einfach Tests anpassen oder löschen.
4. **Neue Funktionalität** — Tests schreiben für neue/geänderte Logik

### Was NICHT erlaubt ist

- Tests löschen oder `@Disabled`/`.skip()` setzen, weil sie nach einer Änderung fehlschlagen
- Tests so anpassen, dass sie den Fehler maskieren statt die Ursache zu beheben
- Committen ohne die Tests im betroffenen Bereich laufen zu lassen

---

## PFLICHT: Laufende Workflows vor Backend-Neustart prüfen

BEVOR du das Backend neu startest (z. B. `mvn spring-boot:run`, Prozess killen, oder Port 9090 neu binden), MUSST du prüfen, ob noch Workflow-Runs laufen:

```bash
curl -s http://localhost:9090/api/v1/executions?status=RUNNING -H "X-API-Key: <key>" | python3 -c "import sys,json; data=json.load(sys.stdin); runs=data.get('content',data) if isinstance(data,dict) else data; count=len(runs) if isinstance(runs,list) else data.get('totalElements',0); print(f'{count} laufende Runs') if count else print('Keine laufenden Runs')"
```

- **Runs laufen** → User fragen: „Es laufen noch X Workflow-Runs. Soll ich das Backend trotzdem neu starten?"
- **Keine Runs** → Neustart ohne Rückfrage erlaubt

---

## PFLICHT: Workflow-First vor Code-Änderungen

WICHTIG: Dieser Abschnitt hat hohe Priorität. Bevor du Code an der Plattform änderst, MUSS geprüft werden, ob das Problem durch einen Workflow lösbar ist.

### Leitfrage (immer stellen)

> „Ist das eine Aufgabe für einen **Workflow** — oder muss wirklich **Plattform-Code** geändert werden?"

MySuricates ist eine selbstverbessernde Plattform. Die bevorzugte Lösung für Automatisierungs-Aufgaben ist **immer ein Workflow**, kein Code-Eingriff.

### Entscheidungsmatrix

| Aufgabe | Workflow-Lösung? | Code-Änderung nötig? |
|---|---|---|
| Daten verarbeiten, transformieren, analysieren | ✅ Workflow | Nein |
| Externe API aufrufen, integrieren | ✅ Workflow | Nein |
| Dateien lesen, schreiben, umbenennen | ✅ Shell-Block | Nein |
| Build-, Deploy-, Release-Prozesse | ✅ Workflow | Nein |
| Berichte, Zusammenfassungen generieren | ✅ Simple/Agentic-Block | Nein |
| Monitoring, Alerting, Scheduled Tasks | ✅ Schedule + Workflow | Nein |
| Batch-Operationen über Listen | ✅ ForEach-Block | Nein |
| **Neuer Block-Typ fehlt** | ❌ | Ja — neues Feature |
| **Plattform-Bug** (falsche Ausführung, Fehler) | ❌ | Ja — Bugfix |
| **UI-Komponente fehlt / ist falsch** | ❌ | Ja — Frontend-Änderung |
| **Security-Fix** | ❌ | Ja — immer Code |

### Pflicht-Rückfrage vor Code-Änderungen

Wenn ein Problem durch einen Workflow lösbar wäre, DARF NICHT sofort Code geändert werden. Stattdessen:

1. **Stopp** — Keine Code-Änderung vornehmen
2. **Rückfrage stellen**: „Dieses Problem könnte als Workflow gelöst werden (z. B. mit [Block-Typ/Pattern]). Soll ich stattdessen einen Workflow erstellen — oder soll die Plattform selbst geändert werden?"
3. **Explizite Bestätigung abwarten**, bevor Code geändert wird

### Beispiele

```
❌ FALSCH: User möchte Logs analysieren → sofort LogAnalysis-Feature im Backend bauen
✅ RICHTIG: User möchte Logs analysieren → Workflow mit Shell-Block (log lesen) + Simple-Block (Claude analysiert) vorschlagen

❌ FALSCH: User möchte tägliche Reports → ScheduledReportService.java schreiben
✅ RICHTIG: User möchte tägliche Reports → Workflow + Schedule-Trigger vorschlagen

❌ FALSCH: User möchte Git-Webhook verarbeiten → WebhookHandler-Code schreiben
✅ RICHTIG: User möchte Git-Webhook verarbeiten → Webhook-Trigger + Workflow konfigurieren
```

### Ausnahmen (Code-Änderung ist richtig)

- Neuer Block-Typ oder Executor wird gebraucht (kein existierender Typ reicht)
- Plattform-Bug verhindert korrekte Workflow-Ausführung
- Sicherheitslücke muss geschlossen werden
- UI-Darstellung / UX-Problem in der App
- Performance-Problem in der Plattform selbst
- Der User hat **explizit** eine Code-Änderung angefordert

### Verweis

Verfügbare Mechanismen: [docs/WORKFLOW-GUIDE.md](docs/WORKFLOW-GUIDE.md) — Block-Typen, Patterns, Timeouts, Agentic-Blöcke

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

## Key API Action Endpoints

These use non-obvious paths — always use these, never guess:

| Action | Method | Path |
|---|---|---|
| Run workflow | POST | `/api/v1/workflows/{id}/execute` |
| Run chain | POST | `/api/v1/chains/{id}/execute` |
| Cancel execution | POST | `/api/v1/executions/{id}/cancel` |
| Approve step | POST | `/api/v1/executions/{id}/approve` — body: `{"answer": "Yes"}` (field is `answer`, NOT `approved`) |
| Check approval | GET | `/api/v1/executions/{id}/approval` — returns `{"pending": true, "options": [...]}` or `{"pending": false}` |
| Pause execution | POST | `/api/v1/executions/{id}/pause` |
| Resume execution | POST | `/api/v1/executions/{id}/resume` |
| List workflow versions | GET | `/api/v1/workflows/{id}/versions?page=0&size=20` |
| Get workflow version | GET | `/api/v1/workflows/{id}/versions/{versionId}` |
| Restore workflow version | POST | `/api/v1/workflows/{id}/versions/{versionId}/restore` |

## Codex / External Agents

See [AGENTS.md](AGENTS.md) for a complete REST API reference for creating workflows, chains, and running them on the test instance. Intended for OpenAI Codex CLI and other external agents.

## Key Conventions

- **Backend ORM**: Lombok for boilerplate, MapStruct for entity-DTO mapping
- **Database**: SQLite with Flyway migrations. Naming: `V{N}__description.sql`
- **NEVER access the SQLite database directly** (via `sqlite3` CLI or raw SQL) — not from code, not from workflow shell blocks, not from scripts. Direct DB access bypasses Hibernate's entity cache, causes lock contention (HikariCP pool-size=1), and can crash the backend. This applies to reading AND writing.
- **Workflow shell blocks must NOT call the MySuricates REST API or read the DB.** All platform data workflows need is injected as context variables by the WorkflowExecutor: `{{ previous_report }}`, `{{ open_todos }}`, `{{ workflow_run_id }}`, etc. Actuator endpoints (`/actuator/health`, `/actuator/metrics/*`) are OK (no auth required).
- **HikariCP pool-size = 1**: SQLite only supports one writer at a time. Using pool-size > 1 causes `SQLITE_BUSY` errors under concurrent workflow runs because multiple connections compete for SQLite's single write lock. With pool-size=1, Hikari serializes all DB operations via its internal queue — no lock contention. Individual DB ops (INSERT/UPDATE) are fast enough (~ms) that even 10 concurrent workflows don't cause Hikari connection timeouts. Do NOT increase the pool size without switching to a proper concurrent database (e.g. PostgreSQL).
- **Connection Pool Watchdog**: `ConnectionPoolWatchdogService` monitors pool health every 15s via `HikariPoolMXBean` (in-memory, no DB connection needed). If the single connection is held >60s with threads waiting, it logs an ERROR to ActivityLog + broadcasts via WebSocket. HikariCP `leak-detection-threshold: 60000` additionally logs a WARNING with the stack trace of who holds the connection. **Critical rule**: Never put slow operations (AI calls, HTTP requests, CLI execution) inside a `@Transactional` method — use `TransactionTemplate` for short DB bursts instead.
- **OSIV disabled** (`spring.jpa.open-in-view: false`): Open Session In View is explicitly off. With pool-size=1, OSIV holds the single connection for the entire HTTP request, blocking any raw JDBC or `DataSource.getConnection()` call. All lazy associations must be loaded within `@Transactional` service methods — do NOT rely on lazy loading in controllers or mappers. If you see `LazyInitializationException`, fix it by eager-fetching or adding `@Transactional`, not by re-enabling OSIV.
- **API prefix**: All endpoints under `/api/v1`
- **Frontend state**: Zustand for client state (settings, theme, sidebar), TanStack Query for server state
- **API client**: Singleton `api` instance in `src/api/client.ts` — all hooks use this. `apiErrorMessage(err, fallback)` extracts the error message from API responses for toast display — use this in all `onError` handlers instead of generic i18n strings
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
  - `CommandExecutorService` provides `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `writeSkillContextToProject()` methods — all overloads use the `HostExecutionRequest` builder API. Skill context is written to `.claude/rules/skills-context.md` (file-based, not CLI-process-based) so all subsequent Claude CLI calls pick it up automatically.
- Docker containers have resource limits: 4GB memory, CPU quota, 100 PIDs, 60min idle timeout
- Security is enabled by default: API key auth via `X-API-Key` header (auto-generated if not configured), rate limiting
- Block types: simple, shell, retry, conditional, foreach, while, switch, agentic, approval, sub-workflow, skill-capability, human-input
- **Block-type icons**: `BLOCK_TYPE_ICONS` map in `WorkflowExecution.tsx` assigns a Lucide icon per block type (Terminal, Sparkles, Brain, etc.) — update when adding new block types
- **Provider routing**: All block executors (simple, retry, conditional, foreach, while, switch, skill-capability) pass `context.getProvider()` as `providerId` via `HostExecutionRequest.builder()` to `CommandExecutorService`. Setting `"provider": "CODEX"` in a workflow JSON therefore applies to every non-shell block type. `AiProviderRegistry.resolve()` falls back to the configured default provider (not hardcoded CLAUDE) when an unknown provider ID is given.
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
- **Gate status does NOT affect run status**: A workflow that runs to completion is always `COMPLETED`, regardless of gate color. The gate badge (GREEN/YELLOW/RED) shows the outcome severity separately. `FAILED` is reserved for actual execution failures (timeouts, crashes). `COMPLETED_WITH_ERRORS` is legacy (no longer set, but old runs retain it).
- `GateStatusResolver` — derives gate status from run status when no explicit marker exists (`FAILED` → RED, `COMPLETED` → GREEN)
- `AutoTodoService` — creates follow-up TODOs based on gate status, configurable via `workflow.metadata.autoTodo`
  - **Modes**: `auto` (default: GREEN closes TODOs, YELLOW→MEDIUM, RED→HIGH), `approval` (queues for confirmation), `track` (always creates/updates: GREEN→LOW, YELLOW→MEDIUM, RED→HIGH, never closes)
  - **TODO description** includes the most detailed step output (second-to-last non-shell block, max 4000 chars)
  - **Granular TODOs**: Blocks can emit `todo_item: <title> | HIGH/MEDIUM/LOW | <tags>` markers → `TodoOutputParser` parses them → `AutoTodoService` creates individual TODOs per finding (with dedup and auto-close for resolved items)
  - Enable per workflow: `metadata: { autoTodo: { enabled: true, mode: "track" } }`

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

## CLI Concurrency & Chat Priority

- `HostClaudeService` uses a **two-tier semaphore** system:
  - `callSemaphore` (default: 5) — total concurrent CLI processes
  - `backgroundSemaphore` (default: 4) — limit for non-interactive calls (workflows, suggestions)
  - **Chat (priority) calls** only acquire `callSemaphore` → at least 1 slot always reserved
  - **Background calls** acquire both → can never starve interactive chat
- `HostExecutionRequest.priority(true)` marks a call as interactive (used by `AssistantService`)
- `PageSuggestionService.cancelRunning()` interrupts running suggestion refresh when chat starts
- Configurable at runtime via Settings UI (Execution tab) or `ExecutionSettingsService`:
  - `claude-max-concurrent-calls` (1–20, default: 5)
  - `claude-reserved-for-interactive` (0–10, default: 1)
- Suggestions model independently configurable via `suggestions-model` app setting

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
- **Skill activation levels**: Skills can be activated at two levels:
  - **Workflow-level** (`Workflow.activatedSkills` ManyToMany): applies to all blocks in the workflow
  - **Block-level** (`BlockDefinition.skillIds` list): additional skills for a specific block only (additive to workflow-level skills)
- Skill context is written to `.claude/rules/skills-context.md` before execution; for blocks with per-block skills, the file is temporarily extended and restored to the baseline after the block completes
- `WorkflowExecutor` pre-resolves all per-block skill IDs in a single `SkillRepository.findAllById()` call before the block loop

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

