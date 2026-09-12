> *Zuletzt aktualisiert: [auto-generated]*

# API-Referenz

> **Scope:** Vollständige interne REST-API-Referenz für MySuricates. Die kuratierte Kurzreferenz für externe Agents ist [AGENTS.md](../AGENTS.md).

## Allgemein

- **Basis-URL:** `http://localhost:9090/api/v1`
- **Content-Type:** `application/json`
- **Authentifizierung:** Header `X-API-Key: <key>` (wenn `automation.security.enabled=true`)

### Fehlerformat

Alle Fehler folgen diesem Format:

```json
{
  "code": "INTERNAL_ERROR",
  "error": "Fehlermeldung",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

Das Feld `details` wird nur bei Bedarf mitgeliefert (`@JsonInclude(NON_NULL)`).

**Error-Codes:**

| Code | HTTP-Status |
|---|---|
| `RESOURCE_NOT_FOUND` | 404 |
| `VALIDATION_FAILED` | 400 |
| `INVALID_ARGUMENT` | 400 |
| `WORKFLOW_EXECUTION_FAILED` | 500 |
| `CONTAINER_ERROR` | 500 |
| `STAGING_ERROR` | 500 |
| `STATE_CONFLICT` | 409 |
| `NO_TASK_TARGET` | 400 |
| `PROVIDER_NOT_CONNECTED` | 409 |
| `PROVIDER_NOT_TASK_CAPABLE` | 400 |
| `SERVICE_UNAVAILABLE` | 503 |
| `TOKEN_BUDGET_EXCEEDED` | 429 |
| `OPERATION_TIMEOUT` | 504 |
| `INTERNAL_ERROR` | 500 |

Bei Validierungsfehlern (`VALIDATION_FAILED`, 400) zusätzlich:

```json
{
  "code": "VALIDATION_FAILED",
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
| 202 | Akzeptiert (asynchrone Verarbeitung) |
| 204 | Gelöscht (kein Body) |
| 400 | Validierungsfehler |
| 404 | Ressource nicht gefunden |
| 409 | Konflikt (ungültiger Zustand) |
| 429 | Rate Limit überschritten |
| 500 | Interner Serverfehler |
| 503 | Service nicht verfügbar (z.B. Test-Instanz nicht gesund) |
| 504 | Operation Timeout |

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
    "subtitle": null,
    "description": null,
    "type": "CODE",
    "path": "/workspace/projekt",
    "config": {},
    "aiContext": null,
    "status": "STOPPED",
    "container": null,
    "archivedAt": null,
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
| `subtitle` | String | Nein | |
| `description` | String | Nein | |
| `type` | Enum | Ja | `CODE`, `CONTENT`, `RESEARCH`, `BUSINESS`, `PERSONAL` |
| `path` | String | Ja | Pfad im Container |
| `config` | Object | Nein | Freies JSON |
| `aiContext` | String | Nein | KI-Kontext für das Projekt |

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
  "subtitle": "Untertitel",
  "description": "Beschreibung",
  "type": "RESEARCH",
  "path": "/workspace/neu",
  "config": {},
  "aiContext": "..."
}
```

Alle Felder sind optional; nur übergebene Felder werden aktualisiert.

**Response:** `ProjectResponse`

### `DELETE /api/v1/projects/{id}`

Projekt löschen (inkl. Container und Workflows).

**Response:** `204 No Content`

### `GET /api/v1/projects/archived`

Archivierte Projekte auflisten.

**Response:** `ProjectResponse[]`

### `POST /api/v1/projects/{id}/restore`

Archiviertes Projekt wiederherstellen.

**Response:** `ProjectResponse`

### `POST /api/v1/projects/{id}/start`

Docker-Container für das Projekt starten.

**Response:** `ProjectResponse` (Status wechselt zu `STARTING` → `RUNNING`)

### `POST /api/v1/projects/{id}/stop`

Docker-Container stoppen.

**Response:** `ProjectResponse` (Status wechselt zu `STOPPED`)

### `GET /api/v1/projects/{id}/workflows`

Alle Workflows eines Projekts auflisten.

**Response:** `WorkflowResponse[]`

### `POST /api/v1/projects/{id}/generate-context`

KI-Kontext für das Projekt generieren (analysiert das Projekt-Verzeichnis).

**Response:** `{ "context": "..." }`

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
    "projectName": "Mein Projekt",
    "projectSubtitle": null,
    "projectDescription": null,
    "name": "Code Review",
    "description": "Automatisiertes Code Review",
    "icon": "🔍",
    "blocks": [
      { "type": "simple", "config": { "command": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
    ],
    "version": "1.0.0",
    "metadata": {},
    "activatedSkillIds": [],
    "activatedSkillNames": [],
    "runCount": 0,
    "instructions": null,
    "model": null,
    "provider": null,
    "templateId": null,
    "defaultContext": {},
    "testStatus": "UNTESTED",
    "lastTestedAt": null,
    "tags": [],
    "category": null,
    "tagsGeneratedAt": null,
    "tagsUserEdited": false,
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-15T10:00:00Z",
    "stableSyncedAt": null
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
    { "type": "simple", "config": { "command": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
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
| `instructions` | String | Nein |
| `model` | String | Nein |
| `provider` | String | Nein |
| `templateId` | String | Nein |
| `metadata` | Object | Nein |
| `defaultContext` | Object | Nein |
| `version` | String | Nein |

**BlockDefinition-Felder:**

| Feld | Typ | Pflicht | Beschreibung |
|---|---|---|---|
| `type` | String | Ja | Block-Typ (simple, shell, agentic, ...) |
| `name` | String | Nein | Menschenlesbarer Name für `{{ block.<name>.output }}`-Referenzen |
| `config` | Object | Ja | Typ-spezifische Konfiguration |
| `requiredCapabilities` | String[] | Nein | Benoetigte Capabilities |
| `skillIds` | String[] | Nein | Skill-IDs die zusaetzlich zu Workflow-Skills fuer diesen Block aktiviert werden (additiv) |

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
    { "type": "simple", "config": { "command": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
  ],
  "instructions": null,
  "model": null,
  "version": "1.0.0",
  "defaultContext": {},
  "metadata": {},
  "exportedAt": "2025-02-22T14:00:00Z"
}
```

### `GET /api/v1/workflows/{id}/export/bpmn`

Workflow als BPMN 2.0 XML exportieren (Camunda-Modeler-kompatibel).

