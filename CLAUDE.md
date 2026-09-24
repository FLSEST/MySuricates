# MySuricates - AI Workflow Automation

> Die Arbeitsroutine des Maintainers (TODO-Tracking gegen eine laufende MySuricates-Instanz,
> Workflow-First-Rückfrage) liegt lokal und ungetrackt unter `.claude/rules/` – sie setzt eine eigene Instanz voraus.

## PFLICHT: Tests müssen nach JEDER Code-Änderung grün sein

BEVOR eine Änderung als fertig gilt oder committet wird, MÜSSEN alle relevanten Tests bestehen.

### Ablauf

1. **Vor der Änderung** — Bestehende Tests im betroffenen Bereich laufen lassen, um den Ist-Zustand zu kennen
2. **Nach der Änderung** — ALLE betroffenen Tests laufen lassen (nicht nur neue):
   - Backend: `mvn test` (oder gezielt `mvn test -Dtest="BetroffeneTests"`)
   - **Neue Klasse angelegt, Klasse zwischen Paketen verschoben oder ein Paket umbenannt?**
     Zusätzlich `mvn test -Dtest='PackageCycleRatchetTest,LayerRuleTest,SourceSizeRatchetTest'`
     laufen lassen (oder `bash scripts/tests/arch-guard.sh`) — diese drei messen über jdeps bzw.
     Dateigrößen die kompilierten Klassen und gehören in keine themenbezogene `-Dtest`-Auswahl
     hinein. Ein am 2026-09-18 auf main aufgefallener Paketzyklus wurde deshalb erst beim
     Integrations-Checkpoint sichtbar, nicht schon im Feature-Branch.
   - **Gezielter Lauf gegen eine Klasse mit `@Nested`-Tests:** `-Dtest='Klasse'` wählt in diesem
     Repo (Surefire 3.5.4) auch die `@Nested`-Inneren-Klassen aus — verifiziert per gezielter
     Fehlprobe in einer `@Nested`-Methode, die den Build zuverlässig scheitern ließ. **Trotzdem**
     als Konvention `-Dtest='Klasse*'` verwenden (auch mit Wildcard, matcht ebenfalls alle
     `@Nested`-Klassen und bleibt unabhängig von Surefire-Version/-Konfiguration verlässlich) —
     und sich **nie** an der Summenzeile der äußeren Klasse allein orientieren: Der eigene
     Report der äußeren Klasse (`target/surefire-reports/<Klasse>.txt`) meldet grundsätzlich
     `Tests run: 0`, weil die äußere Klasse selbst keine `@Test`-Methoden hat — das ist normal
     und **kein** Hinweis auf einen leeren Lauf. Maßgeblich ist die abschließende
     `Results: Tests run: N`-Zeile des gesamten Maven-Laufs bzw. dessen Exit-Code, nicht eine
     einzelne Klassenzeile oder ein abgeschnittener `tail`.
   - Frontend: `npx tsc --noEmit` + `npm run lint`
   - E2E (falls betroffen): `npm run test:e2e`
   - **Vor dem Push** prüft der `pre-push`-Hook (`scripts/ci/pre-push-checks.sh`) Commit-Nachrichten,
     Autor-Adresse (nur GitHub-noreply, nie eine private Adresse), Hygiene des Push-Bereichs, die
     Doku-Gates und baut nach Scope; `commit-msg` prüft Nachricht und Adresse schon beim Commit.
     Kein `--no-verify` per Reflex — Details: [docs/entwicklung.md](docs/entwicklung.md) → Pre-Push-Gate.
3. **Wenn Tests brechen** — Sofort fixen. NICHT committen mit kaputten Tests. Ursache untersuchen, nicht einfach Tests anpassen oder löschen.
4. **Neue Funktionalität** — Tests schreiben für neue/geänderte Logik

### Was NICHT erlaubt ist

- Tests löschen oder `@Disabled`/`.skip()` setzen, weil sie nach einer Änderung fehlschlagen
- Tests so anpassen, dass sie den Fehler maskieren statt die Ursache zu beheben
- Committen ohne die Tests im betroffenen Bereich laufen zu lassen

---

## PFLICHT: Laufende Workflows vor Backend-Neustart prüfen

BEVOR du das Backend neu startest (Prozess killen, Port 9090 neu binden), MUSST du prüfen, ob noch Workflow-Runs laufen. `scripts/start-autotrigger-dev.sh restart` macht diese Prüfung selbst und bricht bei laufenden Runs ab; für andere Wege:

