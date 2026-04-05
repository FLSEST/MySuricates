## Entwickler-Workflows

Für Code-Projekte stehen folgende Workflow-Patterns zur Verfügung:

### Code-Analyse & Review
- **Code-Review Workflows** analysieren Änderungen auf Bugs, Security-Issues und Code-Qualität
- Nutze `shell`-Block für `git diff` + `simple`-Block für Claude-Analyse
- Für tiefgehende Analyse: `agentic`-Block mit Goal "Review code changes"

### Test-Pipelines
- `shell`-Block zum Ausführen von Tests (`mvn test`, `npm test`, etc.)
- `retry`-Block bei flaky Tests mit `max_retries: 3`
- `conditional`-Block für branch-abhängige Test-Suiten

### Auto-Fix & Quality Gates
- `agentic`-Block mit Goal "Fix lint errors" oder "Fix failing tests"
- Gate-Status-Marker in Block-Output: `gate_status: GREEN|YELLOW|RED`
- `approval`-Block vor dem Anwenden von automatischen Fixes

### CI/CD Patterns
- `shell`-Block für Build-Kommandos
- `foreach`-Block für Multi-Modul-Builds
- Chain mehrere Workflows: Build → Test → Deploy

### Empfohlene Variablen
- `branch`: Git-Branch für kontextabhängige Aktionen
- `base_dir`: Projektverzeichnis
- `test_filter`: Spezifische Tests ausführen

## API-Referenz für Entwickler

### Workflows auflisten

```
GET http://localhost:9090/api/v1/workflows
Headers: X-API-Key: $API_KEY
→ [{ id, name, description, blocks, model, projectId, projectName, runCount }]
```

Workflows eines Projekts: `GET /api/v1/projects/{projectId}/workflows`

### Skills auflisten

```
GET http://localhost:9090/api/v1/skills
Headers: X-API-Key: $API_KEY
→ [{ id, name, description, category, builtin, capabilities }]
```

### Run-History

```
GET http://localhost:9090/api/v1/executions?workflowId=<id>&status=COMPLETED&page=0&size=10
Headers: X-API-Key: $API_KEY
→ { content: [{ id, status, durationMs, startedAt, completedAt, metadata }], totalElements }
```

Runs eines Workflows: `GET /api/v1/workflows/{id}/runs?page=0&size=10`

### Projekt nachschlagen & erstellen

```
GET http://localhost:9090/api/v1/projects
Headers: X-API-Key: $API_KEY
→ [{ id, name, path, type, status }]
```

Neues Projekt:
```
POST http://localhost:9090/api/v1/projects
Headers: Content-Type: application/json, X-API-Key: $API_KEY
Body: { "name": "Projektname", "path": "/absoluter/pfad", "type": "CODE" }
```

### Schedules verwalten

```
GET http://localhost:9090/api/v1/schedules
Headers: X-API-Key: $API_KEY
→ [{ id, workflowId, workflowName, cronExpression, enabled, nextRunAt }]
```

Schedule erstellen:
```
POST http://localhost:9090/api/v1/schedules
Headers: Content-Type: application/json, X-API-Key: $API_KEY
Body: { "workflowId": "<id>", "cronExpression": "0 8 * * 1-5", "enabled": true, "description": "Täglicher Check" }
```

Enable/Disable: `POST /api/v1/schedules/{id}/enable` bzw. `/disable`

### Webhooks einrichten

```
POST http://localhost:9090/api/v1/webhooks
Headers: Content-Type: application/json, X-API-Key: $API_KEY
Body: { "workflowId": "<id>", "description": "GitHub Push", "defaultContext": {} }
→ { id, secretToken, webhookUrl }
```

Trigger-URL (public, kein Auth): `POST /api/v1/webhook/trigger/{token}`

### Activity Log

```
GET http://localhost:9090/api/v1/activity-log?page=0&size=20
Headers: X-API-Key: $API_KEY
Filter: level (INFO/WARN/ERROR), category, workflowId
→ { content: [{ timestamp, level, category, message, source }], totalElements }
```

### Workflow Memory

```
GET http://localhost:9090/api/v1/workflows/{workflowId}/memory
Headers: X-API-Key: $API_KEY
→ [{ id, title, content, tags, active }]
```

Wissen extrahieren: `POST /api/v1/workflows/{workflowId}/memory/extract`
