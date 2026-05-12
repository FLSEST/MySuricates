# AGENTS.md — Codex Workflow-Referenz

Anleitung für OpenAI Codex CLI, um eigenständig Workflows und Chains über die REST API anzulegen und auf der Test-Instanz zu validieren.

## Voraussetzungen

- **Dev-Instanz** läuft auf `localhost:9090`
- **Test-Instanz** läuft auf `localhost:8090` (via `POST /api/v1/testing/start`)
- **API-Keys** für Dev und Test sind separate Datenbanken

### API-Key auslesen (Dev)

```bash
DEV_KEY=$(python3 -c "import sqlite3; conn = sqlite3.connect('ai-automation-backend/data/automation.db'); c = conn.cursor(); c.execute(\"SELECT value FROM app_settings WHERE key = 'api-key'\"); print(c.fetchone()[0]); conn.close()")
```

## TODO-First Arbeitsweise

- Bei Aufgaben mit Code-, UI-, Config- oder Commit-Bezug zuerst ein MySuricates-TODO ueber die REST API anlegen oder ein vorhandenes TODO als Arbeitsanker referenzieren.
- Danach die Umsetzung explizit an dieser TODO-ID ausrichten und den Status mindestens auf `IN_PROGRESS` setzen.
- Vor einer Fertigmeldung oder vor einem Commit immer die zugehoerige TODO-ID nennen.
- Kein "fertig", "umgesetzt" oder "committed" behaupten, wenn noch kein TODO existiert oder die Arbeit nicht diesem TODO zugeordnet ist.
- Commits werden anschliessend gemeinsam und bewusst gemacht, nicht stillschweigend.

### TODO anlegen

```bash
curl -s -X POST http://localhost:9090/api/v1/todos \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d '{
    "title": "Kurzbeschreibung der Arbeit",
    "description": "Worum es konkret geht",
    "status": "IN_PROGRESS",
    "priority": "HIGH",
    "projectId": "<project-uuid>",
    "source": "AI",
    "sourceAgent": "Codex CLI"
  }'
```

## Cypress E2E gegen Dev oder Test

Die Cypress-Tests koennen jetzt gegen **Dev** (`9090`) oder **Test** (`8090`) laufen. Wichtig ist, dass **Frontend-Proxy und Cypress immer dasselbe Ziel verwenden**.

### Zielwahl

- Standard: kein `CYPRESS_TARGET` gesetzt -> **Dev**
- `CYPRESS_TARGET=test` -> **Test-Instanz**

### Dev-Target

```bash
cd ai-automation-frontend

# Frontend proxied /api und /ws nach localhost:9090
npm run dev

# Cypress-Helper sprechen ebenfalls localhost:9090/api/v1
npm run test:e2e
```

Der Dev-API-Key wird in `cypress.config.ts` automatisch aus `ai-automation-backend/data/automation.db` gelesen, falls vorhanden.

### Test-Target

```bash
# Test-Instanz mit bekanntem API-Key starten
cd ai-automation-backend
AUTOMATION_API_KEY=test-e2e-key docker compose -f docker-compose.test.yml up -d

# Optional: Definitionen von Dev nach Test synchronisieren
curl -s -X POST http://localhost:9090/api/v1/testing/sync-definitions \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d '{}'

# Frontend und Cypress beide auf Test schalten
cd ../ai-automation-frontend
CYPRESS_TARGET=test CYPRESS_TEST_API_KEY=test-e2e-key npm run dev:e2e:test
CYPRESS_TARGET=test CYPRESS_TEST_API_KEY=test-e2e-key npm run test:e2e:test
```

Hinweise:
- `CYPRESS_TEST_API_KEY` ist fuer die Test-Instanz gedacht; `CYPRESS_API_KEY` ueberschreibt beide Ziele global.
- Falls `AUTOMATION_API_KEY` beim Test-Container gesetzt wurde, kann derselbe Wert fuer `CYPRESS_TEST_API_KEY` verwendet werden.
- Direkte Cypress-Setup-Calls (`cypress/support/api.ts`) und UI-Traffic nutzen dann beide `localhost:8090`.
- Damit bleiben fachliche Testdaten auf der Test-Instanz statt in Dev.

## API-Endpunkte

### 1. Workflow erstellen