```bash
curl -s --noproxy '*' "http://localhost:9090/api/v1/executions?status=RUNNING" -H "X-API-Key: <key>" | python3 -c "import sys,json; data=json.load(sys.stdin); runs=data.get('content',data) if isinstance(data,dict) else data; count=len(runs) if isinstance(runs,list) else data.get('totalElements',0); print(f'{count} laufende Runs') if count else print('Keine laufenden Runs')"
```

- **Runs laufen** → User fragen: „Es laufen noch X Workflow-Runs. Soll ich das Backend trotzdem neu starten?"
- **Keine Runs** → Neustart ohne Rückfrage erlaubt

---

AI-powered workflow automation platform with a skill system, Docker-based execution, and a React frontend.

## Build & Run

```bash
# Backend:  cd ai-automation-backend
mvn clean package          # Build JAR
mvn test                   # Run all tests
# Dev server (port 9090): NIE `mvn spring-boot:run` (blockiert durch RuntimeClasspathGuard) —
# ../scripts/start-autotrigger-dev.sh [start|restart|stop] startet aus einer JAR-Kopie

# Frontend: cd ai-automation-frontend
npm run dev                # Dev server (port 3000, proxies /api → :9090)
npm run build              # Production build (tsc + vite + Lazy-Chunk-Prüfung)
npm run lint               # ESLint
```

Vollständige Build-Anleitung inkl. E2E-Tests und Docker: [docs/entwicklung.md](docs/entwicklung.md)

## Key API Action Endpoints

These use non-obvious paths — always use these, never guess:

| Action | Method | Path |
|---|---|---|
| Run workflow | POST | `/api/v1/workflows/{id}/execute` |
| Run chain | POST | `/api/v1/chains/{id}/execute` |
| Cancel execution | POST | `/api/v1/executions/{id}/cancel` |
| Approve step | POST | `/api/v1/executions/{id}/approve` — body: `{"answer": "Yes"}` (field is `answer`, NOT `approved`); an approval accepts only its `options` (else 400), rejection = `reject_options` (default `No`/`Nein`) |
| Check approval | GET | `/api/v1/executions/{id}/approval` — returns `{"pending": true, "options": [...], "rejectOptions": [...]}` or `{"pending": false}` |
| List waiting approvals | GET | `/api/v1/executions/pending-input` — all runs waiting for an approval/human input, oldest first (UI: sidebar entry + `/approvals`) |
| Pause execution | POST | `/api/v1/executions/{id}/pause` |
| Resume execution | POST | `/api/v1/executions/{id}/resume` |
| List workflow versions | GET | `/api/v1/workflows/{id}/versions?page=0&size=20` |
| Get workflow version | GET | `/api/v1/workflows/{id}/versions/{versionId}` |
| Restore workflow version | POST | `/api/v1/workflows/{id}/versions/{versionId}/restore` |
| Analyze workflow definition | GET | `/api/v1/workflows/{id}/analyze` — deterministische Baufehler-Pruefung, meldet nur |
| Analyze all definitions | GET | `/api/v1/workflows/analyze?minSeverity=HIGH` — Bestandslauf, verdichtet |
| Export workflow as BPMN | GET | `/api/v1/workflows/{id}/export/bpmn` |
| Import workflow from BPMN | POST | `/api/v1/workflows/import/bpmn?projectId={uuid}` — body: BPMN-XML (`Content-Type: application/xml`); liest nur `mysuricates:blockConfig` (Formatfilter, **kein** Herkunftsbeleg — siehe AGENTS.md) |

See [AGENTS.md](AGENTS.md) for a complete REST API reference (workflow/chain CRUD, test instance).

## Key Conventions

