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
| `templateId` | String | Nein | ID des Quell-Templates (wenn aus Template erstellt) |

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

#### Block-Config: `timeout_seconds`

Alle Block-Typen unterstützen ein optionales `timeout_seconds`-Feld in der Block-Config.
Damit wird das Claude-CLI-Timeout pro Block übersteuert (Default: globaler Wert aus `automation.host-claude.timeout-seconds`, Standard 300s).

```json
{
  "type": "simple",
  "config": {
    "command": "Analysiere den Code in src/",
    "timeout_seconds": 600
  }
}
```

| Block-Typ | Wirkung von `timeout_seconds` |
|---|---|
| `simple` | Claude-CLI-Timeout für den Step |
| `retry` | Claude-CLI-Timeout pro Retry-Versuch (Shell: Shell-Timeout) |
| `conditional` | Claude-CLI-Timeout für Condition-Auswertung und Branch-Ausführung |
| `foreach` | Claude-CLI-Timeout pro Iteration (Shell: Shell-Timeout) |
| `while` | Claude-CLI-Timeout für Condition und Body (Shell: Shell-Timeout) |
| `skill-capability` | Claude-CLI-Timeout für Capability-Ausführung |
| `agentic` | Claude-CLI-Timeout für REASON- und ACT-Phase pro Iteration |
| `shell` | Shell-Prozess-Timeout (wie bisher) |

Ohne `timeout_seconds` greift der globale Default (`automation.host-claude.timeout-seconds`).

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

## Workflow Templates

### `GET /api/v1/workflow-templates`

Alle Workflow-Templates auflisten (sortiert nach `sortOrder`).

**Response:** `WorkflowTemplateResponse[]`

```json
[
  {
    "id": "uuid",
    "name": {"de": "Code-Qualität", "en": "Code Quality"},
    "description": {"de": "Prüft Code-Qualität", "en": "Checks code quality"},
    "icon": "🔍",
    "category": "CODE",
    "blocks": [
      { "type": "simple", "config": { "command": "npm run lint" } }
    ],
    "instructions": null,
    "model": null,
    "builtIn": true,
    "sortOrder": 0,
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-15T10:00:00Z"
  }
]
```

### `GET /api/v1/workflow-templates/{id}`

Einzelnes Template abrufen.

**Response:** `WorkflowTemplateResponse`

### `POST /api/v1/workflow-templates`

Neues User-Template erstellen (`builtIn` wird automatisch auf `false` gesetzt).

**Request:**

