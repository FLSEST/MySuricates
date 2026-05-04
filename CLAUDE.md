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

## Build & Run

```bash
# Backend:  cd ai-automation-backend
mvn clean package          # Build JAR
mvn test                   # Run all tests
mvn spring-boot:run        # Dev server (port 9090)

# Frontend: cd ai-automation-frontend
npm run dev                # Dev server (port 3000, proxies /api → :9090)
npm run build              # Production build (tsc + vite)
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
| Approve step | POST | `/api/v1/executions/{id}/approve` — body: `{"answer": "Yes"}` (field is `answer`, NOT `approved`) |
| Check approval | GET | `/api/v1/executions/{id}/approval` — returns `{"pending": true, "options": [...]}` or `{"pending": false}` |
| Pause execution | POST | `/api/v1/executions/{id}/pause` |
| Resume execution | POST | `/api/v1/executions/{id}/resume` |
| List workflow versions | GET | `/api/v1/workflows/{id}/versions?page=0&size=20` |
| Get workflow version | GET | `/api/v1/workflows/{id}/versions/{versionId}` |
| Restore workflow version | POST | `/api/v1/workflows/{id}/versions/{versionId}/restore` |

See [AGENTS.md](AGENTS.md) for a complete REST API reference (workflow/chain CRUD, test instance).

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

## Architecture Overview

- **All Claude CLI execution happens on the host** (not inside Docker containers) — `HostClaudeService.executeAction()` runs `claude -p` with `ProcessBuilder.directory(projectPath)`. `CLAUDE_CODE`/`CLAUDECODE` env vars removed to prevent nested-session errors.
- **Provider routing**: All block executors pass `context.getProvider()` as `providerId` via `HostExecutionRequest.builder()`. Setting `"provider": "CODEX"` in workflow JSON applies to every non-shell block. `AiProviderRegistry.resolve()` falls back to the configured default provider.
- **Soft-Delete**: Workflows use `@SQLRestriction("deleted_at IS NULL")`. Projects use `archived_at` with repository filter methods (not `@SQLRestriction`, because it would break eager-loaded `Workflow.project`).
- **ESLint**: Flat config (`eslint.config.js`, ESLint 9+) with `typescript-eslint`, `react-hooks`, `react-refresh`. Test files: `no-explicit-any` disabled.
- **Block types**: simple, shell, retry, conditional, foreach, while, switch, agentic, approval, sub-workflow, skill-capability, human-input, integration, document — see [docs/WORKFLOW-GUIDE.md](docs/WORKFLOW-GUIDE.md) for details

For full architecture documentation: [docs/architektur.md](docs/architektur.md)

## Execution Config

- **Agent Instructions**: Two-level injection — base instructions (`src/main/resources/agent-instructions.md`, all workflows) + per-workflow instructions (`workflow.instructions` column, editable in UI). Block execution uses `--append-system-prompt`, agentic REASON uses `--system-prompt`.
- **Model Selection**: `workflow.model` field (e.g. `claude-opus-4-6`, `claude-sonnet-4-6`), passed via `--model` flag. Agentic REASON phase uses the locally configured default model.
- **Per-Call Token Limit**: `HostClaudeConfig.maxTokens` (property `automation.host-claude.max-tokens`) — optional output token limit via `--max-tokens` flag. Distinct from agentic block's `max_total_tokens` budget (cumulative input+output).
- **Per-Block Timeout**: Optional `timeout_seconds` in block config overrides global `automation.host-claude.timeout-seconds` (default: 300s). Applies to both shell and Claude CLI calls.

## CLI Concurrency & Chat Priority

- **Two-tier semaphore**: `callSemaphore` (default: 5, total concurrent CLI processes) + `backgroundSemaphore` (default: 4, non-interactive calls). Chat calls only acquire `callSemaphore` → at least 1 slot reserved for interactive use.
- `HostExecutionRequest.priority(true)` marks a call as interactive (used by `AssistantService`)
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
./scripts/staging.sh promote     # stop → snapshot → start (Einzeiler)
./scripts/staging.sh start|stop|status|log|snapshot|db-seed|clean
```

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
