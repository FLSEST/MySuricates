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
    "base_dir": "/home/flo/projects/example"
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

### 5. Run-Status abfragen

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

### 6. Execution steuern

| Aktion | Endpunkt |
|--------|----------|
| Pausieren | `POST /api/v1/executions/{id}/pause` |
| Fortsetzen | `POST /api/v1/executions/{id}/resume` |
| Abbrechen | `POST /api/v1/executions/{id}/cancel` |
| Wiederholen | `POST /api/v1/executions/{id}/retry` |
| Genehmigen | `POST /api/v1/executions/{id}/approve` — Body: `{"answer": "Yes"}` |
| Genehmigung prüfen | `GET /api/v1/executions/{id}/approval` |

### 7. Test-Instanz

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

### 8. Hilfreich

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
```

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