```
POST http://localhost:9090/api/v1/workflows
Headers: Content-Type: application/json, X-API-Key: <dev-key>
Response: 201 Created
```

**Body:**
```json
{
  "projectId": "<project-uuid>",
  "name": "Mein Workflow",
  "description": "Beschreibung",
  "blocks": [
    {
      "type": "shell",
      "config": {
        "description": "Dateien auflisten",
        "command": "ls -la {{ base_dir }}"
      }
    },
    {
      "type": "simple",
      "config": {
        "description": "Ergebnis analysieren",
        "prompt": "Analysiere die Dateiliste: {{ previous_output }}"
      }
    }
  ],
  "defaultContext": {
    "base_dir": "/your/project/path"
  },
  "model": "claude-sonnet-4-6",
  "instructions": "Optionale workflow-spezifische Instruktionen"
}
```

### Block-Typen und Config

| Typ | Config-Felder | Beschreibung |
|-----|--------------|--------------|
| `simple` | `description`, `prompt` | Claude-Aufruf |
| `shell` | `description`, `command`, `on_failure?` | Shell-Befehl auf Host |
| `agentic` | `description`, `goal`, `max_iterations`, `max_total_tokens`, `iteration_timeout_seconds`, `total_timeout_seconds`, `max_repeated_actions`, `require_approval` | Goal→Reason→Act→Observe Loop |
| `conditional` | `description`, `condition`, `then_blocks`, `else_blocks` | Verzweigung |
| `foreach` | `description`, `collection`, `item_variable`, `blocks` | Iteration (Achtung: lowercase!) |
| `while` | `description`, `condition`, `blocks`, `max_iterations` | Schleife |
| `retry` | `description`, `blocks`, `max_retries`, `delay_seconds` | Wiederholung bei Fehler |
| `switch` | `description`, `expression`, `cases`, `default` | Multi-Way-Branching |
| `approval` | `description`, `prompt`, `options` | Warten auf Genehmigung |
| `sub-workflow` | `description`, `workflow_id` | Verschachtelter Workflow (max 5 Ebenen) |
| `skill-capability` | `description`, `capability`, `input` | Skill-Ausführung |
| `human-input` | `description`, `prompt`, `input_type`, `variable_name`, `options`, `timeout_minutes` | User-Eingabe |
| `integration` | `description`, `provider`, `action`, `params` | Integration-Provider-Aktion (Google Calendar, Gmail, Jira, Confluence, Trello) |

### 2. Chain erstellen

```
POST http://localhost:9090/api/v1/chains
Headers: Content-Type: application/json, X-API-Key: <dev-key>
```

**Body:**
```json
{
  "name": "Meine Chain",
  "description": "Sequenzielle Ausführung mehrerer Workflows",
  "stopOnFailure": true,
  "entries": [
    {
      "workflowId": "<workflow-1-uuid>",
      "label": "Schritt 1: Vorbereitung",
      "sortOrder": 0,
      "stopOnFailure": true,
      "inputContext": { "mode": "prepare" }
    },
    {
      "workflowId": "<workflow-2-uuid>",
      "label": "Schritt 2: Ausführung",
      "sortOrder": 1,
      "stopOnFailure": true,
      "inputContext": {}
    }
  ]
}
```

**Hinweise:**
- Chains sind cross-project — Workflows aus verschiedenen Projekten möglich
- `previous_chain_output` wird automatisch vom letzten Workflow propagiert
- `stopOnFailure` auf Chain- UND Entry-Level müssen beide `true` sein, damit die Chain bei Fehler stoppt

### 3. Workflow ausführen

```
POST http://localhost:9090/api/v1/workflows/{id}/execute
Headers: Content-Type: application/json, X-API-Key: <key>
Response: 201 Created
```

**Body:**
```json
{
  "context": {
    "variable1": "wert1"
  }
}
```

**WICHTIG:** Immer `context` verwenden, nicht `variables`!

### 4. Chain ausführen

```
POST http://localhost:9090/api/v1/chains/{id}/execute
Headers: Content-Type: application/json, X-API-Key: <key>
```

**Body (optional):**
```json
{
  "inputContext": { "key": "value" }
}
```

### 5. Todo-Resolve-Workflow ausführen

