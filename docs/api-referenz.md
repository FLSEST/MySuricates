# API-Referenz

## Allgemein

- **Basis-URL:** `http://localhost:9090/api/v1`
- **Content-Type:** `application/json`
- **Authentifizierung:** Header `X-API-Key: <key>` (wenn `automation.security.enabled=true`)

### Fehlerformat

Alle Fehler folgen diesem Format:

```json
{
  "error": "Fehlermeldung",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

Bei Validierungsfehlern (400) zusätzlich:

```json
{
  "error": "Validation failed",
  "timestamp": "2025-01-15T10:30:00Z",
  "fieldErrors": {
    "name": "must not be blank",
    "type": "must not be null"
  }
}
```

### HTTP-Statuscodes

| Code | Bedeutung |
|---|---|
| 200 | Erfolg |
| 201 | Ressource erstellt |
| 204 | Gelöscht (kein Body) |
| 400 | Validierungsfehler |
| 404 | Ressource nicht gefunden |
| 409 | Konflikt (ungültiger Zustand) |
| 429 | Rate Limit überschritten |
| 500 | Interner Serverfehler |
| 503 | Service nicht verfügbar (z.B. Tailscale) |

---

## Projects

### `GET /api/v1/projects`

Alle Projekte auflisten.

**Response:** `ProjectResponse[]`

```json
[
  {
    "id": "uuid",
    "name": "Mein Projekt",
    "type": "CODE",
    "path": "/workspace/projekt",
    "config": {},
    "status": "STOPPED",
    "container": null,
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-15T10:00:00Z"
  }
]
```

### `POST /api/v1/projects`

Neues Projekt erstellen.

**Request:**

```json
{
  "name": "Mein Projekt",
  "type": "CODE",
  "path": "/workspace/projekt",
  "config": {}
}
```

| Feld | Typ | Pflicht | Werte |
|---|---|---|---|
| `name` | String | Ja | |
| `type` | Enum | Ja | `CODE`, `CONTENT`, `RESEARCH`, `BUSINESS`, `PERSONAL` |
| `path` | String | Ja | Pfad im Container |
| `config` | Object | Nein | Freies JSON |

**Response:** `201` mit `ProjectResponse`

### `GET /api/v1/projects/{id}`

Einzelnes Projekt abrufen.

**Response:** `ProjectResponse`

### `PUT /api/v1/projects/{id}`

Projekt aktualisieren.

**Request:**

```json
{
  "name": "Neuer Name",
  "type": "RESEARCH",
  "path": "/workspace/neu",
  "config": {}
}
```

Alle Felder sind optional; nur übergebene Felder werden aktualisiert.

**Response:** `ProjectResponse`

### `DELETE /api/v1/projects/{id}`

Projekt löschen (inkl. Container und Workflows).

**Response:** `204 No Content`

### `POST /api/v1/projects/{id}/start`

Docker-Container für das Projekt starten.

**Response:** `ProjectResponse` (Status wechselt zu `STARTING` → `RUNNING`)

### `POST /api/v1/projects/{id}/stop`

Docker-Container stoppen.

**Response:** `ProjectResponse` (Status wechselt zu `STOPPED`)

### `GET /api/v1/projects/{id}/workflows`

Alle Workflows eines Projekts auflisten.

**Response:** `WorkflowResponse[]`

---

## Workflows

### `GET /api/v1/workflows`

Alle Workflows auflisten.

**Response:** `WorkflowResponse[]`

```json
[
  {
    "id": "uuid",
    "projectId": "uuid",
    "name": "Code Review",
    "description": "Automatisiertes Code Review",
    "icon": "🔍",
    "blocks": [
      { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [] }
    ],
    "version": "1.0.0",
    "metadata": {},
    "activatedSkills": [],
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-15T10:00:00Z"
  }
]
```

### `POST /api/v1/workflows`

Neuen Workflow erstellen.

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Code Review",
  "description": "Automatisiertes Code Review",
  "icon": "🔍",
  "blocks": [
    { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [] }
  ],
  "activatedSkillIds": ["skill-uuid-1"]
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `projectId` | String | Ja |
| `name` | String | Ja |
| `description` | String | Nein |
| `icon` | String | Nein |
| `blocks` | BlockDefinition[] | Ja |
| `activatedSkillIds` | String[] | Nein |

**Response:** `201` mit `WorkflowResponse`

### `GET /api/v1/workflows/{id}`

Einzelnen Workflow abrufen.

**Response:** `WorkflowResponse`

### `PUT /api/v1/workflows/{id}`

Workflow aktualisieren.

**Request:**

```json
{
  "name": "Neuer Name",
  "description": "Neue Beschreibung",
  "icon": "✨",
  "blocks": []
}
```

**Response:** `WorkflowResponse`

### `DELETE /api/v1/workflows/{id}`

Workflow löschen.

**Response:** `204 No Content`

### `GET /api/v1/workflows/export/{id}`

Workflow als portables JSON exportieren (ohne IDs, ohne Projekt-Zuordnung).

**Response:** `ExportWorkflowResponse`

```json
{
  "name": "Code Review",
  "description": "Automatisiertes Code Review",
  "icon": "🔍",
  "blocks": [
    { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [] }
  ],
  "instructions": null,
  "model": null,
  "version": "1.0.0",
  "exportedAt": "2025-02-22T14:00:00Z"
}
```

### `POST /api/v1/workflows/import`

Workflow aus JSON importieren (exportiertes Format + Zielprojekt).

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Code Review",
  "description": "Automatisiertes Code Review",
  "icon": "🔍",
  "blocks": [
    { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [] }
  ],
  "instructions": null,
  "model": null
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `projectId` | String | Ja |
| `name` | String | Ja |
| `description` | String | Nein |
| `icon` | String | Nein |
| `blocks` | BlockDefinition[] | Nein |
| `instructions` | String | Nein |
| `model` | String | Nein |

**Response:** `201` mit `WorkflowResponse`

### `POST /api/v1/workflows/{id}/execute`

Workflow ausführen.

**Request (optional):**

```json
{
  "context": {
    "target": "src/main.py",
    "mode": "strict"
  }
}
```

**Response:** `WorkflowRunResponse`

```json
{
  "id": "run-uuid",
  "workflowId": "workflow-uuid",
  "status": "RUNNING",
  "steps": [],
  "startedAt": "2025-01-15T10:00:00Z",
  "completedAt": null,
  "durationMs": null,
  "userRating": null,
  "error": {},
  "metadata": {}
}
```

### `GET /api/v1/workflows/{id}/runs`

Alle Runs eines Workflows (absteigend nach Startzeit).

**Response:** `WorkflowRunResponse[]`

### `GET /api/v1/workflows/{id}/runs/{runId}`

Einzelnen Run abrufen.

**Response:** `WorkflowRunResponse`

### `POST /api/v1/workflows/{id}/skills`

Skills für einen Workflow aktivieren.

**Request:**

```json
{
  "skillIds": ["skill-uuid-1", "skill-uuid-2"]
}
```

**Response:** `WorkflowResponse`

### `DELETE /api/v1/workflows/{id}/skills/{skillId}`

Skill von einem Workflow deaktivieren.

**Response:** `WorkflowResponse`

---

## Executions

### `GET /api/v1/executions/{id}`

Execution-Status abrufen (inkl. Steps).

**Response:** `WorkflowRunResponse`

### `POST /api/v1/executions/{id}/pause`

Laufende Ausführung pausieren.

**Response:** `200 OK`

### `POST /api/v1/executions/{id}/resume`

Pausierte Ausführung fortsetzen.

**Response:** `200 OK`

### `POST /api/v1/executions/{id}/cancel`

Ausführung abbrechen.

**Response:** `200 OK`

---

## Skills

### `GET /api/v1/skills`

Alle Skills auflisten.

**Response:** `SkillResponse[]`

```json
[
  {
    "id": "uuid",
    "name": "Code Analysis",
    "description": "Analysiert Quellcode",
    "icon": "🔬",
    "category": "CODE",
    "aiContext": "You are a code analysis expert...",
    "configSchema": {},
    "builtin": true,
    "version": "1.0.0",
    "capabilities": [
      {
        "id": "uuid",
        "name": "analyze",
        "description": "Analysiert Code",
        "inputSchema": {},
        "outputSchema": {},
        "async": false
      }
    ],
    "createdAt": "2025-01-15T10:00:00Z"
  }
]
```

### `GET /api/v1/skills/{id}`

Einzelnen Skill abrufen.

**Response:** `SkillResponse`

### `GET /api/v1/skills/{id}/capabilities`

Capabilities eines Skills auflisten.

**Response:** `CapabilityResponse[]`

### `POST /api/v1/skills`

Neuen Skill erstellen.

**Request:**

```json
{
  "name": "Custom Skill",
  "description": "Beschreibung",
  "icon": "⚡",
  "category": "CODE",
  "aiContext": "System prompt...",
  "configSchema": {}
}
```

| Feld | Typ | Pflicht | Werte |
|---|---|---|---|
| `name` | String | Ja | |
| `category` | Enum | Ja | `DOCUMENT`, `CODE`, `DATA`, `CONTENT`, `RESEARCH`, `BUSINESS`, `PRODUCTIVITY` |
| `description` | String | Nein | |
| `icon` | String | Nein | |
| `aiContext` | String | Nein | |
| `configSchema` | Object | Nein | |

**Response:** `201` mit `SkillResponse`

### `DELETE /api/v1/skills/{id}`

Skill löschen.

**Response:** `204 No Content`

### `GET /api/v1/skills/export/{id}`

Skill als JSON exportieren (inkl. Capabilities).

**Response:** `ExportSkillResponse`

### `POST /api/v1/skills/import`

Skill aus JSON importieren.

**Request:** `ImportSkillRequest` (exportiertes Format)

**Response:** `201` mit `SkillResponse`

---

## Schedules

### `GET /api/v1/schedules`

Alle Schedules auflisten.

**Response:** `ScheduledWorkflowResponse[]`

```json
[
  {
    "id": "uuid",
    "workflowId": "uuid",
    "workflowName": "Nightly Build",
    "cronExpression": "0 0 2 * * ?",
    "enabled": true,
    "description": "Nächtlicher Build",
    "inputContext": {},
    "lastRunAt": null,
    "nextRunAt": "2025-01-16T02:00:00Z",
    "createdAt": "2025-01-15T10:00:00Z"
  }
]
```

### `GET /api/v1/schedules/{id}`

Einzelnen Schedule abrufen.

**Response:** `ScheduledWorkflowResponse`

### `GET /api/v1/schedules/workflow/{workflowId}`

Schedules eines bestimmten Workflows.

**Response:** `ScheduledWorkflowResponse[]`

### `POST /api/v1/schedules`

Neuen Schedule erstellen.

**Request:**

```json
{
  "workflowId": "uuid",
  "cronExpression": "0 0 2 * * ?",
  "description": "Nächtlicher Build",
  "inputContext": {}
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `workflowId` | String | Ja |
| `cronExpression` | String | Ja (Spring Cron, 6 Felder) |
| `description` | String | Nein |
| `inputContext` | Object | Nein |

**Response:** `201` mit `ScheduledWorkflowResponse`

### `POST /api/v1/schedules/{id}/enable`

Schedule aktivieren.

**Response:** `ScheduledWorkflowResponse`

### `POST /api/v1/schedules/{id}/disable`

Schedule deaktivieren.

**Response:** `ScheduledWorkflowResponse`

### `DELETE /api/v1/schedules/{id}`

Schedule löschen.

**Response:** `204 No Content`

---

## Webhooks

### `GET /api/v1/webhooks`

Alle Webhooks auflisten.

**Response:** `WebhookTriggerResponse[]`

```json
[
  {
    "id": "uuid",
    "workflowId": "uuid",
    "workflowName": "Deploy Pipeline",
    "secretToken": "abc123...",
    "webhookUrl": "http://localhost:9090/api/v1/webhook/trigger/abc123...",
    "enabled": true,
    "description": "GitHub Push Hook",
    "defaultContext": {},
    "lastTriggeredAt": null,
    "triggerCount": 0,
    "createdAt": "2025-01-15T10:00:00Z"
  }
]
```

### `GET /api/v1/webhooks/{id}`

Einzelnen Webhook abrufen.

**Response:** `WebhookTriggerResponse`

### `GET /api/v1/webhooks/workflow/{workflowId}`

Webhooks eines bestimmten Workflows.

**Response:** `WebhookTriggerResponse[]`

### `POST /api/v1/webhooks`

Neuen Webhook erstellen (Token wird automatisch generiert).

**Request:**

```json
{
  "workflowId": "uuid",
  "description": "GitHub Push Hook",
  "defaultContext": {}
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `workflowId` | String | Ja |
| `description` | String | Nein |
| `defaultContext` | Object | Nein |

**Response:** `201` mit `WebhookTriggerResponse`

### `POST /api/v1/webhooks/{id}/enable`

Webhook aktivieren.

**Response:** `WebhookTriggerResponse`

### `POST /api/v1/webhooks/{id}/disable`

Webhook deaktivieren.

**Response:** `WebhookTriggerResponse`

### `DELETE /api/v1/webhooks/{id}`

Webhook löschen.

**Response:** `204 No Content`

### `POST /api/v1/webhook/trigger/{token}` (Öffentlich)

Webhook auslösen. **Kein API-Key nötig.**

**Request (optional):**

```json
{
  "branch": "main",
  "commit": "abc123"
}
```

Der Payload wird mit dem `defaultContext` des Webhooks gemergt und als Kontext an die Workflow-Ausführung übergeben.

**Response:** `WorkflowRunResponse`

---

## System

### `GET /api/v1/system/health` (Öffentlich)

Health-Check.

**Response:**

```json
{
  "status": "UP",
  "timestamp": "2025-01-15T10:00:00Z",
  "docker": "CONNECTED"
}
```

`docker` kann `CONNECTED` oder `UNAVAILABLE` sein.

### `GET /api/v1/system/info` (Öffentlich)

Systeminformationen.

**Response:**

```json
{
  "name": "AI Automation Platform",
  "version": "0.1.0",
  "java": "21.0.1",
  "tailscale": "DISABLED"
}
```

### `POST /api/v1/system/remote-access/enable`

Tailscale Remote-Access aktivieren.

**Response:** `RemoteAccessResponse`

```json
{
  "enabled": true,
  "ip": "100.64.0.1",
  "hostname": "automation.tailnet.ts.net"
}
```

### `POST /api/v1/system/remote-access/disable`

Tailscale Remote-Access deaktivieren.

**Response:** `200 OK`

### `GET /api/v1/system/remote-access/status`

Tailscale-Status abfragen.

**Response:** `RemoteAccessResponse`

---

## Learning / Dashboard

### `GET /api/v1/learning/dashboard`

Dashboard-Daten mit Statistiken.

**Response:**

```json
{
  "totalWorkflows": 12,
  "totalRuns": 156,
  "successfulRuns": 140,
  "failedRuns": 16,
  "averageDurationMs": 45000,
  "successRate": 89.7,
  "topWorkflows": [...],
  "recentRuns": [...],
  "timeSeries": [
    { "date": "2025-01-15", "count": 8, "successRate": 87.5 }
  ]
}
```

### `GET /api/v1/learning/workflows/{workflowId}/stats`

Statistiken eines einzelnen Workflows.

**Response:**

```json
{
  "workflowId": "uuid",
  "totalRuns": 42,
  "successfulRuns": 38,
  "failedRuns": 4,
  "averageDurationMs": 32000,
  "successRate": 90.5
}
```

### `GET /api/v1/learning/workflows/{workflowId}/step-analytics`

Step-Level-Analyse eines Workflows.

**Response:** `Array<Object>` mit Schritt-Statistiken

### `GET /api/v1/learning/workflows/{workflowId}/suggestions`

Optimierungsvorschläge basierend auf Run-Daten.

**Response:** `Array<Object>` mit Verbesserungsvorschlägen

### `GET /api/v1/learning/workflows/{workflowId}/similar`

Ähnliche Workflows finden.

**Response:** `Array<Object>` mit ähnlichen Workflows

### `POST /api/v1/learning/runs/{runId}/rate?rating={1-5}`

Einen Run bewerten (1-5 Sterne).

| Parameter | Typ | Pflicht | Werte |
|---|---|---|---|
| `rating` | int (Query) | Ja | 1-5 |

**Response:** `200 OK`

---

## WebSocket

### Verbindung

```
Endpunkt: ws://localhost:9090/ws (SockJS)
Protokoll: STOMP
```

### Subscription

```
Topic: /topic/executions/{runId}/logs
```

### Nachrichtenformat

```json
{
  "timestamp": "2025-01-15T10:00:05Z",
  "level": "INFO",
  "message": "Executing step 1: Review code..."
}
```

Level: `INFO`, `WARN`, `ERROR`, `DEBUG`
