## MySuricates API

Dieses Projekt ist mit [MySuricates](http://localhost:9090) verbunden — einer AI Workflow Automation Plattform.
Du kannst TODOs verwalten, Workflows triggern und Ausführungen steuern.

### Verbindung herstellen

API-Key auslesen (wird beim Backend-Start automatisch geschrieben):
```bash
API_KEY=$(cat ~/.autotrigger/api-key)
```

Health-Check:
```bash
curl -sf http://localhost:9090/api/v1/system/health | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])"
```

Projekt-ID des aktuellen Verzeichnisses nachschlagen:
```bash
curl -s http://localhost:9090/api/v1/projects -H "X-API-Key: $API_KEY" | python3 -c "import sys,json; [print(p['id'],p['name']) for p in json.load(sys.stdin) if p.get('path')=='$(pwd)']"
```

### TODO anlegen

```
POST http://localhost:9090/api/v1/todos
Headers: Content-Type: application/json, X-API-Key: $API_KEY
Body: {
  "title": "Kurzer Titel",
  "description": "Details in Markdown",
  "priority": "HIGH | MEDIUM | LOW",
  "tags": ["bug", "feature"],
  "projectId": "{{PROJECT_ID}}",
  "linkedWorkflowId": "{{WORKFLOW_ID}}",
  "source": "AI",
  "sourceAgent": "claude-code"
}
```

Hinweise:
- `linkedWorkflowId` ist optional. Ohne Link gibt es keinen Resolve-Workflow fuer das TODO.
- Wird ein Link gesetzt, ist das rein deklarativ. Der Workflow startet dadurch noch nicht.

### TODOs abfragen

```
GET http://localhost:9090/api/v1/todos
Headers: X-API-Key: $API_KEY
Query-Parameter:
  projectId  — UUID des Projekts
  status     — OPEN, IN_PROGRESS, DONE, BACKLOG (mehrfach: ?status=OPEN&status=IN_PROGRESS)
  priority   — LOW, MEDIUM, HIGH, CRITICAL
  source     — USER, AI, WORKFLOW
  tag        — Tag-Filter (Wildcard)
  q          — Volltextsuche in Titel und Beschreibung
  page       — Seitennummer (ab 0, Standard: 0)
  size       — Seitengröße (Standard: 50)
```

Status-Übersicht: `GET /api/v1/todos/count`
Duplikat-Check: `GET /api/v1/todos/similar?projectId=<id>&title=<titel>`
Status ändern: `PATCH /api/v1/todos/{id}/status` — Body: `{"status": "DONE"}`
Aktualisieren: `PUT /api/v1/todos/{id}` — Body: `{ "title": "...", "priority": "HIGH", "tags": [...], "linkedWorkflowId": "..." }`
Resolver starten: `POST /api/v1/todos/{id}/resolve`

### TODO-Resolve-Workflow

```
POST http://localhost:9090/api/v1/todos/{todoId}/resolve
Headers: X-API-Key: $API_KEY
Body: <leer>
```

Verhalten:
- Der Endpunkt startet den in `linkedWorkflowId` hinterlegten Workflow.
- Ohne `linkedWorkflowId` liefert der Endpunkt einen Fehler.
- Bereits laufende Resolve-Runs fuer dasselbe TODO werden blockiert.
- Das TODO wird beim Start auf `IN_PROGRESS` gesetzt.
- Der gestartete Run wird in `lastFixRunId` gespeichert.

Wichtige Response-Felder an TODOs:
- `linkedWorkflowId` — optionaler Resolver-Workflow
- `linkedWorkflowName` — aufgeloester Workflow-Name fuer die UI
- `lastFixRunId` — letzter gestarteter Resolve-Run
- `resolutionState` — `IDLE | RUNNING | FAILED | SUCCEEDED`

Wichtig:
- `POST /api/v1/todos/{id}/resolve` startet nur manuell auf Aufruf.
- Weder `POST /api/v1/todos`, noch `PUT /api/v1/todos/{id}`, noch ein Statuswechsel triggern automatisch den Workflow.

### Workflow ausführen

```
POST http://localhost:9090/api/v1/workflows/{workflowId}/execute
Headers: Content-Type: application/json, X-API-Key: $API_KEY
Body: { "context": { "variable1": "wert1" } }
```

**WICHTIG:** Immer `context` verwenden, nicht `variables`!

### Chain ausführen

```
POST http://localhost:9090/api/v1/chains/{chainId}/execute
Headers: Content-Type: application/json, X-API-Key: $API_KEY
Body: { "inputContext": { "key": "value" } }
```

### Execution-Ergebnis abrufen

```
GET http://localhost:9090/api/v1/executions/{runId}
Headers: X-API-Key: $API_KEY
→ { status, steps: [{ blockName, output, status, durationMs }], gateStatus, durationMs, metadata }
```

### Execution steuern

| Aktion | Endpunkt |
|--------|----------|
| Status abfragen | `GET /api/v1/executions/{runId}` |
| Pausieren | `POST /api/v1/executions/{id}/pause` |
| Fortsetzen | `POST /api/v1/executions/{id}/resume` |
| Abbrechen | `POST /api/v1/executions/{id}/cancel` |
| Genehmigen | `POST /api/v1/executions/{id}/approve` — Body: `{"answer": "Yes"}` |

### Block-Typen

| Typ | Beschreibung |
|-----|-------------|
| `simple` | Claude-Aufruf mit Prompt |
| `shell` | Shell-Befehl auf Host |
| `agentic` | Goal→Reason→Act→Observe Loop |
| `conditional` | Verzweigung (if/else) |
| `foreach` | Iteration über Liste (lowercase!) |
| `while` | Schleife mit Bedingung |
| `retry` | Wiederholung bei Fehler |
| `switch` | Multi-Way-Branching |
| `approval` | Warten auf Genehmigung |
| `sub-workflow` | Verschachtelter Workflow |
| `human-input` | User-Eingabe |
| `compress` | Ausgabe regelbasiert verdichten |
| `compress-ai` | Ausgabe per KI verdichten |

### Regeln

- Template-Variablen: `{{ variable_name }}` Syntax in Prompts und Commands
- `{{ previous_output }}` enthält den Output des vorherigen Steps
- Ports: Dev = 9090, Test = 8090, Stable = 9091
- DB nie direkt beschreiben — immer über REST API
- `foreach` ist lowercase, nicht `forEach`
- Approval: `{"answer": "Yes"}`, nicht `{"approved": true}`