```json
{
  "name": {"de": "Mein Template", "en": "My Template"},
  "description": {"de": "Beschreibung", "en": "Description"},
  "icon": "⚡",
  "category": "CODE",
  "blocks": [
    { "type": "simple", "config": { "command": "echo hello" } }
  ],
  "instructions": null,
  "model": null
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `name` | Map<String, String> | Ja |
| `description` | Map<String, String> | Nein |
| `icon` | String | Nein |
| `category` | String | Nein |
| `blocks` | BlockDefinition[] | Ja |
| `instructions` | String | Nein |
| `model` | String | Nein |

**Response:** `201` mit `WorkflowTemplateResponse`

### `PUT /api/v1/workflow-templates/{id}`

Template aktualisieren. Bei Built-in Templates können nur `sortOrder` und `category` geändert werden.

**Request:**

```json
{
  "name": {"de": "Neuer Name", "en": "New Name"},
  "description": {"de": "Neue Beschreibung", "en": "New Description"},
  "sortOrder": 5
}
```

Alle Felder sind optional; nur übergebene Felder werden aktualisiert.

**Response:** `WorkflowTemplateResponse`

### `DELETE /api/v1/workflow-templates/{id}`

User-Template löschen. Built-in Templates können nicht gelöscht werden (→ `400`).

**Response:** `204 No Content`

### `POST /api/v1/workflow-templates/from-workflow/{workflowId}`

Einen bestehenden Workflow als neues Template speichern (Name, Beschreibung, Blöcke, Instructions, Model werden übernommen).

**Response:** `201` mit `WorkflowTemplateResponse`

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

## Chains

### `GET /api/v1/chains`

Alle Workflow-Chains auflisten.

**Response:** `ChainResponse[]`

### `POST /api/v1/chains`

Neue Chain erstellen.

**Request:**

```json
{
  "name": "Deploy Pipeline",
  "description": "Build, Test, Deploy",
  "icon": "⛓",
  "entries": [
    { "workflowId": "uuid", "sortOrder": 0, "inputContext": {}, "stopOnFailure": true }
  ],
  "stopOnFailure": true
}
```

**Response:** `201` mit `ChainResponse`

### `GET /api/v1/chains/{id}`

Chain abrufen.

**Response:** `ChainResponse`

### `PUT /api/v1/chains/{id}`

Chain aktualisieren.

**Response:** `ChainResponse`

### `DELETE /api/v1/chains/{id}`

Chain löschen.

**Response:** `204 No Content`

### `POST /api/v1/chains/{id}/execute`

Chain ausführen.

**Request (optional):** `{ "key": "value" }` (Input-Kontext)

**Response:** `201` mit `ChainRunResponse`

### `GET /api/v1/chains/runs`

Alle Chain-Runs (paginiert).

**Query-Parameter:** `page`, `size`, `status`

**Response:** `PageResponse<ChainRunResponse>`

### `GET /api/v1/chains/{id}/runs`

Runs einer bestimmten Chain.

**Response:** `ChainRunResponse[]`

### `GET /api/v1/chains/{id}/runs/{runId}`

Einzelnen Chain-Run abrufen.

**Response:** `ChainRunResponse`

### `POST /api/v1/chains/runs/{runId}/cancel`

Chain-Run abbrechen.

**Response:** `200 OK`

---

## TODOs

### `GET /api/v1/todos`

TODOs auflisten (paginiert, filterbar).

**Query-Parameter:**

| Parameter | Typ | Beschreibung |
|---|---|---|
| `page` | int | Seite (Standard: 0) |
| `size` | int | Einträge pro Seite (Standard: 50) |
| `projectId` | String | Filter nach Projekt |
| `status` | String[] | Filter nach Status: `OPEN`, `IN_PROGRESS`, `DONE`, `CANCELLED` |
| `priority` | String | Filter nach Priorität: `HIGH`, `MEDIUM`, `LOW` |
| `source` | String | Filter nach Quelle: `USER`, `AI`, `WORKFLOW` |
| `tag` | String | Filter nach Tag |
| `q` | String | Volltextsuche |

**Response:** `PageResponse<TodoResponse>`

### `POST /api/v1/todos`

Neues TODO erstellen.

**Request:**

```json
{
  "title": "Kurzer Titel im Imperativ",
  "description": "Markdown mit Kontext",
  "priority": "HIGH",
  "tags": ["backend", "bug"],
  "projectId": "uuid",
  "source": "AI",
  "sourceAgent": "claude-code",
  "sessionId": "shell-session-id"
}
```

**Response:** `201` mit `TodoResponse`

### `GET /api/v1/todos/{id}`

Einzelnes TODO abrufen.

**Response:** `TodoResponse`

### `PUT /api/v1/todos/{id}`

TODO vollständig aktualisieren.

**Response:** `TodoResponse`

### `PATCH /api/v1/todos/{id}/status`

Status eines TODOs ändern.

**Request:**

```json
{
  "status": "IN_PROGRESS",
  "sessionId": "optional-session-id"
}
```

**Response:** `TodoResponse`

### `DELETE /api/v1/todos/{id}`

TODO löschen.

**Response:** `204 No Content`

### `GET /api/v1/todos/count`

Anzahl TODOs nach Status.

**Response:** `TodoCountResponse`

---

## Workflow Memory

### `GET /api/v1/workflows/{workflowId}/memory`

Alle Memory-Einträge eines Workflows.

**Response:** `WorkflowMemoryResponse[]`

### `POST /api/v1/workflows/{workflowId}/memory/extract`

Wissen aus Runs extrahieren.

**Request:**

```json
{
  "runIds": ["run-uuid-1", "run-uuid-2"],
  "provider": "CLAUDE"
}
```

**Response:** `201` mit `WorkflowMemoryResponse`

### `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}`

Memory-Eintrag aktualisieren.

**Request:** `{ "title": "...", "content": "..." }`

**Response:** `WorkflowMemoryResponse`

### `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}/activate`

Memory-Eintrag aktivieren (wird in nachfolgende Runs injiziert).

**Response:** `200 OK`

### `DELETE /api/v1/workflows/{workflowId}/memory/{memoryId}`

Memory-Eintrag löschen.

**Response:** `204 No Content`

---

## Activity Log

### `GET /api/v1/activity-log`

Activity-Log auflisten (paginiert, filterbar).

**Query-Parameter:** `page`, `size`, `level` (`INFO`, `WARN`, `ERROR`, `DEBUG`), `category`, `workflowId`

**Response:** `PageResponse<ActivityLogResponse>`

---

## Staging (Stable-Umgebung)

### `GET /api/v1/staging/status`

Status der Stable-Umgebung.

**Response:** `StagingStatusResponse` (inkl. `stableVersion`, `stableBuildTimestamp`, Health-Status)

### `POST /api/v1/staging/start`

Stable-Container starten.

**Response:** `200 OK`

### `POST /api/v1/staging/stop`

Stable-Container stoppen.

**Response:** `200 OK`

### `POST /api/v1/staging/promote`

Neuen Snapshot bauen und als Stable deployen.

**Response:** `202 Accepted`

### `POST /api/v1/staging/promote/cancel`

Laufenden Promote-Vorgang abbrechen.

**Response:** `200 OK`

### `GET /api/v1/staging/sync-preview`

Vorschau der Sync-Operationen (was würde von Dev nach Stable übertragen?).

**Response:** `SyncPreview`

### `POST /api/v1/staging/sync-definitions`

Definitionen von Dev nach Stable übertragen (Projekte, Workflows, Skills).

**Request (optional):** `SyncDefinitionsRequest` mit optionalen Filtern

**Response:** `SyncResult`

---

## Testing (Test-Instanz)

### `GET /api/v1/testing/status`

Status der Test-Instanz (Port 8090).

**Response:** `TestingStatusResponse`

### `POST /api/v1/testing/start`

Test-Container starten.

**Response:** `200 OK`

### `POST /api/v1/testing/stop`

Test-Container stoppen.

**Response:** `200 OK`

### `POST /api/v1/testing/reset`

Datenbank der Test-Instanz zurücksetzen.

**Response:** `200 OK`

### `GET /api/v1/testing/sync-preview`

Vorschau der Sync-Operationen (Dev → Test).

**Response:** `SyncPreview`

### `POST /api/v1/testing/sync-definitions`

Definitionen von Dev nach Test übertragen.

**Response:** `SyncResult`

### `POST /api/v1/testing/report-result`

Test-Ergebnis von der Test-Instanz zurückmelden.

**Request:** `{ "workflowName": "...", "projectName": "...", "status": "PASSED|FAILED" }`

**Response:** `200 OK`

### `POST /api/v1/testing/pull-changes`

Änderungen von Dev in die Test-Instanz ziehen.

**Response:** `SyncResult`

---

## Conversions

### `GET /api/v1/conversions/runs/{runId}/suggest`

Konvertierungsvorschlag für einen Agentic-Run generieren.

**Response:** `WorkflowSuggestion` (Workflow-Struktur als Vorschlag)

### `POST /api/v1/conversions/runs/{runId}/create-workflow`

Workflow aus einem Agentic-Run erstellen.

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Konvertierter Workflow"
}
```