- **Backend ORM**: Lombok for boilerplate, MapStruct for entity-DTO mapping
- **Database**: SQLite with Flyway migrations. Naming: `V{N}__description.sql`
- **NEVER access the SQLite database directly** (via `sqlite3` CLI or raw SQL) — not from code, not from workflow shell blocks, not from scripts. Direct DB access bypasses Hibernate's entity cache, causes lock contention (HikariCP pool-size=1), and can crash the backend. This applies to reading AND writing.
- **Workflow shell blocks must NOT call the MySuricates REST API or read the DB.** All platform data workflows need is injected as context variables by the WorkflowExecutor: `{{ previous_report }}`, `{{ open_todos }}`, `{{ workflow_run_id }}`, etc. Only the key-free probes are OK: `/actuator/health`, `/api/v1/system/health` and `/api/v1/system/jvm-memory` (heap used/max/percent, nothing else). `/actuator/metrics/*` requires the API key, so shell blocks cannot use it.
- **HikariCP pool-size = 1**: SQLite only supports one writer at a time. Using pool-size > 1 causes `SQLITE_BUSY` errors under concurrent workflow runs because multiple connections compete for SQLite's single write lock. With pool-size=1, Hikari serializes all DB operations via its internal queue — no lock contention. Individual DB ops (INSERT/UPDATE) are fast enough (~ms) that even 10 concurrent workflows don't cause Hikari connection timeouts. Do NOT increase the pool size without switching to a proper concurrent database (e.g. PostgreSQL). **This applies to the integration tests too** (`src/test/resources/application-integration.yml` → `maximum-pool-size: 1`): the tests run against a file-based SQLite DB, so they must mirror prod. A test pool > 1 has two failure modes — (1) intermittent `SQLITE_CORRUPT` ("database disk image is malformed") when multiple connections + WAL open the file during ApplicationContext startup, and (2) it **masks** real pool-size=1 deadlocks/starvation, letting tests pass green while prod would starve on the single connection. Keep the test pool at 1 unless SQLite is replaced. **Each test JVM gets its own DB file**: `IntegrationTestDbSessionListener` (registered via `META-INF/services`) sets `integration.db.path` to a per-process unique path and deletes the files after the run — pool-size=1 only serializes writers *within* one JVM, and two concurrent `mvn test` processes sharing one SQLite file corrupt it (`SQLITE_BUSY_SNAPSHOT` → `SQLITE_CORRUPT`, seen 2026-07-09 when two quality-gate workflows fired on the same schedule tick).
- **Connection Pool Watchdog**: `ConnectionPoolWatchdogService` monitors pool health every 15s via `HikariPoolMXBean` (in-memory, no DB connection needed). If the single connection is held >60s with threads waiting, it logs an ERROR to ActivityLog + broadcasts via WebSocket. HikariCP `leak-detection-threshold: 60000` additionally logs a WARNING with the stack trace of who holds the connection. The watchdog's thread dump (`service/watchdog/ThreadDumpCollector`) uses `HotSpotDiagnosticMXBean.dumpThreads`, so it includes the virtual `virtual-*` run threads that `Thread.getAllStackTraces()` omits — the holder is listed first. **Critical rule**: Never put slow operations (AI calls, HTTP requests, CLI execution) inside a `@Transactional` method — use `TransactionTemplate` for short DB bursts instead. `SlowCallInTransactionRatchetTest` fails the build for AI/CLI/shell calls inside `@Transactional` or a `TransactionTemplate` callback; unit tests check the boundary with `testsupport/FakeTransactions`.
- **OSIV disabled** (`spring.jpa.open-in-view: false`): Open Session In View is explicitly off. With pool-size=1, OSIV holds the single connection for the entire HTTP request, blocking any raw JDBC or `DataSource.getConnection()` call. All lazy associations must be loaded within `@Transactional` service methods — do NOT rely on lazy loading in controllers or mappers. If you see `LazyInitializationException`, fix it by eager-fetching or adding `@Transactional`, not by re-enabling OSIV.
- **API prefix**: All endpoints under `/api/v1`
- **Frontend state**: Zustand for client state (settings, theme, sidebar), TanStack Query for server state
- **API client**: Singleton `api` instance in `src/api/client.ts` — all hooks use this. `apiErrorMessage(err, fallback)` extracts the error message from API responses for toast display — use this in all `onError` handlers instead of generic i18n strings
- **i18n**: German (`de.ts`) and English (`en.ts`), both must be updated together. Shipped templates/pool files: `name`/`description` are `{de,en}`; with the marker `"i18n": ["de","en"]` also their steps (`blocks`, `instructions`) — resolved to one language when a workflow is created (`util/LocalizedJson`, `GET /workflow-templates?locale=`, install `locale`). Shell fields and compared values stay single-language; `LocalizedDefinitionGateTest` enforces it (docs/WORKFLOW-GUIDE.md → Zweisprachige Vorlagen). **Blueprints** (`templates/blueprints/`, `kind: BLUEPRINT`, own tab next to templates/pool) are bilingual scaffolds with runnable placeholders; outside category `AI` they must not call a model (`BlueprintGateTest`: wrappers need `execution_mode: shell`)
- **Styling**: TailwindCSS utility classes — prefer the color tokens (`bg-surface`, `text-accent`, `text-danger`, …) over fixed Tailwind palette classes so themes apply. Themes live in `src/theme/themes.ts` (contrast-checked by `themes.test.ts`); on `bg-accent` use `text-text-inverse`, not `text-white`. Role tokens besides status: `ai` (AI involved), `human` (a person decides/acted — approval, human-input, user edits), `info` (running, scheduled, informational), each with `-subtle`; the normal/deterministic case stays neutral (`text-secondary`, `border-strong`) on purpose. Accent/status/role tokens are split: `--color-<name>` is the fill (bars, dots, charts), `text-<name>` automatically reads `--color-<name>-text` (mapped via `--text-color-*` in `index.css`), `bg-accent` reads `--color-accent-emphasis` — keep writing `text-success`/`bg-success`, never pick the variant by hand. Chart series use `var(--color-chart-1..8)`, framed cards `border-card-border` (invisible in shade-separated themes), never raw hex. Palette themes are derived from the authors' palette + roles (`src/theme/palettes.ts` → `derive.ts`) and also remap Tailwind palette classes (`blue-500` …) at runtime; only the always-dark LogViewer/terminal keep fixed colours
- **PWA**: Vite PWA plugin configured, app name "MySuricates"
- **WebSocket**: Backend sends real-time updates via `/ws`, frontend connects via `useWebSocket` hook

