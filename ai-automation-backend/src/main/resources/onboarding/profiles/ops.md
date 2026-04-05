## Operations-Workflows

Für DevOps und System-Administration stehen folgende Workflow-Patterns zur Verfügung:

### Monitoring & Alerting
- `shell`-Block für Metriken-Abfragen (curl, API-Calls)
- `conditional`-Block für Schwellwert-Prüfungen
- Schedule-Trigger für regelmäßige Checks
- Gate-Status-Marker: `gate_status: RED` bei kritischen Werten

### Log-Analyse
- `shell`-Block zum Lesen und Filtern von Logs
- `simple`-Block für Claude-gestützte Analyse von Fehlermustern
- `agentic`-Block für tiefgehende Root-Cause-Analyse

### Deployment & Release
- Chain: Build → Test → Deploy → Verify
- `approval`-Block vor Production-Deployments
- `shell`-Block für Deploy-Kommandos und Health-Checks
- `retry`-Block für Health-Check-Polling nach Deploy

### Scheduled Tasks
- Schedule-Trigger für regelmäßige Wartungsaufgaben
- `shell`-Block für Cleanup, Backup, Log-Rotation
- `simple`-Block für tägliche Status-Reports

### Infrastruktur-Checks
- `foreach`-Block über Server-/Service-Liste
- `switch`-Block für service-spezifische Checks
- TODOs automatisch anlegen bei Problemen

### Empfohlene Variablen
- `environment`: Zielumgebung (dev, staging, production)
- `service_name`: Betroffener Service
- `threshold`: Schwellwert für Alerting

## API-Referenz für Operations

### Schedules verwalten

```
GET http://localhost:9090/api/v1/schedules
Headers: X-API-Key: $API_KEY
→ [{ id, workflowId, workflowName, cronExpression, enabled, nextRunAt, lastRunStatus }]
```

Schedule erstellen:
```
POST http://localhost:9090/api/v1/schedules
Headers: Content-Type: application/json, X-API-Key: $API_KEY
Body: { "workflowId": "<id>", "cronExpression": "*/15 * * * *", "enabled": true, "description": "Health Check alle 15min" }
```

Enable/Disable: `POST /api/v1/schedules/{id}/enable` bzw. `/disable`
Nächste Runs heute: `GET /api/v1/schedules/remaining-today`

### Webhooks einrichten

```
POST http://localhost:9090/api/v1/webhooks
Headers: Content-Type: application/json, X-API-Key: $API_KEY
Body: { "workflowId": "<id>", "description": "Deploy Hook" }
→ { id, secretToken, webhookUrl }
```

Trigger-URL (public): `POST /api/v1/webhook/trigger/{token}`

### Activity Log

```
GET http://localhost:9090/api/v1/activity-log?level=ERROR&page=0&size=50
Headers: X-API-Key: $API_KEY
Filter: level (INFO/WARN/ERROR), category, workflowId
→ { content: [{ timestamp, level, category, message, source }], totalElements }
```

### Run-History

```
GET http://localhost:9090/api/v1/executions?workflowId=<id>&status=FAILED&page=0&size=10
Headers: X-API-Key: $API_KEY
→ { content: [{ id, status, error, durationMs, startedAt }], totalElements }
```