```
POST http://localhost:9090/api/v1/todos/{id}/resolve
Headers: X-API-Key: <key>
Response: 200 OK
```

**Body:** keiner

**Voraussetzungen und Verhalten:**
- Das Todo muss ein `linkedWorkflowId` gesetzt haben.
- Der Endpunkt startet den verlinkten Workflow als Resolve-Run.
- Das Todo wechselt dabei auf `IN_PROGRESS` und speichert den Run in `lastFixRunId`.
- Ein bereits laufender Resolve-Run fuer dasselbe Todo blockiert einen weiteren Start.
- Der Workflow startet nur durch diesen expliziten API-Aufruf oder die UI-Aktion `Loesen`.
- Das reine Setzen von `linkedWorkflowId` startet noch nichts.

### 6. Run-Status abfragen

```
GET http://localhost:9090/api/v1/executions/{runId}
Headers: X-API-Key: <key>
```

**Polling:** Alle 2-5 Sekunden abfragen, Timeout 30 Minuten.

| Status | Terminal? | Beschreibung |
|--------|----------|--------------|
| `PENDING` | Nein | Erstellt, nicht gestartet |
| `RUNNING` | Nein | Läuft gerade |
| `PAUSED` | Nein | Pausiert |
| `COMPLETED` | Ja | Erfolgreich |
| `COMPLETED_WITH_ERRORS` | Ja | Mit Warnungen (Gate YELLOW) |
| `FAILED` | Ja | Fehlgeschlagen |
| `CANCELLED` | Ja | Abgebrochen |
| `INTERRUPTED` | Ja | Unterbrochen |

### 7. Execution steuern

| Aktion | Endpunkt |
|--------|----------|
| Pausieren | `POST /api/v1/executions/{id}/pause` |
| Fortsetzen | `POST /api/v1/executions/{id}/resume` |
| Abbrechen | `POST /api/v1/executions/{id}/cancel` |
| Wiederholen | `POST /api/v1/executions/{id}/retry` |
| Genehmigen | `POST /api/v1/executions/{id}/approve` — Body: `{"answer": "Yes"}` |
| Genehmigung prüfen | `GET /api/v1/executions/{id}/approval` |

### 8. Workflow-Versionen

```bash
# Versionen eines Workflows auflisten (neueste zuerst, paginiert)
GET /api/v1/workflows/{id}/versions?page=0&size=20

# Einzelne Version mit Snapshot
GET /api/v1/workflows/{id}/versions/{versionId}

# Version wiederherstellen (erstellt automatisch neuen Snapshot des aktuellen Zustands)
POST /api/v1/workflows/{id}/versions/{versionId}/restore
```

**Hinweise:**
- Versionen werden automatisch bei jedem Update erstellt (nur bei Änderung an Blocks, Instructions, Model, Provider, Metadata, DefaultContext, Skills)
- `changeSource` zeigt die Quelle: `user`, `ai`, `pool-install`, `import`, `api`
- Kosmetische Änderungen (Name, Beschreibung, Icon) erzeugen keine Version
- Context-Variablen in Workflows: `{{ workflow_version }}` (Versionsnummer), `{{ workflow_changed_since_last_run }}` (true/false)

### 9. Test-Instanz

```bash
# Status prüfen
GET /api/v1/testing/status

# Starten
POST /api/v1/testing/start

# Stoppen
POST /api/v1/testing/stop

# Definitionen synchronisieren (Dev → Test)
POST /api/v1/testing/sync-definitions
Body: { "workflowIds": ["..."], "projectIds": ["..."], "skillIds": ["..."] }
# Alle Felder optional — leerer Body synchronisiert alles

# Testergebnis melden
POST /api/v1/testing/report-result
Body: { "workflowName": "...", "status": "PASSED|FAILED|SKIPPED" }
```

**Sync-Verhalten:**
- Projects: Match by name+path → skip if exists, create if new
- Workflows: Match by name+projectName → update if exists, import if new
- Custom Skills: Match by name → skip if exists
- **Nicht synchronisiert:** workflow_runs, step_results, scheduled_workflows, webhook_triggers

### 10. Hilfreich