## Architecture Overview

- **Dev backend runs from a JAR copy**, never from `target/classes`: `scripts/start-autotrigger-dev.sh restart` builds, copies to `~/.local/state/autotrigger-dev/runtime/` and starts `java -jar`. IDE builds (VS Code Java/m2e) go to `target-ide/` via the pom profile `ide-eclipse-output`. Rationale and the guard: [docs/entwicklung.md](docs/entwicklung.md) → „Builds neben dem laufenden Dev-Backend".
- **All Claude CLI execution happens on the host** (not inside Docker containers) — `HostClaudeService.executeAction()` runs `claude -p` with `ProcessBuilder.directory(projectPath)`. `CLAUDE_CODE`/`CLAUDECODE` env vars removed to prevent nested-session errors.
- **Provider routing**: AI-capable block executors (agentic and compress-ai included, not integration) resolve the provider via `context.providerFor(block)` — block > workflow > system default (`BlockAiSettings`). Setting `"provider": "CODEX"` in workflow JSON applies to every AI block without its own `provider`. `AiProviderRegistry.resolve()` falls back to the configured default provider.
- **Soft-Delete**: Workflows use `@SQLRestriction("deleted_at IS NULL")`. Projects use `archived_at` with repository filter methods (not `@SQLRestriction`, because it would break eager-loaded `Workflow.project`).
- **ESLint**: Flat config (`eslint.config.js`, ESLint 9+) with `typescript-eslint`, `react-hooks`, `react-refresh`. Test files: `no-explicit-any` disabled.
- **Block types**: simple, shell, retry, conditional, foreach, while, switch, agentic, approval, sub-workflow, skill-capability, human-input, integration, document, todo-create, todo-update, todo-close, compress, compress-ai, dashboard-report — see [docs/WORKFLOW-GUIDE.md](docs/WORKFLOW-GUIDE.md) for details

For full architecture documentation: [docs/architektur.md](docs/architektur.md)

## Optional Modules (startup configuration)