**Response:** `WorkflowResponse`

---

## Workflow Pool

### `GET /api/v1/pool`

Alle verfügbaren Pool-Workflows auflisten.

**Response:** `PoolWorkflowEntry[]`

### `GET /api/v1/pool/{id}`

Einzelnen Pool-Workflow abrufen.

**Response:** `PoolWorkflowEntry`

### `POST /api/v1/pool/{id}/install`

Pool-Workflow in ein Projekt installieren.

**Request:**

```json
{
  "projectId": "uuid"
}
```

**Response:** `201` mit `{ "workflowId": "uuid", "workflowName": "..." }`

---

## Workflow Factory

### `GET /api/v1/factory/topics`

Verfügbare Topic-Kategorien abrufen.

**Response:** `Array<{ "id": "...", "label": "..." }>`

### `POST /api/v1/factory/generate`

Workflow aus Topic generieren (Vorschau, ohne Test-Ausführung).

**Request:** `WorkflowFactoryRequest`

**Response:** `201` mit `WorkflowResponse`

### `POST /api/v1/factory/run`

Vollständige Factory-Pipeline: Generieren → Test-Instanz → Verifizieren → optional Promoten.

**Request:** `WorkflowFactoryRequest`

**Response:** `WorkflowFactoryResult`

### `POST /api/v1/factory/verify/{id}`