**Response:** Datei-Download (`.bpmn`, `Content-Disposition: attachment`)

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
    { "type": "simple", "config": { "command": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
  ],
  "instructions": null,
  "model": null,
  "updateIfExists": false
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `projectId` | String | Nein (Fallback: "Sammlung"-Projekt) |
| `name` | String | Ja |
| `description` | String | Nein |
| `icon` | String | Nein |
| `blocks` | BlockDefinition[] | Nein |
| `instructions` | String | Nein |
| `model` | String | Nein |
| `updateIfExists` | Boolean | Nein (Standard: `false`) |

Wenn `updateIfExists=true` und ein Workflow gleichen Namens im Zielprojekt existiert, wird dieser aktualisiert statt neu erstellt.

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
  "workflowName": "Code Review",
  "projectName": "Mein Projekt",
  "projectPath": "/workspace/projekt",
  "status": "RUNNING",
  "steps": [],
  "startedAt": "2025-01-15T10:00:00Z",
  "completedAt": null,
  "durationMs": null,
  "userRating": null,
  "error": {},
  "metadata": {},
  "inputContext": {},
  "triggeredByScheduleId": null
}
```

### `GET /api/v1/workflows/{id}/runs`

Alle Runs eines Workflows (absteigend nach Startzeit, paginiert).

**Query-Parameter:** `page` (Standard: 0), `size` (Standard: 50)

**Response:** `PageResponse<WorkflowRunResponse>`

### `GET /api/v1/workflows/{id}/runs/{runId}`

Einzelnen Run abrufen.

**Response:** `WorkflowRunResponse`

### `POST /api/v1/workflows/{id}/optimize`

Optimierungsvorschlag für einen Workflow generieren (KI-basiert).

**Response:** `Map<String, Object>` mit Optimierungsvorschlag

### `GET /api/v1/workflows/{id}/similar`

Ähnliche Workflows finden.

**Query-Parameter:** `limit` (Standard: 5, Max: 20)

**Response:** `List<Map<String, Object>>`

### `POST /api/v1/workflows/{id}/repair`

Fehlgeschlagenen Workflow auf der Test-Instanz reparieren. Setzt voraus, dass der Workflow fehlgeschlagene Runs hat und die Test-Instanz gesund ist.

**Response:** `202 Accepted` mit `{ "status": "repair_started", "workflowId": "...", "workflowName": "..." }`

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

### `GET /api/v1/workflows/{id}/versions`

Alle Versionen eines Workflows auflisten (paginiert).

**Query-Parameter:** `page` (Standard: 0), `size` (Standard: 20)

**Response:** `PageResponse<WorkflowVersionResponse>`

```json
{
  "content": [
    {
      "id": "version-uuid",
      "workflowId": "workflow-uuid",
      "versionNumber": 3,
      "snapshot": { ... },
      "changeSource": "USER",
      "changeMessage": "Prompt angepasst",
      "createdAt": "2025-01-15T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "totalPages": 1
}
```

### `GET /api/v1/workflows/{id}/versions/{versionId}`

Einzelne Version abrufen.

**Response:** `WorkflowVersionResponse`

### `POST /api/v1/workflows/{id}/versions/{versionId}/restore`

Workflow auf eine frühere Version zurücksetzen.

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
    "qualityStatus": null,
    "verifications": [],
    "version": "1.0.0",
    "createdAt": "2025-01-15T10:00:00Z",
    "updatedAt": "2025-01-15T10:00:00Z"
  }
]
```

`verifications` enthält pro Projekt, in dem das Template verifiziert wurde, ein Objekt mit `projectId`, `projectName`, `lastRunId`, `verifiedAt`.

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

### `GET /api/v1/executions`

Alle Ausführungen auflisten (paginiert, filterbar).

**Query-Parameter:**

| Parameter | Typ | Beschreibung |
|---|---|---|
| `page` | int | Seite (Standard: 0) |
| `size` | int | Einträge pro Seite (Standard: 50) |
| `status` | String | Filter nach Status: `PENDING`, `RUNNING`, `PAUSED`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`, `CANCELLED`, `INTERRUPTED` |
| `workflowId` | String | Filter nach Workflow |
| `projectId` | String | Filter nach Projekt |
| `search` | String | Volltextsuche |

**Response:** `PageResponse<WorkflowRunResponse>`

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

### `POST /api/v1/executions/{id}/retry`

Fehlgeschlagene Ausführung erneut starten (erstellt einen neuen Run mit gleichem Input-Kontext).

**Response:** `WorkflowRunResponse`

### `POST /api/v1/executions/{id}/resume-from-step`

Unterbrochene Ausführung ab dem fehlgeschlagenen Step fortsetzen.

**Response:** `WorkflowRunResponse`

### `PATCH /api/v1/executions/{id}/notes`

Notizen einer Ausführung aktualisieren.

**Request:**

```json
{
  "notes": "Freitext-Notiz zum Run"
}
```

**Response:** `WorkflowRunResponse`

### `POST /api/v1/executions/{id}/approve`

Approval-Gate oder Human-Input beantworten.

**Request:**

```json
{
  "answer": "Yes"
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `answer` | String | Ja |

**Hinweis:** Das Feld heißt `answer`, nicht `approved`.

**Response:** `200 OK`

### `GET /api/v1/executions/{id}/approval`

Prüfen ob ein Approval oder Human-Input ansteht.

**Response (kein Approval anstehend):**

```json
{
  "pending": false
}
```

**Response (Approval anstehend):**

```json
{
  "pending": true,
  "blockId": "block-id",
  "question": "Soll fortgefahren werden?",
  "options": ["Yes", "No"],
  "stepIndex": 2,
  "type": "approval"
}
```

**Response (Human-Input anstehend):**

```json
{
  "pending": true,
  "blockId": "block-id",
  "question": "Bitte Dateiname eingeben:",
  "options": [],
  "stepIndex": 3,
  "type": "human-input",
  "inputType": "text",
  "defaultValue": "",
  "variableName": "filename",
  "required": true,
  "validationPattern": null
}
```

### `POST /api/v1/executions/{id}/todos/approve`

Auto-generierte TODOs eines Runs genehmigen oder ablehnen.

**Request:**

```json
{
  "approved": true
}
```

**Response:** `200 OK`

### `POST /api/v1/executions/{id}/command`

Ad-hoc-Kommando im Kontext einer laufenden Ausführung senden.

**Request:**

```json
{
  "command": "ls -la"
}
```

**Response:**

```json
{
  "output": "..."
}
```

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
        "async": false,
        "writes": false
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
    "projectName": "Mein Projekt",
    "chainId": null,
    "chainName": null,
    "targetType": "WORKFLOW",
    "cronExpression": "0 0 2 * * ?",
    "scheduleType": "CRON",
    "scheduledAt": null,
    "delayExpression": null,
    "enabled": true,
    "description": "Nächtlicher Build",
    "inputContext": {},
    "lastRunAt": null,
    "lastRunStatus": null,
    "nextRunAt": "2025-01-16T02:00:00Z",
    "endDate": null,
    "maxRuns": 0,
    "runCount": 0,
    "conflictBehavior": "SKIP",
    "catchUpOnResume": false,
    "createdAt": "2025-01-15T10:00:00Z"
  }
]
```

### `GET /api/v1/schedules/remaining-today`

Verbleibende geplante Ausführungen für heute.

**Response:** `UpcomingScheduleResponse`

### `GET /api/v1/schedules/remaining-this-week`

Verbleibende geplante Ausführungen für diese Woche.

**Response:** `UpcomingScheduleResponse`

### `GET /api/v1/schedules/{id}`

Einzelnen Schedule abrufen.

**Response:** `ScheduledWorkflowResponse`

### `GET /api/v1/schedules/{id}/runs`

Runs die durch diesen Schedule ausgelöst wurden.

**Response:**

```json
{
  "workflowRuns": [...],
  "chainRuns": [...]
}
```

### `GET /api/v1/schedules/workflow/{workflowId}`

Schedules eines bestimmten Workflows.

**Response:** `ScheduledWorkflowResponse[]`

### `GET /api/v1/schedules/chain/{chainId}`

Schedules einer bestimmten Chain.

**Response:** `ScheduledWorkflowResponse[]`

### `GET /api/v1/schedules/calendar`

Kalender-Daten: geplante Ausführungen und vergangene Runs in einem Zeitraum.

**Query-Parameter:**

| Parameter | Typ | Pflicht |
|---|---|---|
| `from` | Instant | Ja |
| `to` | Instant | Ja |

**Response:** `CalendarDataResponse` mit `upcoming` und `pastRuns`

### `POST /api/v1/schedules/preview`

Vorschau der nächsten 5 Ausführungszeitpunkte für einen Cron-Ausdruck.

**Request:**

```json
{
  "cronExpression": "0 0 2 * * ?"
}
```

**Response:** `{ "runs": ["2025-01-16T02:00:00Z", ...] }`

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
| `workflowId` | String | Ja (wenn `targetType` = `WORKFLOW`) |
| `chainId` | String | Ja (wenn `targetType` = `CHAIN`) |
| `targetType` | String | Nein (Standard: `WORKFLOW`, Werte: `WORKFLOW`, `CHAIN`) |
| `cronExpression` | String | Ja (Spring Cron, 6 Felder) |
| `description` | String | Nein |
| `inputContext` | Object | Nein |
| `scheduleType` | String | Nein (Standard: `CRON`, Werte: `CRON`, `ONCE`, `DELAYED`) |
| `scheduledAt` | Instant | Nein (für `ONCE`-Schedules) |
| `delayExpression` | String | Nein (für `DELAYED`-Schedules, z.B. `"2h"`, `"30m"`) |
| `endDate` | Instant | Nein (optionales Ende für `CRON`-Schedules) |
| `maxRuns` | Integer | Nein (max. Ausführungen, 0 = unbegrenzt) |
| `conflictBehavior` | String | Nein (Standard: `SKIP`, Werte: `SKIP`, `OVERLAP`) |
| `catchUpOnResume` | boolean | Nein (Standard: `false`) |

**Response:** `201` mit `ScheduledWorkflowResponse`

### `PUT /api/v1/schedules/{id}`

Schedule aktualisieren.

**Request:**

```json
{
  "cronExpression": "0 30 1 * * ?",
  "description": "Neuer Zeitplan",
  "inputContext": {},
  "endDate": null,
  "maxRuns": 10
}
```

Alle Felder sind optional; nur übergebene Felder werden aktualisiert.

**Response:** `ScheduledWorkflowResponse`

### `POST /api/v1/schedules/{id}/enable`

Schedule aktivieren.

**Response:** `ScheduledWorkflowResponse`

### `POST /api/v1/schedules/{id}/disable`

Schedule deaktivieren.

**Response:** `ScheduledWorkflowResponse`

### `DELETE /api/v1/schedules/{id}`

Schedule löschen.

**Response:** `204 No Content`

### `GET /api/v1/schedules/maintenance`

Wartungsmodus-Status abfragen.

**Response:** `{ "enabled": false }`

### `POST /api/v1/schedules/maintenance`

Wartungsmodus ein-/ausschalten. Im Wartungsmodus werden keine geplanten Ausführungen gestartet.

**Request:**

```json
{
  "enabled": true
}
```

**Response:** `{ "enabled": true }`

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

## Completion Triggers

Completion-Trigger lösen automatisch einen Ziel-Workflow aus, wenn ein Quell-Workflow abgeschlossen wird.

### `GET /api/v1/completion-triggers`

Alle Completion-Trigger auflisten.

**Response:** `CompletionTriggerResponse[]`

### `GET /api/v1/completion-triggers/{id}`

Einzelnen Trigger abrufen.

**Response:** `CompletionTriggerResponse`

### `GET /api/v1/completion-triggers/workflow/{workflowId}/as-source`

Trigger abrufen, bei denen der angegebene Workflow als Quelle fungiert.

**Response:** `CompletionTriggerResponse[]`

### `GET /api/v1/completion-triggers/workflow/{workflowId}/as-target`

Trigger abrufen, bei denen der angegebene Workflow als Ziel fungiert.

**Response:** `CompletionTriggerResponse[]`

### `POST /api/v1/completion-triggers`

Neuen Completion-Trigger erstellen.

**Request:**

```json
{
  "sourceWorkflowId": "uuid",
  "targetWorkflowId": "uuid",
  "triggerOnSuccess": true,
  "triggerOnFailure": false,
  "triggerAlways": false,
  "description": "Nach erfolgreichem Build: Deployment starten",
  "contextVariables": {}
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `sourceWorkflowId` | String | Ja |
| `targetWorkflowId` | String | Ja |
| `triggerOnSuccess` | boolean | Nein |
| `triggerOnFailure` | boolean | Nein |
| `triggerAlways` | boolean | Nein |
| `description` | String | Nein |
| `contextVariables` | Object | Nein |

**Response:** `201` mit `CompletionTriggerResponse`

**CompletionTriggerResponse-Felder:** `id`, `sourceWorkflowId`, `sourceWorkflowName`, `targetWorkflowId`, `targetWorkflowName`, `enabled`, `triggerOnSuccess`, `triggerOnFailure`, `triggerAlways`, `description`, `contextVariables`, `lastTriggeredAt`, `triggerCount`, `createdAt`

### `POST /api/v1/completion-triggers/{id}/enable`

Trigger aktivieren.

**Response:** `CompletionTriggerResponse`

### `POST /api/v1/completion-triggers/{id}/disable`

Trigger deaktivieren.

**Response:** `CompletionTriggerResponse`

### `DELETE /api/v1/completion-triggers/{id}`

Trigger löschen.

**Response:** `204 No Content`

---

## Integrations (OAuth-Provider)

Plugin-System für externe Dienste (Google Calendar, Gmail, Jira, Confluence, Trello). Jeder Provider wird automatisch erkannt und bietet OAuth-Verbindung + Actions.

### `GET /api/v1/integrations`

Liste aller Provider mit Verbindungsstatus, Actions und Account-Info.

**Response:** `200 OK`
```json
[
  {
    "providerId": "google-calendar",
    "displayName": "Google Calendar",
    "icon": "calendar",
    "description": "...",
    "status": "CONNECTED",
    "configured": true,
    "requiresCredentials": true,
    "accountEmail": "user@gmail.com",
    "accountName": "Max Mustermann",
    "connectedSince": "2026-04-01T10:00:00Z",
    "scopes": "https://www.googleapis.com/auth/calendar",
    "optionalScopes": [
      { "scope": "https://www.googleapis.com/auth/calendar.events", "label": "Events verwalten", "granted": true }
    ],
    "supportsTaskExport": false,
    "actions": [
      { "name": "list_events", "description": "...", "inputSchema": {...}, "available": true }
    ]
  }
]
```

### `GET /api/v1/integrations/{providerId}`

Einzelnen Provider mit Details abrufen.

### `GET /api/v1/integrations/{providerId}/connect`

OAuth-Autorisierungs-URL generieren.

**Query-Parameter:** `scopes` (optional, Liste zusätzlicher OAuth-Scopes)

**Response:** `200 OK`
```json
{ "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?..." }
```

### `GET /api/v1/integrations/{providerId}/callback`

OAuth-Callback (öffentlich, kein API-Key). Tauscht Authorization-Code gegen Tokens und schließt das Popup-Fenster via `postMessage`.

### `POST /api/v1/integrations/{providerId}/credentials`

Client-Credentials (Client ID + Secret) speichern. Werden AES-256-GCM-verschlüsselt in der DB abgelegt.

**Body:**
```json
{ "clientId": "...", "clientSecret": "..." }
```

**Response:** `IntegrationProviderResponse`

### `DELETE /api/v1/integrations/{providerId}/connection`

OAuth-Verbindung trennen. Löscht gespeicherte Tokens.

**Response:** `204 No Content`

### `DELETE /api/v1/integrations/{providerId}/credentials`

Client-Credentials löschen.

**Response:** `204 No Content`

### `GET /api/v1/integrations/{providerId}/actions`

Liste aller verfügbaren Actions des Providers. `available` ist `false` wenn benötigte OAuth-Scopes fehlen.

### `POST /api/v1/integrations/{providerId}/actions/{action}`

Action ausführen. Body enthält die Action-spezifischen Parameter.

**Body (Beispiel Google Calendar `create_event`):**
```json
{
  "summary": "Meeting",
  "startDateTime": "2026-04-15T10:00:00+02:00",
  "endDateTime": "2026-04-15T11:00:00+02:00"
}
```

### `GET /api/v1/integrations/{providerId}/task-targets`

Verfügbare Task-Export-Ziele (z.B. Jira-Boards, Trello-Listen) auflisten.

**Response:** `List<TaskTarget>`

---

## Onboarding-Profile

AI-Kontext-Templates für Projekt-Onboarding. 5 Built-in-Profile (Developer, Research, Content, Ops, Base) + benutzerdefinierte Profile.

### `GET /api/v1/projects/{id}/onboarding/status`

Onboarding-Status eines Projekts prüfen (aktive Profile, generierte Datei).

### `POST /api/v1/projects/{id}/onboarding/enable`

Onboarding für ein Projekt aktivieren. Generiert die Onboarding-Datei aus den ausgewählten Profilen.

**Body:**
```json
{ "format": "md", "profileIds": ["profile-uuid-1", "profile-uuid-2"] }
```

**Response:** `OnboardingStatusResponse`

### `POST /api/v1/projects/{id}/onboarding/disable`

Onboarding für ein Projekt deaktivieren.

**Response:** `OnboardingStatusResponse`

### `POST /api/v1/projects/{id}/onboarding/refresh`

Onboarding-Datei neu generieren.

**Response:** `OnboardingStatusResponse`

### `GET /api/v1/projects/{id}/onboarding/preview`

Vorschau der generierten Onboarding-Datei.

**Response:** `{ "content": "..." }`

### `GET /api/v1/onboarding-profiles`

Alle Onboarding-Profile auflisten.

**Response:** `OnboardingProfileResponse[]`

### `GET /api/v1/onboarding-profiles/{id}`

Einzelnes Profil abrufen.

**Response:** `OnboardingProfileResponse`

### `POST /api/v1/onboarding-profiles`

Neues Profil erstellen.

**Request:**

```json
{
  "key": "my-profile",
  "name": {"de": "Mein Profil", "en": "My Profile"},
  "description": {"de": "...", "en": "..."},
  "content": "Profilinhalt...",
  "type": "PROFILE"
}
```

| Feld | Typ | Pflicht | Werte |
|---|---|---|---|
| `key` | String | Ja | Pattern: `^[a-z0-9-]+$` |
| `name` | Map<String, String> | Ja | |
| `description` | Map<String, String> | Nein | |
| `content` | String | Ja | |
| `type` | Enum | Ja | `BASE`, `PROFILE` |

**Response:** `201` mit `OnboardingProfileResponse`

**OnboardingProfileResponse-Felder:** `id`, `key`, `name`, `description`, `content`, `type`, `builtIn`, `sortOrder`, `version`, `historyCount`, `createdAt`, `updatedAt`

### `PUT /api/v1/onboarding-profiles/{id}`

Profil-Content aktualisieren (erstellt automatisch einen History-Eintrag).

**Response:** `OnboardingProfileResponse`

### `DELETE /api/v1/onboarding-profiles/{id}`

Profil löschen.

**Response:** `204 No Content`

### `POST /api/v1/onboarding-profiles/{id}/reset`

Built-in-Profil auf Originalinhalt zurücksetzen.

**Response:** `OnboardingProfileResponse`

### `GET /api/v1/onboarding-profiles/{id}/history`

Änderungshistorie eines Profils abrufen.

**Response:** `OnboardingProfileHistoryResponse[]`

---

## Todo-Trigger (Self-Improvement Loop)

Todo-Trigger starten automatisch Fix-Workflows wenn TODOs erstellt werden. Schliesst den Self-Improvement-Loop: Audit → TODO → Fix → Verify → Close.

### `GET /api/v1/todo-triggers`

Alle Todo-Trigger auflisten.

**Response:** `TodoTriggerResponse[]`

### `GET /api/v1/todo-triggers/{id}`

Einzelnen Todo-Trigger abrufen.

**Response:** `TodoTriggerResponse`

### `GET /api/v1/todo-triggers/workflow/{workflowId}`

Todo-Trigger abrufen, die den angegebenen Workflow als Ziel haben.

**Response:** `TodoTriggerResponse[]`

### `POST /api/v1/todo-triggers`

Neuen Todo-Trigger erstellen.

**Request:**

```json
{
  "tagPattern": "backend,testing",
  "targetWorkflowId": "<workflow-id>",
  "projectId": null,
  "sourceFilter": "WORKFLOW",
  "minPriority": "HIGH",
  "description": "Auto-Fix: fehlende Backend-Tests",
  "maxAttempts": 3,
  "autoClose": true,
  "contextTemplate": { "extra_key": "extra_value" }
}
```

| Feld | Pflicht | Beschreibung |
|---|---|---|
| `tagPattern` | Ja | Kommagetrennte Tags (AND-Logik). Alle Tags müssen im TODO vorhanden sein |
| `targetWorkflowId` | Ja | ID des Fix-Workflows der gestartet wird |
| `projectId` | Nein | Optional: nur TODOs in diesem Projekt matchen |
| `sourceFilter` | Nein | Optional: `"USER"`, `"AI"` oder `"WORKFLOW"` |
| `minPriority` | Nein | Optional: Minimum-Priorität (`"LOW"`, `"MEDIUM"`, `"HIGH"`, `"CRITICAL"`) |
| `description` | Nein | Menschenlesbare Beschreibung |
| `maxAttempts` | Nein | Max. Fix-Versuche pro TODO (Default: 3) |
| `autoClose` | Nein | TODO bei GREEN Gate automatisch schliessen (Default: true) |
| `processBacklog` | Nein | Bei Erstellung bestehende passende TODOs abarbeiten (Default: true) |
| `contextTemplate` | Nein | Statische Context-Variablen, gemerged mit TODO-Daten |

**Response:** `201` mit `TodoTriggerResponse`

**TodoTriggerResponse-Felder:** `id`, `tagPattern`, `projectId`, `sourceFilter`, `minPriority`, `targetWorkflowId`, `targetWorkflowName`, `contextTemplate`, `enabled`, `maxAttempts`, `autoClose`, `processBacklog`, `description`, `lastTriggeredAt`, `triggerCount`, `createdAt`

**Context-Variablen** die der Fix-Workflow automatisch erhält:

| Variable | Beschreibung |
|---|---|
| `todo_id` | TODO-UUID |
| `todo_title` | TODO-Titel |
| `todo_description` | TODO-Beschreibung (Markdown) |
| `todo_tags` | TODO-Tags als JSON-Array |
| `todo_priority` | `LOW`, `MEDIUM`, `HIGH` oder `CRITICAL` |
| `todo_project_id` | Projekt-UUID des TODOs |
| `todo_source` | `USER`, `AI` oder `WORKFLOW` |
| `todo_reference_type` | Referenz-Typ (z.B. `workflow_run`) |
| `todo_reference_id` | Referenz-ID (z.B. Workflow-ID) |
| `todo_trigger_id` | ID des auslösenden TodoTriggers |

### `POST /api/v1/todo-triggers/{id}/enable`

Trigger aktivieren.

**Response:** `TodoTriggerResponse`

### `POST /api/v1/todo-triggers/{id}/disable`

Trigger deaktivieren.

**Response:** `TodoTriggerResponse`

### `DELETE /api/v1/todo-triggers/{id}`

Trigger löschen.

**Response:** `204 No Content`

### Loop-Prevention

Fix-Workflows die durch einen TodoTrigger gestartet werden, setzen `todoTriggerRun: true` in der Run-Metadata. Dies verhindert:
- **AutoTodoService** überspringt TODO-Erstellung für diese Runs (kein Audit→TODO→Fix→TODO-Loop)
- **Max-Attempts**: Nach `maxAttempts` Fehlversuchen wird der Trigger für dieses TODO nicht mehr ausgelöst
- **Running-Check**: Wenn `lastFixRunId` noch RUNNING ist, wird kein zweiter Fix-Run gestartet

---

## Notifications

Benachrichtigungen bei Workflow-Abschluss (Webhook oder Desktop).

### `GET /api/v1/notifications`

Alle Benachrichtigungs-Konfigurationen auflisten.

**Response:** `NotificationConfigResponse[]`

### `GET /api/v1/notifications/{id}`

Einzelne Benachrichtigungs-Konfiguration abrufen.

**Response:** `NotificationConfigResponse`

### `POST /api/v1/notifications`

Neue Benachrichtigungs-Konfiguration erstellen.

**Request:**

```json
{
  "name": "Slack Deployment",
  "channelType": "WEBHOOK",
  "webhookUrl": "https://hooks.slack.com/...",
  "workflowId": null,
  "triggerOnSuccess": true,
  "triggerOnFailure": true,
  "triggerAlways": false,
  "messageTemplate": "Workflow {{workflow_name}} {{status}} ({{duration}})"
}
```

| Feld | Typ | Pflicht | Werte |
|---|---|---|---|
| `name` | String | Ja | |
| `channelType` | Enum | Ja | `WEBHOOK`, `DESKTOP` |
| `webhookUrl` | String | Nein (Pflicht bei `WEBHOOK`) | Slack/Discord/Teams Webhook-URL |
| `workflowId` | String | Nein | `null` = global (alle Workflows) |
| `triggerOnSuccess` | boolean | Nein | |
| `triggerOnFailure` | boolean | Nein | |
| `triggerAlways` | boolean | Nein | |
| `messageTemplate` | String | Nein | Unterstützt `{{workflow_name}}`, `{{status}}`, `{{duration}}`, `{{error_message}}` |

**Response:** `201` mit `NotificationConfigResponse`

**NotificationConfigResponse-Felder:** `id`, `name`, `enabled`, `channelType`, `webhookUrl`, `workflowId`, `triggerOnSuccess`, `triggerOnFailure`, `triggerAlways`, `messageTemplate`, `createdAt`

### `POST /api/v1/notifications/{id}/enable`

Benachrichtigung aktivieren.

**Response:** `NotificationConfigResponse`

### `POST /api/v1/notifications/{id}/disable`

Benachrichtigung deaktivieren.

**Response:** `NotificationConfigResponse`

### `DELETE /api/v1/notifications/{id}`

Benachrichtigungs-Konfiguration löschen.

**Response:** `204 No Content`

---

## System

### `GET /api/v1/system/health` (Öffentlich)

Health-Check.

**Response:**

```json
{
  "status": "UP",
  "timestamp": "2025-01-15T10:00:00Z",
  "docker": "CONNECTED",
  "components": {
    "database": { "status": "UP", "responseTimeMs": 2 },
    "docker": { "status": "UP" }
  }
}
```

`status` kann `UP`, `DEGRADED` (Docker nicht verfügbar) oder `DOWN` (Datenbank nicht verfügbar) sein.
`docker` kann `CONNECTED` oder `UNAVAILABLE` sein.

### `GET /api/v1/system/info` (Öffentlich)

Systeminformationen.

**Response:**

```json
{
  "name": "MySuricates",
  "version": "0.7.3",
  "buildType": "SNAPSHOT",
  "buildTimestamp": null,
  "java": "21.0.1",
  "tailscale": "DISABLED",
  "environment": "Dev"
}
```

### `GET /api/v1/system/auth-status`

Authentifizierungsstatus abfragen.

**Response:** `{ "securityEnabled": true }`

### `GET /api/v1/system/ai-providers`

Verfügbare KI-Provider und Standard-Provider abfragen.

**Response:**

```json
{
  "providers": ["CLAUDE", "PI", "CODEX"],
  "default": "CLAUDE"
}
```

### `GET /api/v1/system/capabilities`

System-Capabilities abrufen (API-Version, App-Version, verfügbare Routen und Block-Typen).

**Response:** `SystemCapabilitiesResponse`

### `GET /api/v1/system/ai-providers/PI/catalog`

Modell-Katalog des Pi-Providers abrufen.

**Response:** `PiCatalog`

### `POST /api/v1/system/client-errors`

Frontend-Fehler melden (für Logging).

**Request:**

```json
{
  "message": "TypeError: ...",
  "stack": "...",
  "source": "component",
  "url": "/workflows",
  "timestamp": "2025-01-15T10:00:00Z"
}
```

**Response:** `202 Accepted`

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

### `POST /api/v1/system/vacuum`

SQLite-Datenbank VACUUM ausführen.

**Response:** `Map<String, Object>`

### `GET /api/v1/system/backup`

Datenbank-Backup herunterladen.

**Response:** Datei-Download (SQLite-Backup)

### `POST /api/v1/system/backup`

Benanntes Backup erstellen.

**Query-Parameter:** `reason` (optional, String)

**Response:** `BackupMetadata` mit `filename`, `sizeBytes`, `createdAt`

### `GET /api/v1/system/backups`

Alle verfügbaren Backups auflisten.

**Response:** `BackupInfo[]`

### `GET /api/v1/system/backups/{filename}`

Bestimmtes Backup herunterladen.

**Response:** Datei-Download

### `GET /api/v1/system/settings/{key}`

Anwendungs-Einstellung abrufen.

**Erlaubte Keys:** `auto-todo-default`, `auto-repair-default`, `auto-repair-mode`, `auto-repair-max-retries`, `auto-repair-timeout-seconds`, `default-ai-provider`, `assistant-thinking-phrases`, `assistant-model`, `suggestions-model`, `suggestions-enabled`, `backup-retain-count`

**Response:** `{ "key": "...", "value": "..." }`

### `PUT /api/v1/system/settings/{key}`

Anwendungs-Einstellung setzen.

**Request:**

```json
{
  "value": "true"
}
```

**Response:** `200 OK`

### `GET /api/v1/system/execution-config`

Aktuelle Execution-Konfiguration abrufen (Timeouts, Token-Limits etc.). Enthält zusätzlich ein read-only-Feld `activeRuns` mit der Anzahl aktiver Runs.

**Erlaubte Keys:**

| Key | Bereich |
|---|---|
| `execution-max-concurrent` | 1–50 |
| `claude-max-concurrent-calls` | 1–20 |
| `claude-reserved-for-interactive` | 0–10 |
| `claude-timeout-seconds` | 30–3600 |
| `claude-inter-call-delay-ms` | 0–30000 |
| `chain-workflow-timeout-minutes` | 5–1440 |
| `rate-limit-enabled` | 0–1 |
| `rate-limit-requests-per-minute` | 10–10000 |
| `assistant-chat-timeout-seconds` | 30–900 |
| `task-command-timeout-seconds` | 10–3600 |
| `allow-extra-mounts` | 0–1 |
| `todo-hygiene-enabled` | 0–1 |
| `todo-stale-days` | 7–365 |
| `todo-hygiene-max-per-run` | 10–2000 |
| `todo-watchdog-enabled` | 0–1 |
| `todo-watchdog-stale-hours` | 1–720 |
| `assistant-max-actions-per-hour` | 1–500 |
| `scheduler-catch-up-on-resume` | 0–1 |
| `scheduler-resume-grace-minutes` | 1–60 |
| `summarization-timeout-seconds` | 30–600 |
| `factory-timeout-seconds` | 60–3600 |
| `wish-total-timeout-minutes` | 5–1440 |
| `wish-workflow-wait-minutes` | 1–120 |
| `wish-default-shell-timeout-seconds` | 30–3600 |
| `wish-default-agentic-total-timeout-seconds` | 60–86400 |

**Response:** `Map<String, Object>` mit aktuellen Einstellungen

### `PUT /api/v1/system/execution-config`

Execution-Konfiguration aktualisieren.

**Request:** `Map<String, String>` mit den zu ändernden Einstellungen (Keys wie oben, auch in camelCase erlaubt)

**Response:** `Map<String, Object>` mit aktualisierten Einstellungen

### `POST /api/v1/system/repair-failed`

Alle fehlgeschlagenen Workflows automatisch reparieren lassen. Setzt gesunde Test-Instanz voraus.

**Query-Parameter:** `force` (boolean, Standard: `false`)

**Response:** `202 Accepted` mit `{ "status": "repair_started", "workflowsQueued": 3, "force": false }`

### `POST /api/v1/system/open-path`

Datei oder Verzeichnis im nativen Dateimanager öffnen (Linux: `xdg-open`).

**Request:**

```json
{
  "path": "/home/user/projekt"
}
```

**Response:** `{ "status": "ok" }`

### `GET /api/v1/system/integration-snippet`

Markdown-Integration-Snippet für ein Projekt rendern (für AGENTS.md o.ä.).

**Query-Parameter:** `projectPath` (optional)

**Response:** `String` (Content-Type: `text/markdown`)

### `GET /api/v1/system/browse`

Verzeichnisinhalt durchsuchen (nur Unterverzeichnisse, keine versteckten Ordner).

**Query-Parameter:** `path` (String, optional, Standard: Home-Verzeichnis)

**Response:**

```json
{
  "current": "/home/user",
  "parent": "/home",
  "directories": [
    { "name": "Documents", "path": "/home/user/Documents" }
  ]
}
```

---

## Learning / Dashboard

### `GET /api/v1/learning/dashboard`

Dashboard-Daten mit Statistiken.

**Query-Parameter:** `days` (Standard: 30), `projectId` (optional)

**Response:**

```json
{
  "totalWorkflows": 12,
  "totalRuns": 156,
  "runsToday": 5,
  "successfulRuns": 140,
  "failedRuns": 16,
  "averageDurationMs": 45000,
  "successRate": 89.7,
  "totalCostUsd30d": 12.50,
  "totalTokens30d": 250000,
  "totalInputTokens": 180000,
  "totalOutputTokens": 70000,
  "topWorkflows": [
    { "workflowId": "uuid", "workflowName": "Code Review", "runCount": 42 }
  ],
  "recentRuns": [...],
  "timeSeries": [
    { "date": "2025-01-15", "count": 8, "successRate": 87.5, "failedCount": 1, "avgDurationMs": 32000, "totalCostUsd": 0.45, "totalTokens": 9500 }
  ],
  "providerStats": [
    { "provider": "CLAUDE", "runCount": 150, "totalTokens": 240000, "totalCostUsd": 11.0, "successRate": 90.0 }
  ],
  "modelStats": [
    { "model": "sonnet", "stepCount": 200, "totalTokens": 180000, "totalCostUsd": 8.5 }
  ],
  "gateStatusSummary": [
    { "status": "GREEN", "count": 10 }
  ],
  "failureReasons": [
    { "reason": "timeout", "count": 3 }
  ],
  "costByWorkflow": [
    { "workflowId": "uuid", "workflowName": "Code Review", "totalCostUsd": 5.0, "runCount": 42 }
  ],
  "blockTypeUsage": [
    { "blockType": "simple", "stepCount": 100, "failedCount": 2, "failureRate": 2.0 }
  ],
  "triggerDistribution": {
    "manual": 50, "scheduled": 80, "webhook": 10, "completionTrigger": 5, "todoTrigger": 11
  }
}
```

### `GET /api/v1/learning/platform-stats`

Plattform-weite Statistiken.

**Response:** `PlatformStatsResponse` mit `activeProjects`, `archivedProjects`, `totalWorkflows`, `workflowPoolSize`, `totalSkills`, `builtinSkills`, `customSkills`, `topSkillsByUsage`, `totalTemplates`, `builtinTemplates`, `customTemplates`, `activeSchedules`, `totalSchedules`, `activeWebhooks`, `totalWebhooks`, `chainStats`, `activeTodoTriggers`, `activeCompletionTriggers`, `activeMemoryEntries`, `todoStats`, `wishStats`, `assistantStats`

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

### `GET /api/v1/learning/workflows/{workflowId}/agentic-history`

Agentic-Strategie-Verlauf eines Workflows (wie sich die KI-Strategie über Runs hinweg entwickelt hat).

**Response:** `Array<Object>`

### `GET /api/v1/learning/workflows/{workflowId}/agentic-analytics`

Agentic-Block-Analyse: Iterationen, Token-Verbrauch, Erfolgsquote.

**Response:** `Map<String, Object>`

### `GET /api/v1/learning/workflows/{workflowId}/tool-patterns`

Tool-Nutzungsmuster eines Workflows (welche Tools wie oft genutzt werden).

**Response:** `Map<String, Object>`

### `GET /api/v1/learning/workflows/{workflowId}/execution-insights`

Ausführungs-Insights: Trends, Anomalien, Performance-Metriken.

**Response:** `Map<String, Object>`

### `GET /api/v1/learning/runs/{runId}/insights`

Detaillierte Insights für einen einzelnen Run.

**Response:** `Map<String, Object>`

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
  "stopOnFailure": true,
  "runCount": 0,
  "metadata": {}
}
```

`ChainEntryResponse` enthält zusätzlich: `workflowName`, `projectName`, `label`.

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

**Query-Parameter:** `page`, `size`, `status`, `search`

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

## Chain Templates

Vordefinierte Chain-Vorlagen, die als Chains in ein Projekt installiert werden können.

### `GET /api/v1/chain-templates`

Alle Chain-Templates auflisten.

**Response:** `ChainTemplateResponse[]`

```json
[
  {
    "id": "template-id",
    "name": {"de": "CI/CD Pipeline", "en": "CI/CD Pipeline"},
    "description": {"de": "Build, Test, Deploy Chain", "en": "Build, Test, Deploy Chain"},
    "icon": "🔄",
    "entries": [
      { "templateId": "workflow-template-id", "label": {"de": "Build", "en": "Build"} }
    ]
  }
]
```

### `GET /api/v1/chain-templates/{id}`

Einzelnes Chain-Template abrufen.

**Response:** `ChainTemplateResponse`

### `POST /api/v1/chain-templates/{id}/install`

Chain-Template in ein Projekt installieren (erstellt die Chain und alle referenzierten Workflows).

**Request:**

```json
{
  "projectId": "uuid",
  "chainName": "Meine CI/CD Pipeline"
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `projectId` | String | Ja |
| `chainName` | String | Nein |

**Response:** `201` mit `ChainInstallResponse`

```json
{
  "chainId": "uuid",
  "chainName": "CI/CD Pipeline",
  "workflowIds": ["uuid-1", "uuid-2"]
}
```

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
| `status` | String[] | Filter nach Status: `OPEN`, `IN_PROGRESS`, `DONE`, `BACKLOG`, `ARCHIVED` |
| `priority` | String | Filter nach Priorität: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` |
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
  "status": "OPEN",
  "priority": "HIGH",
  "tags": ["backend", "bug"],
  "projectId": "uuid",
  "linkedWorkflowId": null,
  "source": "AI",
  "sourceAgent": "claude-code",
  "sessionId": "shell-session-id",
  "workflowRunId": null,
  "referenceType": null,
  "referenceId": null,
  "dueDate": null
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `title` | String | Ja |
| `description` | String | Nein |
| `status` | Enum | Nein (Standard: `OPEN`) |
| `priority` | Enum | Nein |
| `tags` | String[] | Nein |
| `projectId` | String | Nein |
| `linkedWorkflowId` | String | Nein |
| `source` | Enum | Nein |
| `sourceAgent` | String | Nein |
| `sessionId` | String | Nein |
| `workflowRunId` | String | Nein |
| `referenceType` | String | Nein |
| `referenceId` | String | Nein |
| `dueDate` | Instant | Nein |

**Response:** `201` mit `TodoResponse`

**TodoResponse-Felder:** `id`, `title`, `description`, `status`, `priority`, `tags`, `projectId`, `projectName`, `linkedWorkflowId`, `linkedWorkflowName`, `source`, `sourceAgent`, `sessionId`, `workflowRunId`, `referenceType`, `referenceId`, `exportedProvider`, `exportedRef`, `exportedUrl`, `fixAttempts`, `lastFixRunId`, `resolutionState` (`IDLE`, `RUNNING`, `FAILED`, `SUCCEEDED`), `dueDate`, `createdAt`, `updatedAt`

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

### `POST /api/v1/todos/{id}/resolve`

TODO automatisch lösen (startet einen passenden Fix-Workflow).

**Response:** `WorkflowRunResponse`

### `POST /api/v1/todos/{id}/export`

TODO in ein externes System exportieren (z.B. Jira, Trello).

**Request (optional):**

```json
{
  "provider": "jira",
  "target": "board-id"
}
```

**Response:** `TodoExportResponse` mit `referenceType`, `referenceId`, `url`

### `DELETE /api/v1/todos/{id}`

TODO löschen.

**Response:** `204 No Content`

### `GET /api/v1/todos/count`

Anzahl TODOs nach Status.

**Response:** `TodoCountResponse` mit `open`, `inProgress`, `done`, `backlog`, `archived`

### `GET /api/v1/todos/overview`

TODO-Übersicht mit Projekt-Rollups und "Needs You"-Items.

**Response:** `TodoOverviewResponse` mit `projects` (List), `needsYou` (List), `generatedAt`, `staleThresholdDays`

### `POST /api/v1/todos/bulk-status`

Status mehrerer TODOs gleichzeitig ändern.

**Request:**

```json
{
  "ids": ["uuid-1", "uuid-2"],
  "status": "DONE"
}
```

**Response:** `{ "updated": 2 }`

### `POST /api/v1/todos/archive-stale`

Veraltete TODOs archivieren.

**Query-Parameter:** `projectId` (optional)

**Response:** `{ "archived": 5 }`

### `GET /api/v1/todos/similar`

Ähnliche TODOs suchen (für Duplikat-Erkennung).

**Query-Parameter:** `projectId` (Pflicht), `title` (Pflicht)

**Response:** `SimilarTodoResponse[]` mit `id`, `title`, `priority`, `score`, `similarity`

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

**WorkflowMemoryResponse-Felder:** `id`, `workflowId`, `title`, `content`, `tags`, `sourceRunIds`, `provider`, `active`, `createdAt`

### `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}`

Memory-Eintrag aktualisieren.

**Request:** `{ "title": "...", "content": "..." }`

**Response:** `WorkflowMemoryResponse`

### `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}/activate`

Memory-Eintrag aktivieren (wird in nachfolgende Runs injiziert).

**Response:** `204 No Content`

### `DELETE /api/v1/workflows/{workflowId}/memory/{memoryId}`

Memory-Eintrag löschen.

**Response:** `204 No Content`

---

## Activity Log

### `GET /api/v1/activity-log`

Activity-Log auflisten (paginiert, filterbar).

**Query-Parameter:** `page` (Standard: 0), `size` (Standard: 50), `level` (`DEBUG`, `INFO`, `WARN`, `ERROR`), `category` (`SYSTEM`, `EXECUTION`, `SCHEDULE`, `WEBHOOK`, `SECURITY`, `CONTAINER`, `PLUGIN`), `workflowId`

**Response:** `PageResponse<ActivityLogResponse>`

### `POST /api/v1/activity-log`

Neuen Activity-Log-Eintrag erstellen.

**Request:**

```json
{
  "level": "INFO",
  "message": "Manueller Eintrag",
  "source": "plugin",
  "referenceId": null,
  "workflowId": null,
  "metadata": {}
}
```

**ActivityLogResponse-Felder:** `id`, `timestamp`, `level`, `category`, `message`, `source`, `referenceId`, `workflowId`, `metadata`, `createdAt`

**Response:** `201` mit `ActivityLogResponse`

---

## Assistant

### `POST /api/v1/assistant/chat`

Chat-Nachricht an den KI-Assistenten senden.

**Request:**

```json
{
  "message": "Wie erstelle ich einen Workflow für Code-Reviews?",
  "history": [
    { "role": "user", "content": "Hallo" },
    { "role": "assistant", "content": "Hallo! Wie kann ich helfen?" }
  ],
  "context": {
    "page": "workflows",
    "entityId": "workflow-uuid",
    "projectId": "project-uuid",
    "workflowId": "workflow-uuid",
    "runId": null,
    "pathname": "/workflows/workflow-uuid"
  },
  "provider": "CLAUDE",
  "language": "de",
  "sessionId": "session-uuid",
  "requestId": "request-uuid"
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `message` | String | Ja |
| `history` | MessageEntry[] | Nein |
| `context` | PageContext | Nein |
| `provider` | String | Nein |
| `language` | String | Nein |
| `sessionId` | String | Nein |
| `requestId` | String | Nein |

**Response:** `AssistantChatResponse`

```json
{
  "message": "Hier ist eine Anleitung...",
  "actions": [
    {
      "type": "run_workflow",
      "id": "workflow-uuid",
      "name": "Code Review",
      "label": "Workflow starten",
      "options": null,
      "payload": null
    }
  ],
  "model": "sonnet",
  "provider": "CLAUDE",
  "responseTimeMs": 1234,
  "pendingTools": [
    {
      "id": "tool-uuid",
      "type": "create_todo",
      "description": "TODO erstellen: Unit-Tests ergänzen",
      "expiresAt": "2025-01-15T10:30:00Z"
    }
  ]
}
```

**Navigations-Typen:** `run_workflow`, `show_workflow`, `start_wish`, `show_run`, `ask_choice`

**Mutating-Proposal-Typen** (werden serverseitig als `PendingTool` mit Confirm/Reject-UI behandelt): `propose_create_todo`, `propose_cancel_run`, `propose_rerun_workflow`, `propose_update_todo_status`

### `GET /api/v1/assistant/sessions`

Alle Chat-Sessions auflisten.

**Response:** `AssistantSessionSummary[]` mit `id`, `preview`, `page`, `messageCount`, `startedAt`

### `GET /api/v1/assistant/sessions/{id}`

Einzelne Chat-Session mit Nachrichten abrufen.

**Response:** `AssistantSessionDetail` mit `id`, `preview`, `page`, `entityId`, `messages`, `messageCount`, `startedAt`, `endedAt`

### `DELETE /api/v1/assistant/sessions/{id}`

Chat-Session löschen.

**Response:** `200 OK`

### `POST /api/v1/assistant/pending-tools/{id}/confirm`

Pending Tool-Call bestätigen (z.B. Workflow-Start durch Assistenten).

**Request (optional):**

```json
{
  "sessionId": "session-uuid"
}
```

**Response:** `ToolExecutionResult` (oder `410 Gone` wenn bereits verarbeitet/abgelaufen)

### `POST /api/v1/assistant/pending-tools/{id}/reject`

Pending Tool-Call ablehnen.

**Request (optional):**

```json
{
  "sessionId": "session-uuid"
}
```

**Response:** `ToolExecutionResult` (oder `410 Gone` wenn bereits verarbeitet/abgelaufen)

### `GET /api/v1/assistant/suggestions`

Kontext-bezogene Vorschläge für eine Seite abrufen.

**Query-Parameter:** `page` (String, Pflicht)

**Response:** `String[]`

### `POST /api/v1/assistant/suggestions/refresh`

Vorschläge für eine Seite neu generieren.

**Query-Parameter:** `page` (String, Pflicht)

**Response:** `String[]`

---

## Dialog Import

Analyse und Import von Claude-Code-Dialog-Sessions als Workflows.

### `GET /api/v1/dialog/sessions/count`

Anzahl Sessions pro Projekt-Verzeichnis zählen (schnell, kein File-Lesen).

**Response:** `Map<String, Integer>`

### `GET /api/v1/dialog/sessions`

Sessions mit Metadaten auflisten.

**Query-Parameter:**

| Parameter | Typ | Beschreibung |
|---|---|---|
| `projectPath` | String | Nein (optional: Filter auf ein Projekt) |
| `limit` | int | Standard: 50 (Max: 2000 mit projectPath, 500 ohne) |

**Response:** `DialogSessionInfo[]`

### `GET /api/v1/dialog/projects`

Alle bekannten Projekt-Pfade auflisten (für Filter-Dropdown).

**Response:** `String[]`

### `POST /api/v1/dialog/sessions/analyze`

Session mit KI analysieren und Workflow-Vorschlag generieren.

**Request:**

```json
{
  "filePath": "/home/user/.claude/projects/.../session.jsonl"
}
```

**Response:** `DialogWorkflowSuggestion`

### `POST /api/v1/dialog/sessions/create-workflow`

Workflow aus einer Suggestion erstellen.

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Workflow-Name",
  "suggestion": { ... }
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `projectId` | String | Ja |
| `name` | String | Nein |
| `suggestion` | DialogWorkflowSuggestion | Ja |

**Response:** `WorkflowResponse`

---

## Staging (Stable-Umgebung)

### `GET /api/v1/staging/status`

Status der Stable-Umgebung.

**Response:** `StagingStatusResponse` mit `containerRunning`, `healthy`, `promotePhase`, `promoteStep`, `promoteTotalSteps`, `lastSnapshot`, `error`, `stableVersion`, `stableBuildTimestamp`

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

**Response:** `SyncPreview` mit `projects`, `workflows`, `skills`, `chains` (jeweils Liste von `SyncPreviewItem`)

### `POST /api/v1/staging/sync-definitions`

Definitionen von Dev nach Stable übertragen (Projekte, Workflows, Skills, Chains).

**Request (optional):**

```json
{
  "projectIds": ["uuid"],
  "workflowIds": ["uuid"],
  "skillIds": ["uuid"],
  "chainIds": ["uuid"]
}
```

Alle Felder optional; ohne Filter werden alle Definitionen synchronisiert.

**Response:** `SyncResult` mit `projectsSynced`, `workflowsSynced`, `skillsSynced`, `chainsSynced`, `skipped`, `errors`, `preSyncBackup`

---

## Testing (Test-Instanz)

### `GET /api/v1/testing/status`

Status der Test-Instanz (Port 8090).

**Response:** `TestingStatusResponse` mit `containerRunning`, `healthy`, `testVersion`, `testBuildTimestamp`

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

Definitionen von Dev nach Test übertragen. Request-Format wie bei `POST /api/v1/staging/sync-definitions`.

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
  "projectId": "uuid",
  "name": "Optionaler Name",
  "forceCreate": false
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `projectId` | String | Ja |
| `name` | String | Nein |
| `forceCreate` | Boolean | Nein |

**Response:** `{ "workflowId": "uuid", "workflowName": "...", "created": true }`

### `POST /api/v1/pool/export/{workflowId}`

Bestehenden Workflow in den Pool exportieren.

**Request (optional):**

```json
{
  "nameEn": "English Name",
  "nameDe": "Deutscher Name",
  "descriptionEn": "...",
  "descriptionDe": "...",
  "category": "CODE",
  "tags": ["review"],
  "author": "..."
}
```

**Response:** `201` mit `ExportToPoolResponse` (`poolId`, `filename`)

---

## Workflow Factory

### `GET /api/v1/factory/topics`

Verfügbare Topic-Kategorien abrufen.

**Response:** `Array<{ "id": "...", "label": "..." }>`

### `POST /api/v1/factory/generate`

Workflow aus Topic generieren (Vorschau, ohne Test-Ausführung).

**Request:**

```json
{
  "topic": "code-review",
  "description": "Automatisiertes Code-Review",
  "category": "CODE",
  "projectId": "uuid",
  "testInputs": {},
  "autoPromote": false,
  "autoFix": false,
  "maxRetries": 2,
  "provider": "CLAUDE"
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `topic` | String | Ja |
| `description` | String | Nein |
| `category` | String | Nein |
| `projectId` | String | Nein |
| `testInputs` | Object | Nein |
| `autoPromote` | boolean | Nein |
| `autoFix` | boolean | Nein |
| `maxRetries` | int | Nein (Standard: 2) |
| `provider` | String | Nein |

**Response:** `201` mit `WorkflowResponse`

### `POST /api/v1/factory/run`

Vollständige Factory-Pipeline: Generieren → Test-Instanz → Verifizieren → optional Promoten.

**Request:** `WorkflowFactoryRequest` (wie oben)

**Response:** `WorkflowFactoryResult` mit `workflowId`, `workflowName`, `status`, `testRunId`, `testStatus`, `testError`, `promotedAsTemplate`, `durationMs`, `retryErrors`

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

| Feld | Typ | Pflicht |
|---|---|---|
| `text` | String | Ja |
| `projectId` | String | Ja |
| `maxIterations` | Integer | Nein |
| `referenceWorkflowIds` | String[] | Nein |
| `autoRefine` | boolean | Nein (Standard: `false`) |

**Response:** `201` mit `WishResponse`

**WishResponse-Felder:** `id`, `originalText`, `status`, `projectId`, `projectName`, `decomposition`, `generatedWorkflowIds`, `generatedWorkflowNames`, `referenceWorkflowIds`, `referenceWorkflowNames`, `generatedChainId`, `iterationCount`, `maxIterations`, `currentRunId`, `resultSummary`, `error`, `iterationHistory`, `metadata`, `autoRefine`, `createdAt`, `updatedAt`

**WishStatus-Werte:** `RECEIVED`, `DECOMPOSING`, `AWAITING_DECOMPOSE_APPROVAL`, `EXECUTING`, `EVALUATING`, `REFINING`, `AWAITING_CLARIFICATION`, `AWAITING_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELLED`

### `GET /api/v1/wishes/{id}`

Einzelnen Wish abrufen.

**Response:** `WishResponse`

### `POST /api/v1/wishes/{id}/cancel`

Wish abbrechen.

**Response:** `200 OK`

### `POST /api/v1/wishes/{id}/respond`

Feedback zur aktuellen Iteration geben.

**Request:**

```json
{
  "action": "ACCEPT",
  "feedback": "Optional: Anpassungswünsche"
}
```

| Feld | Typ | Pflicht | Werte |
|---|---|---|---|
| `action` | Enum | Ja | `ACCEPT`, `REFINE`, `CANCEL`, `CLARIFY`, `RETRY` |
| `feedback` | String | Nein | |

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

**Response:** `Array<{ "name": "architektur.md", "label": "Architektur", "lang": "de" }>`

### `GET /api/v1/docs/{name}`

Inhalt einer Dokumentationsdatei abrufen (nur `.md`-Dateien).

**Query-Parameter:** `lang` (Standard: `"de"`, Werte: `"de"`, `"en"`)

**Response:** `{ "content": "# Architektur\n..." }`

---

## Search

### `GET /api/v1/search`

Aggregierte Suche über Workflows (Substring/Fuzzy-Ranking).

**Query-Parameter:**

| Parameter | Typ | Beschreibung |
|---|---|---|
| `q` | String | Suchbegriff |
| `types` | String | Zu durchsuchende Typen (Standard: `"workflow"`) |
| `limit` | int | Max. Ergebnisse (Standard: 20, Max: 50) |

**Response:**

```json
{
  "workflows": [...]
}
```

### `POST /api/v1/search/workflows/semantic`

Semantische Workflow-Suche via KI-Modell. Gibt `204 No Content` zurück wenn semantische Suche nicht aktiviert ist.

**Request:**

```json
{
  "query": "Code-Qualität prüfen",
  "limit": 5
}
```

**Response:** `SemanticSearchResult[]` mit `workflowId`, `workflowName`, `reason`, `score`

---

## Reports

### `GET /api/v1/reports`

Verfügbare Report-Dateien auflisten (`.md`-Dateien im konfigurierten Reports-Verzeichnis, sortiert nach Datum absteigend).

**Response:** `Array<{ "name": "report-2025-01.md", "label": "Report 2025 01" }>`

### `GET /api/v1/reports/{name}`

Inhalt einer Report-Datei abrufen (nur `.md`-Dateien, Path-Traversal-Schutz).

**Response:** `{ "content": "# Report\n..." }`

---

## Dead Letter Queue

Fehlgeschlagene Workflow-Runs werden automatisch in der DLQ erfasst und können dort verwaltet werden.

### `GET /api/v1/dlq`

DLQ-Einträge auflisten (paginiert).

**Query-Parameter:**

| Parameter | Typ | Beschreibung |
|---|---|---|
| `status` | String | Filter: `OPEN`, `RETRIED`, `DISCARDED` |
| `page` | int | Seite (Standard: 0) |
| `size` | int | Einträge pro Seite (Standard: 20) |

**Response:** `Page<DeadLetterEntry>` mit `id`, `workflowRunId`, `workflowId`, `workflowName`, `errorMessage`, `failedStepIndex`, `retryCount`, `lastFailureAt`, `status`, `createdAt`, `updatedAt`

### `GET /api/v1/dlq/{id}`

Einzelnen DLQ-Eintrag abrufen.

**Response:** `DeadLetterEntry`

### `POST /api/v1/dlq/{id}/retry`

DLQ-Eintrag erneut ausführen (startet eine frische Workflow-Ausführung mit dem Original-Input-Kontext).

**Response:** `DeadLetterEntry` (Status wechselt zu `RETRIED`)

### `DELETE /api/v1/dlq/{id}`

DLQ-Eintrag verwerfen.

**Response:** `204 No Content`

---

## Debug

### `GET /api/v1/debug/failed-workflows`

Workflows deren letzter Run fehlgeschlagen ist (`FAILED` / `COMPLETED_WITH_ERRORS`), sortiert nach letztem Fehler absteigend.

**Response:** `BrokenWorkflowResponse[]`

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
| `/topic/executions/{runId}/logs` | Workflow-Ausführungs-Logs (Steps, Status, Approval-Prompts) |
| `/topic/chains/{chainRunId}/status` | Chain-Ausführungs-Status-Updates |
| `/topic/assistant/{requestId}/status` | Assistent-Chat-Fortschritt (Phase, Detail, Elapsed) |
| `/topic/assistant/{sessionId}/events` | Assistent-Push-Events (erstellte Items, Suggestions) |
| `/topic/wishes/{wishId}/status` | Wish-Iterations-Status |
| `/topic/activity-log` | Globale Activity-Log-Events |

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
Endpunkt: ws://localhost:9090/ws/terminal/{runId}?apiKey=...
```

Für Echtzeit-Container-Zugriff (interaktive Shell). Raw WebSocket (kein SockJS/STOMP). Max. 3 Sessions pro Container. Authentifizierung über `apiKey`-Query-Parameter.