- Peripheral features are **modules** decided at startup only: `automation.modules.preset` (`core` default | `research` | `full`) + per-module override `automation.modules.<name>`, hardening flags under `automation.hardening.*`. They are switchable via REST only if the startup configuration sets `automation.modules.ui-editable: true` (default false, feature modules only; `ModuleOverrideService` → `app_settings`, applies immediately) — never add another runtime switch path, and hardening flags stay startup-only (a leaked API key must not re-enable network/credential/host features or disable hardening). Module checks must run per call (no startup snapshots) so runtime switches take effect. Catalog: `modules/ModuleCatalog`.
- **New controller ⇒** annotate `@RequiresModule(ModuleCatalog.X)` (class or handler) or add it consciously to `CORE_CONTROLLERS` in `ControllerModuleGateTest`. Background paths of a module (jobs, listeners, block executors) check `ModuleRegistry` themselves; for ratchet-pinned classes use `@EventListener(condition = ModuleConditions.X)`.
- Dev instance: `ai-automation-backend/config/application.yml` must contain `automation.modules.preset: full`, otherwise the dev backend starts with the public `core` preset. Integration tests run `full` (`application-integration.yml`); disable single modules per test via `@TestPropertySource`.
- Frontend: `useModuleEnabled(name)` (`hooks/useModules.ts`) gates queries in hooks and widgets in core pages; module pages are wrapped in `ModuleRoute` (`App.tsx`), nav items carry `module`.
- **Workflow hardening**: every write of definition fields goes through `WorkflowService` → `WorkflowMutationGateway` (post-state version row, server-side `ChangeSource`, seal check; `WorkflowWriterAllowlistTest` pins other `save` callers) and every run through `WorkflowExecutor.execute(…, TriggerSource)` → `RunAdmission` (hash/provenance, seal verification, unattended policy; refused runs leave no run row). New run trigger ⇒ pick its `TriggerSource`; new definition writer ⇒ its own `ChangeSource` through the gateway, never `workflowRepository.save`. Skills likewise: every write through `SkillMutationGateway` (`skill_versions` row, `SkillChangeSource`, refused while a sealed workflow uses the skill; `SkillWriterAllowlistTest`), and a seal also pins the content hash of every used skill (`sealed_skill_hashes`, checked by `RunAdmission`). Seal checks go through `WorkflowSealVerifier` (shared by `RunAdmission` and `GET /workflows/seal-status`); runs record their seal state at start (`workflow_runs.sealed`), and a refusal's SECURITY entry is written after the caller's transaction ends — never log inside a transaction that the refusal rolls back. Seals are signed with a key outside the database (`automation.seal.key-file`); `require-signed-seal` and `seal-immutable` are the switches for strictness, and every seal/unseal is kept in `workflow_seal_events`. A refusal logs through `ActivityLogService.logRefusal` (written after the caller's transaction, never inside it). Seals and webhook signing secrets stay in force when their switch is turned off. Details: [docs/architektur.md](docs/architektur.md) → „Workflow-Härtung".

## Execution Config

- **Agent Instructions**: Two-level injection — base instructions (`src/main/resources/agent-instructions.md`, all workflows) + per-workflow instructions (`workflow.instructions` column, editable in UI). Block execution uses `--append-system-prompt`, agentic REASON uses `--system-prompt`.
- **Model Selection**: `workflow.model` field, passed via `--model` flag. Use the CLI aliases `opus`/`sonnet`/`haiku`/`fable` (always the newest model of that family, never stale) or pin a concrete id like `claude-opus-5`; pi takes `<backend>/<model>`, codex a slug. The UI suggestions come from `GET /api/v1/system/ai-providers/{id}/models` (`ModelCatalogService`: host-discovered via `pi --list-models` / codex cache, Claude = aliases; free text always allowed). Never hard-code dated model ids in code, templates or prompts. Agentic REASON and ACT both use the workflow model (`ReasoningRequest.model`); providers fall back to their default only when no model is configured. **Per-block override**: AI-capable blocks (simple, agentic, skill-capability, nested blocks in foreach/while/conditional/retry/switch) accept config keys `provider` and `model`; precedence Block > Workflow > System default (`executor/BlockAiSettings`). A block `provider` that differs from the workflow's does not inherit the workflow model (model ids are provider-specific). Step metadata `aiRequest.provider/model` show what was actually used — also on wrapper blocks (foreach/while/conditional/switch/retry/skill-capability carry the keys of their first inner AI call; foreach adds `items`+`totalItems`, while `iterations`+`iterationCount`, retry `attempts`+`maxAttempts`). The run detail UI derives the step kind (AI / deterministic / human / unknown, `stepKind` in `frontend/src/components/workflow/stepMetadata.ts`) from exactly these keys — a wrapper without `aiRequest` counts as deterministic only when its result keys are present, so keep writing them.
- **Reasoning Effort**: `workflow.effort` field (`low`/`medium`/`high`/`xhigh`, nullable), optionally overridden per block via config key `"effort"` (precedence: block > workflow > provider default). Mapping: Claude → `--effort`, Pi → `--thinking` (explicit effort beats the maxTurns heuristic), Codex → ignored (`supportsEffort=false`). Unset ⇒ no flag emitted (byte-identical to pre-effort behavior). Visible in step metadata `aiRequest.effort`.
- **Per-Call Token Limit**: `HostClaudeConfig.maxTokens` (property `automation.host-claude.max-tokens`) — optional output token limit via `--max-tokens` flag. Distinct from agentic block's `max_total_tokens` budget (cumulative input+output).
- **Per-Block Timeout**: Optional `timeout_seconds` in block config overrides global `automation.host-claude.timeout-seconds` (default: 300s). Applies to both shell and Claude CLI calls.

## CLI Concurrency & Chat Priority

- **Two-tier semaphore**: `callSemaphore` (default: 5, total concurrent CLI processes) + `backgroundSemaphore` (default: 4, non-interactive calls). Chat calls only acquire `callSemaphore` → at least 1 slot reserved for interactive use.
- `HostExecutionRequest.priority(true)` marks a call as interactive (used by the assistant chat, `AssistantChatCaller`)
- Configurable at runtime via Settings UI or `ExecutionSettingsService`: `claude-max-concurrent-calls` (1–20), `claude-reserved-for-interactive` (0–10)

## Integration / Plugin System

Plugin-Framework für externe Dienste. Jeder Provider ist ein Spring `@Component` das `IntegrationProvider` implementiert — auto-discovered via `IntegrationRegistry`.

**Neuen Provider hinzufügen:**
1. Java-Klasse in `service/integration/providers/<name>/` — `IntegrationProvider` implementieren, `@Component`
2. OAuth-Config, Actions, `executeAction()` implementieren
3. Setup Guide in `frontend/src/data/integrationSetupGuides.ts`
4. Optional: Kalender-Adapter in `frontend/src/hooks/calendar-adapters/`

Vollständige Referenz (Provider-Tabelle, Block-Config, Kalender-Adapter, Credentials): [docs/integrations.md](docs/integrations.md)

## Staging (Stable + Dev + Test)

Drei Umgebungen parallel:
- **Dev**: `localhost:3000` → proxy → `localhost:9090` (from source)
- **Stable**: `localhost:9091` (Docker container, backend + embedded frontend)
- **Test**: `localhost:8090` (Docker container, dedicated test instance)

```bash
./scripts/staging.sh promote      # Stable: stop → snapshot → start (Einzeiler)
./scripts/staging.sh start|stop|status|log|snapshot|db-seed|clean
./scripts/staging.sh test-promote # Test:  stop → test-snapshot → test-start (Einzeiler)
./scripts/staging.sh test-start|test-stop|test-status|test-log|test-reset|test-clean
```

**Wichtig:** `test-start` baut das Test-Image NICHT neu — es startet nur das vorhandene.
Nach Code-Änderungen immer `test-promote` (oder `test-snapshot`) verwenden, sonst läuft
der Test-Container mit einem veralteten Backend-JAR (aktuelles Frontend + altes Backend
→ neue Endpoints liefern 404 → UI hängt auf „loading").

**KI-Anbieter und Docker-Socket in Stable/Test** kommen aus `scripts/lib/host-mounts.sh` — derselben Stelle wie bei
`instance.sh` und dem Dev-Container, auch beim Start aus der Oberfläche (`StagingComposeCommand`). Schalter in
`~/.config/mysuricates/staging.env`: `STABLE_`/`TEST_` + `AI_FROM_HOST` (`login` = Programme + je Anbieter nur die
Anmeldung, Standard; Claude per Token aus `~/.config/mysuricates/ai.env`), `AI_PROVIDERS`, `DOCKER_SOCKET` (Standard 1).
Nie wieder ganze Provider-Verzeichnisse fest in eine Compose-Datei schreiben. Details: [docs/deployment.md](docs/deployment.md) → „KI-Anbieter im Container“.

Vollständige Dokumentation (Auto-Start, Definition-Sync, Key-Files): [docs/deployment.md](docs/deployment.md)

## Detailed Documentation

| Topic | Document |
|---|---|
| System-Architektur, Block-Typen, Gate Status, TodoTrigger, Chains, Memory, Skills, Templates | [docs/architektur.md](docs/architektur.md) |
| Build, Test, Conventions, Releasing | [docs/entwicklung.md](docs/entwicklung.md) |
| Workflow-Bau: Block-Config, Patterns, Timeouts, Pitfalls | [docs/WORKFLOW-GUIDE.md](docs/WORKFLOW-GUIDE.md) |
| Integration/Plugin-System: Provider, OAuth, Kalender-Adapter | [docs/integrations.md](docs/integrations.md) |
| Deployment, Docker, Staging, Test-Instanz | [docs/deployment.md](docs/deployment.md) |
| REST API Reference für externe Agents | [AGENTS.md](AGENTS.md) |
| Vision und Roadmap | [VISION.md](VISION.md) |