Bestehenden Workflow auf der Test-Instanz verifizieren.

**Response:** `WorkflowFactoryResult`

### `POST /api/v1/factory/promote/{id}`

Verifizierten Workflow als Template promoten.

**Response:** `WorkflowFactoryResult`

---

## Wishes

### `GET /api/v1/wishes`

Alle Wishes auflisten.

**Response:** `WishResponse[]`

### `POST /api/v1/wishes`

Neuen Wunsch erstellen und asynchron erfüllen.

**Request:**

```json
{
  "text": "Erstelle einen Workflow, der täglich die neuesten KI-News zusammenfasst",
  "projectId": "uuid",
  "maxIterations": 5,
  "referenceWorkflowIds": ["uuid"]
}
```

**Response:** `201` mit `WishResponse`

### `GET /api/v1/wishes/{id}`

Einzelnen Wish abrufen.

**Response:** `WishResponse`

### `POST /api/v1/wishes/{id}/cancel`

Wish abbrechen.

**Response:** `200 OK`

### `POST /api/v1/wishes/{id}/respond`

Feedback zur aktuellen Iteration geben.

**Request:** `WishRespondRequest`

**Response:** `WishResponse`

### `POST /api/v1/wishes/{id}/crystallize`

Erfolgreichen Wish als Template kristallisieren.

**Response:** `{ "templateId": "uuid" }`

### `DELETE /api/v1/wishes/{id}`

Wish löschen.

**Response:** `204 No Content`

---

## Docs

### `GET /api/v1/docs`

Verfügbare Dokumentationsdateien auflisten.

**Response:** `Array<{ "name": "architektur.md", "label": "Architektur" }>`

### `GET /api/v1/docs/{name}`

Inhalt einer Dokumentationsdatei abrufen (nur `.md`-Dateien).

**Response:** `{ "content": "# Architektur\n..." }`

---

## WebSocket

### Verbindung

```
Endpunkt: ws://localhost:9090/ws (SockJS)
Protokoll: STOMP
```

### Topics

| Topic | Beschreibung |
|---|---|
| `/topic/executions/{runId}/logs` | Workflow-Ausführungs-Logs |
| `/topic/chains/{chainRunId}/status` | Chain-Ausführungs-Status-Updates |

### Nachrichtenformat (Execution-Logs)

```json
{
  "timestamp": "2025-01-15T10:00:05Z",
  "level": "INFO",
  "message": "Executing step 1: Review code..."
}
```

Level: `INFO`, `WARN`, `ERROR`, `DEBUG`

### Terminal-WebSocket

```
Endpunkt: ws://localhost:9090/ws/terminal
```

Für Echtzeit-Container-Zugriff (interaktive Shell).