```bash
# Projekte auflisten
GET /api/v1/projects

# Workflows eines Projekts
GET /api/v1/workflows?projectId=<uuid>

# System-Backup erstellen
GET /api/v1/system/backup
# → File-Download

# Alle Runs auflisten (mit Filtern)
GET /api/v1/executions?status=FAILED&workflowId=<uuid>

# Assistenten-Sessions auflisten (letzte 50)
GET /api/v1/assistant/sessions

# Einzelne Session mit Nachrichten laden
GET /api/v1/assistant/sessions/<session-uuid>

# Session loeschen
DELETE /api/v1/assistant/sessions/<session-uuid>
```

## Todo-Resolve in der Praxis

```bash
# Todo mit Resolver-Workflow verknuepfen
curl -s -X PUT http://localhost:9090/api/v1/todos/$TODO_ID \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d "{\"linkedWorkflowId\":\"$WORKFLOW_ID\"}"

# Resolver-Workflow fuer das Todo manuell starten
curl -s -X POST http://localhost:9090/api/v1/todos/$TODO_ID/resolve \
  -H "X-API-Key: $DEV_KEY"
```

Hinweise:
- `linkedWorkflowId` ist optional; ohne Link ist `resolve` nicht moeglich.
- `resolve` ist ein manueller Startpunkt und kein automatischer Trigger beim Speichern des Todos.

## Kompletter Beispiel-Flow

```bash
# 1. API-Key auslesen
DEV_KEY=$(python3 -c "import sqlite3; conn = sqlite3.connect('ai-automation-backend/data/automation.db'); c = conn.cursor(); c.execute(\"SELECT value FROM app_settings WHERE key = 'api-key'\"); print(c.fetchone()[0]); conn.close()")

# 2. Test-Status prüfen
curl -s -H "X-API-Key: $DEV_KEY" http://localhost:9090/api/v1/testing/status

# 3. Projekt-ID finden
curl -s -H "X-API-Key: $DEV_KEY" http://localhost:9090/api/v1/projects | python3 -c "import sys,json; [print(f'{p[\"id\"]}  {p[\"name\"]}') for p in json.load(sys.stdin)]"

# 4. Workflow erstellen
WORKFLOW=$(curl -s -X POST http://localhost:9090/api/v1/workflows \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $DEV_KEY" \
  -d '{
    "projectId": "<project-id>",
    "name": "Test-Workflow",
    "blocks": [{ "type": "shell", "config": { "description": "Hello", "command": "echo Hello World" } }]
  }')
WORKFLOW_ID=$(echo $WORKFLOW | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

# 5. Auf Test synchronisieren
curl -s -X POST http://localhost:9090/api/v1/testing/sync-definitions \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" \
  -d "{\"workflowIds\": [\"$WORKFLOW_ID\"]}"

# 6. Auf Test ausführen (Port 8090!)
RUN=$(curl -s -X POST http://localhost:8090/api/v1/workflows/$WORKFLOW_ID/execute \
  -H "Content-Type: application/json" -H "X-API-Key: $TEST_KEY" -d '{}')
RUN_ID=$(echo $RUN | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

# 7. Ergebnis pollen
while true; do
  STATUS=$(curl -s -H "X-API-Key: $TEST_KEY" \
    http://localhost:8090/api/v1/executions/$RUN_ID | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  echo "Status: $STATUS"
  case $STATUS in COMPLETED|FAILED|CANCELLED|INTERRUPTED|COMPLETED_WITH_ERRORS) break;; esac
  sleep 3
done

# 8. Ergebnis melden
curl -s -X POST http://localhost:9090/api/v1/testing/report-result \
  -H "Content-Type: application/json" -H "X-API-Key: $DEV_KEY" \
  -d '{"workflowName": "Test-Workflow", "status": "PASSED"}'
```

## Wichtige Regeln

- **Context, nicht Variables:** Bei `execute` immer das `context`-Feld verwenden
- **Template-Variablen:** `{{ variable_name }}` Syntax in Prompts und Commands
- **previous_output:** Enthält den Output des vorherigen Steps
- **Ports:** Dev = 9090, Test = 8090, Stable = 9091
- **DB nie direkt beschreiben:** Immer über REST API gehen
- **foreach ist lowercase:** Block-Typ heißt `foreach`, nicht `forEach`
- **Approval-Feld:** `{"answer": "Yes"}`, nicht `{"approved": true}`
