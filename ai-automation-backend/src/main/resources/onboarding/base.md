## MySuricates API

Dieses Projekt ist mit [MySuricates](http://localhost:9090) verbunden — einer AI Workflow Automation Plattform.
Du kannst TODOs anlegen, Workflows triggern und Ausführungen steuern.

### TODO anlegen

```
POST http://localhost:9090/api/v1/todos
Headers: Content-Type: application/json, X-API-Key: <key>
Body: {
  "title": "Kurzer Titel",
  "description": "Details in Markdown",
  "priority": "HIGH | MEDIUM | LOW",
  "tags": ["bug", "feature"],
  "projectId": "{{PROJECT_ID}}",
  "source": "AI",
  "sourceAgent": "claude-code"
}
```

### Workflow ausführen

```
POST http://localhost:9090/api/v1/workflows/{workflowId}/execute
Headers: Content-Type: application/json, X-API-Key: <key>
Body: { "context": { "variable1": "wert1" } }
```

**WICHTIG:** Immer `context` verwenden, nicht `variables`!

### Chain ausführen

```
POST http://localhost:9090/api/v1/chains/{chainId}/execute
Headers: Content-Type: application/json, X-API-Key: <key>
Body: { "inputContext": { "key": "value" } }
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

### Regeln

- Template-Variablen: `{{ variable_name }}` Syntax in Prompts und Commands
- `{{ previous_output }}` enthält den Output des vorherigen Steps
- Ports: Dev = 9090, Test = 8090, Stable = 9091
- DB nie direkt beschreiben — immer über REST API
- `foreach` ist lowercase, nicht `forEach`
- Approval: `{"answer": "Yes"}`, nicht `{"approved": true}`
