# API-Referenz

> **Scope:** Vollständige interne REST-API-Referenz für MySuricates. Die kuratierte Kurzreferenz für externe Agents ist [AGENTS.md](../AGENTS.md).

## Allgemein

- **Basis-URL:** `http://localhost:9090/api/v1`
- **Content-Type:** `application/json`
- **Authentifizierung:** Header `X-API-Key: <key>` (wenn `automation.security.enabled=true`)
- **Proxy:** Aufrufe mit API-Key per `curl` immer mit `--noproxy '*'` – bei gesetztem `http_proxy`
  schickt curl auch Anfragen an `localhost` über den Proxy, samt Key. Das Doku-Gate
  `doc-facts-check.sh` prüft das an jedem Beispiel in `docs/` und `AGENTS.md`.

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
| `MODULE_DISABLED` | 404 |
| `MODULES_NOT_EDITABLE` | 403 |
| `VALIDATION_FAILED` | 400 |
| `INVALID_ARGUMENT` | 400 |
| `WORKFLOW_EXECUTION_FAILED` | 500 |
| `CONTAINER_ERROR` | 500 |
| `STAGING_ERROR` | 500 |
| `STATE_CONFLICT` | 409 |
| `WORKFLOW_SEALED` | 409 |
| `WORKFLOW_TAMPERED` | 409 |
| `UNATTENDED_REQUIRES_SEAL` | 403 |
| `WEBHOOK_SIGNATURE_INVALID` | 401 |
| `NO_TASK_TARGET` | 400 |
| `PROVIDER_NOT_CONNECTED` | 409 |
| `PROVIDER_NOT_TASK_CAPABLE` | 400 |
| `SERVICE_UNAVAILABLE` | 503 |
| `TOKEN_BUDGET_EXCEEDED` | 429 |
| `OPERATION_TIMEOUT` | 504 |
| `RUN_DELETION_DISABLED` | 403 |
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
    "updatedAt": "2025-01-15T10:00:00Z",
    "workflowCount": 3
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

### `POST /api/v1/projects/example`

Das mitgelieferte Beispielprojekt anlegen, gedacht für eine frische Installation. Die Oberfläche bietet es an, solange es kein Projekt gibt.

- **Verzeichnis:** Die Vorlage `classpath:examples/example-project/` wird nach `<automation.examples.directory>/example-project` kopiert. Standard ist `mysuricates-examples` unter dem Workspace-Root, ohne Workspace-Root `./data/examples` neben der Datenbank. Die Umgebungsvariable dafür ist `AUTOMATION_EXAMPLES_DIR`. Ein vorhandenes Verzeichnis wird nie überschrieben.
- **Workflows:** je ein Workflow pro mitgelieferter Vorlage, die `ExampleProjectService.EXAMPLE_TEMPLATE_IDS` nennt (derzeit die Blaupause `blueprint-approval`), mit `changeSource` `example-project`.
- **Idempotent:** Ein aktives Projekt, das schon auf das Verzeichnis zeigt, wird zurückgegeben, und fehlende Beispiel-Workflows werden ergänzt.

| Parameter | Typ | Pflicht | Werte |
|---|---|---|---|
| `locale` | Query | Nein | `de`, `en` (Default `en`): Sprache von Projektname und Beispiel-Workflows |

**Response:** `201` mit `ProjectResponse`, wenn das Projekt neu angelegt wurde, sonst `200`.

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

Alle Felder sind optional; nur übergebene Felder werden aktualisiert. Änderbar sind `cronExpression`
(nur `CRON`), `scheduledAt` (nur `ONCE`), `description`, `inputContext`, `endDate` und `maxRuns` (nur
`CRON`), `catchUpOnResume` und `conflictBehavior` (`SKIP`/`OVERLAP`, seit 2026-09-24; ein anderer Wert
→ `400`). Ziel und Typ eines Zeitplans bleiben fest.

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
| `skillIds` | String[] | Nein | Skill-IDs die zusätzlich zu Workflow-Skills für diesen Block aktiviert werden (additiv) |

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
  "blocks": [],
  "version": "4.4.0"
}
```

**Response:** `WorkflowResponse`

Alle Felder sind optional; ein fehlendes Feld bleibt unverändert. `version` ist das Versions-Label (höchstens 255 Zeichen, leer → `400`), kein Teil der Definition: Es erzeugt keine neue Version und ändert keinen Hash. Die Pool-Neuinstallation setzt es auf die Version der Pool-Datei.

Die Herkunft der Änderung (`changeSource` der neuen Version) bestimmt der Server. Versiegelte Workflows: Definitionsänderungen → `409 WORKFLOW_SEALED`, Umbenennen und `version` bleiben möglich.

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

### `POST /api/v1/workflows/import/bpmn`

Workflow aus zuvor per `GET /api/v1/workflows/{id}/export/bpmn` exportiertem BPMN 2.0 XML als **neuen** Workflow importieren (Roundtrip). Akzeptiert nur MySuricates-eigene Exporte (BPMN-Extension `mysuricates:blockConfig`); fremdes BPMN, kaputtes XML oder zu große Eingaben liefern `400` — dann wird nichts angelegt.

**Query-Parameter:** `projectId` (Pflicht) — Zielprojekt

**Request:** `Content-Type: application/xml` oder `text/xml`, Body = BPMN-XML

**Response:** `201` mit `WorkflowResponse`

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
| `version` | String | Nein — Versions-Label des Exports (`4.4.0`); fehlt es oder ist es leer, bleibt es beim Standard `1.0.0` bzw. beim bisherigen Label |

Wenn `updateIfExists=true` und ein Workflow gleichen Namens im Zielprojekt existiert, wird dieser aktualisiert statt neu erstellt – samt `version`, wie bei `PUT` und der Pool-Neuinstallation.

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
  "triggeredByScheduleId": null,
  "triggerSource": "MANUAL",
  "definitionHash": "3f1c…e9",
  "definitionVersionId": "version-uuid",
  "chainRunId": null
}
```

`definitionHash`/`definitionVersionId` sind `null`, wenn `automation.hardening.provenance` aus ist. `chainRunId` nennt den Chain-Run, der den Lauf als Eintrag gestartet hat (`triggerSource: CHAIN`, nach einem Fortsetzen `RESUME`), sonst `null` — auch bei Retry und Sub-Workflows, die `{{ chain_run_id }}` nur mit dem Kontext erben; Läufe von vor V89 haben keinen. Abgelehnte Starts erzeugen keinen Run: `409 WORKFLOW_TAMPERED` (versiegelte Definition verändert), `403 UNATTENDED_REQUIRES_SEAL` (nur bei unbeaufsichtigten Auslösern).

### `GET /api/v1/workflows/{id}/runs`

Alle Runs eines Workflows (absteigend nach Startzeit, paginiert).

**Query-Parameter:** `page` (Standard: 0), `size` (Standard: 50)

**Response:** `PageResponse<WorkflowRunListItemResponse>` — **ohne Steps**, dieselben Listenzeilen wie
`GET /api/v1/executions` (`stepCount`, `lastStepStatus`, `lastStepPreview`). Bis 2026-09-23 trug diese
Route jeden Run mit allen Schritten. Die Schritte eines Runs liefert `GET /api/v1/workflows/{id}/runs/{runId}`.

### `GET /api/v1/workflows/{id}/runs/{runId}`

Einzelnen Run abrufen, mit allen Schritten.

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

**Response:** `202 Accepted` mit `{ "status": "repair_started", "workflowId": "...", "workflowName": "..." }` — versiegelte Workflows: `409 WORKFLOW_SEALED`, bevor die Reparatur startet

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
      "changeSource": "assistant",
      "definitionHash": "3f1c…e9",
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

### `POST /api/v1/workflows/{id}/seal`

Workflow versiegeln (Schalter `automation.hardening.sealing`, sonst `404 MODULE_DISABLED`). Hält den aktuellen
Definitions-Hash fest; danach lehnt der Workflow Definitionsänderungen und Löschen ab (`409 WORKFLOW_SEALED`) und startet
nicht, wenn die Definition vom Siegel abweicht (`409 WORKFLOW_TAMPERED`). Idempotent; protokolliert einen
`SECURITY`-Eintrag.

**Response:** `WorkflowResponse` mit `sealed: true`, `sealedHash`, `sealedAt`

### `POST /api/v1/workflows/{id}/unseal`

Siegel lösen (gleicher Schalter). Protokolliert einen `SECURITY`-Eintrag mit dem bisherigen Hash.

**Response:** `WorkflowResponse` mit `sealed: false`

---

## Workflow Templates

### `GET /api/v1/workflow-templates`

Alle Workflow-Templates auflisten, sortiert nach Art und `sortOrder`. Das Feld `kind` unterscheidet Vorlagen (`TEMPLATE`) von Blaupausen (`BLUEPRINT`: leere Gerüste, die Block-Verknüpfungen zeigen, siehe [WORKFLOW-GUIDE → Blaupausen](WORKFLOW-GUIDE.md#blaupausen)).

**Query:** `locale` (optional, `de`/`en`): Sprache der Schritte (`blocks`, `instructions`) zweisprachiger Templates. Ohne Angabe gilt Englisch. `name` und `description` bleiben Maps. Siehe [WORKFLOW-GUIDE → Zweisprachige Vorlagen](WORKFLOW-GUIDE.md#zweisprachige-vorlagen).

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
    "updatedAt": "2025-01-15T10:00:00Z",
    "kind": "TEMPLATE",
    "requiredModules": [],
    "needsAi": true
  }
]
```

`verifications` enthält pro Projekt, in dem das Template verifiziert wurde, ein Objekt mit `projectId`, `projectName`, `lastRunId`, `verifiedAt`.

`requiredModules` nennt die optionalen Module, die die Blöcke brauchen (etwa `skills` für `skill-capability`, `integrations` für `integration`; verschachtelte Blöcke eingeschlossen). Ist eines davon aus, verweigert der Lauf den Block, und die Vorlagen-Auswahl blendet das Template aus. `needsAi` sagt, ob ein Lauf ein KI-Modell rufen kann: Modell-Blöcke, `foreach`/`while`/`conditional`/`retry` ohne `execution_mode: shell`, ein `simple`-Block in einem `switch`-Zweig ohne Shell-Modus, und `sub-workflow`, weil der gestartete Workflow nicht Teil der Definition ist.

### `GET /api/v1/workflow-templates/{id}`

Einzelnes Template abrufen. Query `locale` wie bei der Liste.

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

Bei `status` zählt die Groß- und Kleinschreibung nicht. Ein unbekannter Wert ergibt `400` (`INVALID_ARGUMENT`) mit den erlaubten Werten, etwa `status muss PENDING, RUNNING, … oder INTERRUPTED sein, nicht 'FOO'`.

**Response:** `PageResponse<WorkflowRunListItemResponse>` — **ohne Steps.** Statt der Schritte
trägt eine Listenzeile `stepCount`, `lastStepStatus` und `lastStepPreview` (Ausgabe des letzten
Schritts, auf 500 Zeichen gekürzt — in der Datenbank abgeschnitten, die Step-Zeilen werden nicht geladen). Grund: Die Liste trug vorher jeden `step_results.output`
(TEXT) mit — gemessen 3 061 816 Bytes bei 60 Runs, danach 51 716. Wer die Schritte braucht, holt
den Run einzeln über `GET /api/v1/executions/{id}`; die Detail-Route liefert unverändert alles.

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

### `GET /api/v1/executions/{id}/live-log`

Gepufferte Live-Zeilen eines Laufs für das Replay nach Reload oder spätem Öffnen (nur im Speicher, bis 10 Minuten nach
Laufende). Query: `afterSeq` (Default `-1` = alles). Ohne Puffer `200` mit `available: false`.

**Response:**

```json
{
  "runId": "run-uuid",
  "bufferId": "buffer-uuid",
  "available": true,
  "firstSeq": 0,
  "lastSeq": 2,
  "skipped": 0,
  "stepNames": { "0": "Build" },
  "lines": [
    { "seq": 1, "stepIndex": 0, "kind": "tool", "text": "Bash: mvn test", "ts": 1757844005000 },
    { "seq": 2, "stepIndex": 0, "kind": "tool_result", "text": "↳ 1.2 KB", "ts": 1757844009000 }
  ]
}
```

`kind`: `line` (Rohzeile), `text`, `tool`, `tool_result`, `tool_error`, `result`, `system`. `stepNames`: Blocknamen der Steps mit gepufferten Zeilen (Schlüssel = `stepIndex`).

### `DELETE /api/v1/executions/{id}`

Lauf samt Schritten und den losen Verweisen darauf löschen. `409`, wenn der Lauf noch aktiv ist oder ein Audit-Bericht an ihm hängt. TODOs und Audit-Daten werden nie mitgelöscht.

`403 RUN_DELETION_DISABLED`, wenn die Instanz mit `automation.executions.allow-delete: false` (Env `AUTOMATION_EXECUTIONS_ALLOW_DELETE`, Default `true`) gestartet wurde — geprüft vor jedem Lookup, für jede ID. Nur Startkonfiguration, kein REST- oder Settings-Schalter. `GET /api/v1/system/capabilities` meldet den Stand als `runDeletionAllowed`; die UI blendet die Löschknöpfe dann aus. Die Run-Retention (`run-retention-days`) und das nächtliche Aufräumen von INTERRUPTED-Läufen gelten weiter — wer gar nichts verlieren will, setzt zusätzlich `run-retention-days` auf `0`. Solange die Löschung abgeschaltet ist, nimmt `PUT /api/v1/system/execution-config` für `run-retention-days` nur eine gleich lange oder längere Retention an (`0` gilt als längste), sonst `403 RUN_DELETION_DISABLED` — der ganze Aufruf wird dann nicht angewendet.

**Response:** `204 No Content`

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

### `GET /api/v1/skills/{id}/versions`

Änderungsverlauf eines Skills, neueste zuerst — auch für gelöschte Skills.

**Response:** `SkillVersionResponse[]` mit `id`, `skillId`, `versionNumber`, `operation` (`CREATE`/`UPDATE`/`DELETE`), `changeSource` (`user`/`import`/`builtin-seed`), `contentHash`, `createdAt`; der Snapshot selbst wird nicht ausgeliefert.

### `GET /api/v1/skills/seal-pins`

Ordnet jeder Skill-ID die Namen der versiegelten Workflows zu, die den Skill nutzen. Solche Skills lassen sich nicht ändern oder löschen.

**Response:** `{"<skillId>": ["Workflow-Name", ...]}`

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

### `PUT /api/v1/skills/{id}`

Eigenen Skill bearbeiten. Änderbar sind `name`, `description`, `icon`, `category` und `aiContext` (Pflichtfelder wie beim Anlegen); `configSchema`, Capabilities und das Versions-Etikett bleiben unverändert. Jede Änderung schreibt eine Zeile in `GET /api/v1/skills/{id}/versions` (Herkunft `user`).

**Response:** `200` mit `SkillResponse` · `409 STATE_CONFLICT` bei einem Built-in-Skill (das nächste Release-Update würde die Änderung überschreiben) · `409 SKILL_PINNED_BY_SEAL`, solange ein versiegelter Workflow den Skill nutzt

### `DELETE /api/v1/skills/{id}`

Skill löschen.

**Response:** `204 No Content` · `409 STATE_CONFLICT` bei einem Built-in-Skill · `409 SKILL_PINNED_BY_SEAL`, solange ein versiegelter Workflow den Skill nutzt

### `GET /api/v1/skills/export/{id}`

Skill als JSON exportieren (inkl. Capabilities).

**Response:** `ExportSkillResponse`

### `POST /api/v1/skills/import`

Skill aus JSON importieren.

**Request:** `ImportSkillRequest` (exportiertes Format); jede Capability braucht einen `name`, sonst `400`

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
    "pausedUntil": null,
    "maintenance": false,
    "createdAt": "2025-01-15T10:00:00Z"
  }
]
```

`nextRunAt` ist der nächste Lauf, den der Scheduler tatsächlich startet. Liegt der gespeicherte Termin
in einer aktiven `skip-until`-Pause (`automation.scheduler.skip-until`), steht hier der erste Termin nach
der Pause — bei einem einmaligen Zeitplan `null`, er verfällt — und `pausedUntil` nennt das Pausenende;
sonst ist `pausedUntil` `null`. Der Tick spult den Termin erst weiter, wenn er fällig wird; ohne diese
Umrechnung zeigten Liste, Kalender und Workflow-Seite bis dahin einen Lauf, der nie stattfindet (seit
2026-09-24, TODO a776fd2a). Solange der Wartungsmodus an ist, startet der Scheduler nichts, und wann er
wieder ausgeht, steht nirgends: Dann ist `nextRunAt` `null` und `maintenance` `true` (für jeden Zeitplan
mit gespeichertem nächsten Termin; der gespeicherte Wert bleibt, TODO 7704b1e9). Dasselbe gilt für alle
Routen, die `ScheduledWorkflowResponse` liefern.

### `GET /api/v1/schedules/remaining-today`

Verbleibende geplante Ausführungen für heute.

**Query-Parameter:** `projectId` (optional) — nur Zeitpläne von Workflows dieses Projekts; Ketten gehören zu
keinem Projekt und fallen dann heraus (Projektfilter der Dashboard-Ansicht).

**Response:** `UpcomingScheduleResponse` (`count`, `schedules[]`, `pausedUntil`, `maintenance`). Termine in
einer aktiven `skip-until`-Pause fehlen — der Tick überspringt sie —, `pausedUntil` nennt dann das Pausenende,
sonst `null`. Im Wartungsmodus ist die Liste leer und `maintenance` `true`: Solange er an ist, startet nichts.

### `GET /api/v1/schedules/remaining-this-week`

Verbleibende geplante Ausführungen für diese Woche. `projectId` (optional) wie bei `remaining-today`.

**Response:** `UpcomingScheduleResponse` (wie `remaining-today`, einschließlich `pausedUntil` und `maintenance`)

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

`workflowRuns` sind `WorkflowRunListItemResponse` — **ohne Steps**, dieselben Listenzeilen wie
`GET /api/v1/executions` (`stepCount`, `lastStepStatus`, `lastStepPreview`). Bis 2026-09-23 trug diese
Route jeden Run mit allen Schritten. Die Schritte eines Runs liefert `GET /api/v1/executions/{id}`.

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

**Response:** `CalendarDataResponse` mit `upcoming` (`ScheduledWorkflowResponse[]`, Zeitpläne, deren
nächster tatsächlicher Lauf im Zeitraum liegt — während einer `skip-until`-Pause also nach der Pause) und `pastRuns` (`WorkflowRunListItemResponse[]` — **ohne Steps**, dieselben
Listenzeilen wie `GET /api/v1/executions`; bis 2026-09-23 mit allen Schritten jedes Runs im Zeitraum)

### `POST /api/v1/schedules/preview`

Vorschau der nächsten 5 Ausführungszeitpunkte für einen Cron-Ausdruck.

**Request:**

```json
{
  "cronExpression": "0 0 2 * * ?"
}
```

**Response:** `{ "runs": ["2025-01-16T02:00:00Z", ...] }` — während einer aktiven `skip-until`-Pause
beginnen die Termine nach der Pause, und die Antwort trägt zusätzlich `"pausedUntil"`.

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
| `maxRuns` | Integer | Nein (max. Starts, 0 = unbegrenzt — siehe unten) |
| `conflictBehavior` | String | Nein (Standard: `SKIP`, Werte: `SKIP`, `OVERLAP` — siehe unten) |
| `catchUpOnResume` | boolean | Nein (Standard: `false` — siehe unten) |

`targetType`, `scheduleType` und `conflictBehavior` werden getrimmt und in beliebiger Schreibweise
angenommen; ein anderer Wert → `400`, die Meldung nennt die erlaubten Werte.

- **`conflictBehavior`:** Bei `SKIP` entfällt ein Termin, solange das Ziel noch einen Lauf in `RUNNING`
  oder `PAUSED` hat: `nextRunAt` springt auf das nächste Vorkommen, ein einmaliger Zeitplan (`ONCE`,
  `DELAYED`) verfällt, das Activity-Log vermerkt „Schedule skipped (previous run still in progress)“.
  `OVERLAP` startet trotzdem einen zweiten Lauf daneben.
- **`maxRuns`:** zählt jeden Start des Zeitplans, auch einen fehlgeschlagenen oder abgewiesenen; ein per
  `SKIP` entfallener Termin zählt nicht. Ist die Zahl erreicht, wird der Zeitplan deaktiviert.
  Wiedereinschalten setzt `runCount` nicht zurück — dann folgt genau ein weiterer Start.
- **`catchUpOnResume`:** holt Termine dieses Zeitplans nach, die in Standby oder einen Neustart fielen,
  auch wenn die globale Einstellung `scheduler-catch-up-on-resume` aus ist. Die ist standardmäßig an —
  dann wird ohnehin jeder Zeitplan nachgeholt, nacheinander über die Catch-up-Warteschlange.

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

### `GET /api/v1/schedules/catch-up`

Sequenzielle Catch-up-Warteschlange abfragen: der gerade laufende nachgeholte Termin (nach Kaltstart/Resume) und die noch wartenden. Nicht persistiert — nach einem Neustart werden fällige Termine neu eingereiht.

**Response:** `CatchUpQueue.Snapshot` (`active`, `pending[]`)

### `GET /api/v1/schedules/maintenance`

Wartungsmodus-Status abfragen, zusammen mit einer aktiven `skip-until`-Pause aus der Konfiguration.

**Response:** `{ "enabled": false, "pausedUntil": null }` — `pausedUntil` ist das Ende der Pause, solange
sie noch vor uns liegt, sonst `null`.

### `POST /api/v1/schedules/maintenance`

Wartungsmodus ein-/ausschalten. Im Wartungsmodus startet kein Zeitplan; der gespeicherte `nextRunAt`
bleibt dabei stehen, die Ansichten zeigen solange keinen nächsten Lauf (`maintenance: true`, siehe oben).
Beim Ausschalten startet jeder Zeitplan, dessen Termin in die Pause fiel, einmal sofort — alle
gleichzeitig, ohne Catch-up-Warteschlange.

**Request:**

```json
{
  "enabled": true
}
```

**Response:** `{ "enabled": true, "pausedUntil": null }`

---

## Webhooks

Der geheime Token wird nur direkt nach dem Erstellen (`POST /api/v1/webhooks`) oder Neu-Erzeugen
(`POST /api/v1/webhooks/{id}/regenerate-token`) im Klartext zurückgegeben (`tokenVisible: true`).
In jeder anderen Management-Response (List, Get, Enable, Disable) ist er maskiert: nur die ersten
8 Zeichen plus `…` (U+2026), und `webhookUrl` verwendet denselben maskierten Token. Der volle Token
muss beim Erstellen/Regenerieren sofort gesichert werden — er lässt sich danach nicht mehr abrufen.

**Upgrade-Hinweis:** Bestehende Webhook-Tokens sind nach diesem Update nicht mehr im Klartext
abrufbar. Wiederherstellung: `POST /api/v1/webhooks/{id}/regenerate-token` aufrufen und den neuen
Token beim externen Aufrufer (z. B. GitHub-Webhook-Konfiguration) neu hinterlegen.

### `GET /api/v1/webhooks`

Alle Webhooks auflisten.

**Response:** `WebhookTriggerResponse[]`

```json
[
  {
    "id": "uuid",
    "workflowId": "uuid",
    "workflowName": "Deploy Pipeline",
    "secretToken": "abc123de…",
    "tokenVisible": false,
    "webhookUrl": "http://localhost:9090/api/v1/webhook/trigger/abc123de…",
    "enabled": true,
    "description": "GitHub Push Hook",
    "defaultContext": {},
    "lastTriggeredAt": null,
    "triggerCount": 0,
    "createdAt": "2025-01-15T10:00:00Z",
    "signatureRequired": false
  }
]
```

### `GET /api/v1/webhooks/{id}`

Einzelnen Webhook abrufen.

**Response:** `WebhookTriggerResponse` (Token maskiert, `tokenVisible: false`)

### `GET /api/v1/webhooks/workflow/{workflowId}`

Webhooks eines bestimmten Workflows.

**Response:** `WebhookTriggerResponse[]` (Token maskiert, `tokenVisible: false`)

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

**Response:** `201` mit `WebhookTriggerResponse` — Token voll sichtbar, `tokenVisible: true`

### `POST /api/v1/webhooks/{id}/enable`

Webhook aktivieren.

**Response:** `WebhookTriggerResponse` (Token maskiert, `tokenVisible: false`)

### `POST /api/v1/webhooks/{id}/disable`

Webhook deaktivieren.

**Response:** `WebhookTriggerResponse` (Token maskiert, `tokenVisible: false`)

### `POST /api/v1/webhooks/{id}/regenerate-token`

Erzeugt einen neuen geheimen Token für den Webhook. Der bisherige Token wird sofort ungültig;
`lastTriggeredAt` und `triggerCount` bleiben unverändert. Protokolliert einen `SECURITY`-Eintrag
im ActivityLog.

**Response:** `WebhookTriggerResponse` — neuer Token voll sichtbar, `tokenVisible: true`

### `POST /api/v1/webhooks/{id}/secret`

Signatur-Secret setzen oder erneuern (Schalter `automation.hardening.webhook-signatures`, sonst `404 MODULE_DISABLED`).
Das Secret wird nur in dieser Response gezeigt und verschlüsselt gespeichert; ab jetzt brauchen Trigger-Aufrufe eine
Signatur. Protokolliert einen `SECURITY`-Eintrag.

**Response:** `{ "secret": "<64 hex>", "header": "X-Signature" }`

### `DELETE /api/v1/webhooks/{id}/secret`

Signatur-Secret entfernen (gleicher Schalter).

**Response:** `204 No Content`

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
Er muss ein JSON-Objekt sein (sonst `400`); Schlüssel mit `__`-Präfix sind reserviert.

**Signatur:** Hat der Webhook ein Signatur-Secret (`signatureRequired: true`), braucht der Aufruf den Header
`X-Signature: sha256=<hex>` (alternativ `X-Hub-Signature-256`) mit dem HMAC-SHA256 der rohen Request-Bytes — sonst
`401 WEBHOOK_SIGNATURE_INVALID`:

```bash
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | cut -d' ' -f2)
curl -X POST "$URL" -H 'Content-Type: application/json' -H "X-Signature: sha256=$SIG" -d "$BODY"
```

Mit `automation.hardening.require-sealed-for-unattended` startet ein Webhook nur versiegelte Workflows (sonst
`403 UNATTENDED_REQUIRES_SEAL`).

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

**Benannte Verbindungen:** Ein Provider kann mehrere Verbindungen haben (z.B. zwei Konten). Alle Endpunkte, die eine Verbindung betreffen (`connect`, `credentials`, `connection`, `actions`, `task-targets`), nehmen den optionalen Query-Parameter `connection=<name>` (1–40 Zeichen `[a-z0-9-]`, sonst `400`); ohne ihn gilt `default`. Details: [integrations.md](integrations.md#mehrere-verbindungen-je-anbieter).

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
    ],
    "requiresOAuthFlow": true,
    "connections": [
      { "name": "default", "status": "CONNECTED", "configured": true, "accountEmail": "user@gmail.com", "accountName": "Max Mustermann", "connectedSince": "2026-04-01T10:00:00Z", "scopes": "..." },
      { "name": "arbeit", "status": "CONNECTED", "configured": true, "accountEmail": "max@firma.example", "accountName": null, "connectedSince": "2026-09-24T08:00:00Z", "scopes": "..." }
    ]
  }
]
```

Die Felder auf oberster Ebene (`status`, `accountEmail`, …) beschreiben die `default`-Verbindung.

### `GET /api/v1/integrations/{providerId}/connections`

Alle gespeicherten Verbindungen eines Providers, `default` zuerst (Felder wie in `connections[]` oben).

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

Built-in-Profil auf die aktuelle mitgelieferte Fassung setzen; der bisherige Text landet im Verlauf, `customized` wird `false`.

**Response:** `OnboardingProfileResponse` (enthält `customized` — von Hand geändert — und `updateAvailable` — es gibt eine neuere mitgelieferte Fassung als die, auf der das Profil beruht)

### `GET /api/v1/onboarding-profiles/{id}/shipped`

Aktuelle mitgelieferte Fassung eines Built-in-Profils, zum Vergleichen vor dem Übernehmen.

**Response:** `{ "version": 4, "content": { "de": "...", "en": "..." } }` · 404 für eigene Profile

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

`sourceFilter` und `minPriority` werden in beliebiger Schreibweise angenommen und als Konstante
gespeichert; ein anderer Wert → `400`, die Meldung nennt die erlaubten Werte.

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

## Audit Center

Read-Only-Auswertung von Audit-Workflow-Ergebnissen (Gate-Status je Audit-Lauf) und der daraus abgeleiteten Findings, plus Promotion einzelner Findings zu TODOs. Modul `audit` (`automation.modules.audit`) — ist es deaktiviert, antworten alle folgenden Endpunkte mit `404 MODULE_DISABLED`.

### `GET /api/v1/audits`

Alle Audits (gruppiert nach Projekt + `auditKey`) mit dem jeweils neuesten gültigen Report auflisten. Optionale Filter: `projectId`, `category`, `gateStatus`.

**Response:** `AuditSummary[]`

### `GET /api/v1/audits/{auditKey}`

Details eines Audits (Zusammenfassung + neuester Report). Pflicht-Parameter: `projectId`.

**Response:** `AuditDetail`

### `GET /api/v1/audits/{auditKey}/reports`

Alle Reports eines Audits, paginiert (`page`, `size`). Pflicht-Parameter: `projectId`.

**Response:** `PageResponse<AuditReportResponse>`

### `GET /api/v1/audit-reports/{id}`

Einzelnen Audit-Report abrufen.

**Response:** `AuditReportResponse`

Neben Inhalt, Gate und `findingCount` trägt jeder Report (auch in der Liste und in `AuditDetail.latestReport`) die Herkunft seines Laufs, jedes Feld `null`, wenn unbekannt:

| Feld | Bedeutung |
|---|---|
| `workflowVersion` | Versions-Label des Workflows beim Speichern (z. B. die Pool-Version) |
| `triggerSource` | Effektiver Auslöser; bei einem Ketteneintrag der Auslöser der Kette (`SCHEDULE`, `MANUAL`, …). Ein Kettenlauf von vor V89 bleibt `CHAIN`, ein Sub-Workflow-Lauf `SUB_WORKFLOW` |
| `scheduleId` | Auslösender Zeitplan; bei einem Ketteneintrag der Zeitplan der Kette |
| `chainRunId`, `chainId`, `chainName` | Chain-Run und Kette eines Ketteneintrags; `chainRunId` bleibt, wenn die Kette gelöscht ist |
| `definitionVersionNumber` | Nummer der Definitionsversion, die der Lauf ausgeführt hat (leer ohne `automation.hardening.provenance`) |

Die Liste löst Zählung und Herkunft für die ganze Seite mit je einer Abfrage auf, nicht je Report.

### `GET /api/v1/audit-findings`

Findings über alle Audits auflisten, paginiert (`page`, `size`). Optionale Filter: `projectId`, `auditKey`, `status`, `severity`.

**Response:** `PageResponse<AuditFindingResponse>`

### `GET /api/v1/audit-findings/{id}`

Einzelnes Finding abrufen.

**Response:** `AuditFindingResponse`

### `PATCH /api/v1/audit-findings/{id}`

Status oder Dispositions-Notiz eines Findings ändern.

**Request:**

| Feld | Typ | Pflicht |
|---|---|---|
| `status` | Enum (`AuditFindingStatus`) | Nein |
| `dispositionNote` | String | Nein |

**Response:** `AuditFindingResponse`

### `POST /api/v1/audit-findings/{id}/todos`

Ein von MySuricates verwaltetes TODO aus dem Finding erzeugen ("Managed TODO").

**Response:** `AuditFindingResponse`

### `POST /api/v1/audit-findings/{id}/todo-links`

Ein bereits bestehendes TODO manuell mit dem Finding verknüpfen.

**Request:** `{ "todoId": "uuid" }`

**Response:** `AuditFindingResponse`

### `DELETE /api/v1/audit-findings/{id}/todo-links/{todoId}`

Verknüpfung zwischen Finding und TODO wieder lösen.

**Response:** `204 No Content`

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

### `GET /api/v1/system/jvm-memory` (Öffentlich)

Heap-Belegung der JVM als genau drei Zahlen — für Shell-Blöcke wie den Performance-Audit, die keinen
API-Key haben. Die übrige Metrik-Oberfläche unter `/actuator/metrics` verlangt den Key.

**Response:**

```json
{
  "jvm_memory_used_bytes": 268435456,
  "jvm_memory_max_bytes": 1073741824,
  "jvm_heap_used_percent": 25.0
}
```

Die Werte beziehen sich auf den Heap (`MemoryMXBean`), der Anteil ist auf eine Nachkommastelle
gerundet. Kennt die JVM kein Heap-Maximum, sind `jvm_memory_max_bytes` und `jvm_heap_used_percent`
`null`.

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

System-Capabilities abrufen (API-Version, App-Version, verfügbare Routen und Block-Typen) sowie den beim Start festgelegten Modulstatus.

**Response:** `SystemCapabilitiesResponse` — Auszug der Modul-Felder:

```json
{
  "preset": "core",
  "modulesUiEditable": false,
  "modules": [
    {
      "name": "webhooks", "kind": "feature", "enabled": false,
      "tier": "advanced", "maturity": "stable", "risks": ["network"], "requires": [],
      "property": "automation.modules.webhooks", "envVar": "AUTOMATION_MODULES_WEBHOOKS",
      "description": "Inbound webhook triggers that start workflows from outside.",
      "configured": false, "overridden": false
    }
  ]
}
```

Endpunkte deaktivierter Module antworten mit `404` und `{"code": "MODULE_DISABLED"}`. `configured` ist der Wert aus der Startkonfiguration, `overridden` zeigt einen Schalter aus den Settings.

### `PUT /api/v1/system/modules/{name}`

Funktionsmodul zur Laufzeit schalten — nur mit `automation.modules.ui-editable: true`, wirkt sofort und bleibt über Neustarts erhalten. Protokolliert einen `SECURITY`-Eintrag.

**Request:** `{ "enabled": true }`

**Response:** `{ "preset": "core", "uiEditable": true, "modules": [ … ] }` (Modul-Einträge wie oben)

**Fehler:** `403 MODULES_NOT_EDITABLE` (Schalter aus), `400 INVALID_ARGUMENT` (Härtungs-Schalter — nur per Startkonfiguration), `409 STATE_CONFLICT` (Abhängigkeit, z. B. `wishes` braucht `chains`), `404` (unbekanntes Modul)

### `DELETE /api/v1/system/modules/{name}/override`

Schalter aus den Settings entfernen: Das Modul folgt wieder der Startkonfiguration. Gleiche Response und Fehler wie `PUT`.

### `GET /api/v1/system/ai-providers/PI/catalog`

Modell-Katalog des Pi-Providers abrufen.

**Response:** `PiCatalog`

### `GET /api/v1/system/ai-providers/{id}/models`

Modelle, die dieser Provider auf diesem Host anbietet (Host-Discovery via CLI bzw. lokalem Cache, TTL-gecacht). Schlägt nie wegen fehlender CLI fehl — dann `available: false` mit `reason`.

**Response:** `ModelCatalog` (`providerId`, `available`, `source`, `fetchedAt`, `reason`, `models[]`)

### `POST /api/v1/system/ai-providers/{id}/models/refresh`

Cache umgehen und den Host jetzt neu abfragen.

**Response:** `ModelCatalog`

### `GET /api/v1/system/ai-providers/status`

Ob die KI-CLIs auf dem Rechner des Backends bereit sind: je Anbieter das Programm auf dem `PATH`, eine Anmeldung (Anmeldedatei oder Umgebungsvariable — dieselbe Regel wie der Installer, aus `AiProvider#setup()`) und die Befehle zum Installieren und Anmelden. Ruft keine CLI auf; die Antwort ist gecacht (`automation.ai-provider-status.ttl-seconds`, Standard 60). Sie nennt nur Namen und Befehle, nie einen Token oder Dateiinhalt. „Bereit“ heißt: Programm und Anmeldung gefunden — nicht, dass die Anmeldung noch gilt.

**Response:**

```json
{
  "container": false,
  "checkedAt": "2026-09-24T10:00:00Z",
  "providers": [
    {
      "id": "CLAUDE", "cli": "claude", "state": "READY",
      "installed": true, "loggedIn": true, "loginVia": "ENVIRONMENT", "loginSource": "CLAUDE_CODE_OAUTH_TOKEN",
      "install": "curl -fsSL https://claude.ai/install.sh | bash   (or: npm install -g @anthropic-ai/claude-code)",
      "login": "claude auth login", "defaultProvider": true
    },
    {
      "id": "CODEX", "cli": "codex", "state": "LOGIN_MISSING",
      "installed": true, "loggedIn": false, "loginVia": null, "loginSource": null,
      "install": "npm install -g @openai/codex", "login": "codex login", "defaultProvider": false
    },
    {
      "id": "PI", "cli": "pi", "state": "LOGIN_MISSING",
      "installed": true, "loggedIn": false, "loginVia": null, "loginSource": null,
      "install": "npm install -g @earendil-works/pi-coding-agent@<version>", "login": "pi",
      "loginNote": "pi_slash_login", "defaultProvider": false
    }
  ]
}
```

`state`: `READY` (Programm und Anmeldung gefunden), `LOGIN_MISSING` (Programm gefunden, keine Anmeldung), `NOT_INSTALLED` (Programm fehlt). `loginSource`: der Variablenname oder die Anmeldedatei als `~/…` — bzw. als `$VARIABLE/…`, wenn eine Container-Instanz das Verzeichnis umlenkt (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `PI_CODING_AGENT_DIR`). `container: true` heißt, das Backend läuft in einem Container: Es zählen Programme und Anmeldungen darin.
`login` ist nur der Befehl; was danach zu tun ist, steht als Schlüssel in `loginNote` (fehlt, wenn der Befehl alles
sagt) — die Oberfläche übersetzt ihn (`aiProviderStatus.loginNotes`), der Installer ebenso (`M_login_note_<schlüssel>`).

### `POST /api/v1/system/ai-providers/status/refresh`

Cache umgehen und jetzt neu prüfen, etwa direkt nach einer Anmeldung.

**Response:** wie `GET /api/v1/system/ai-providers/status`

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

`backup-retain-count` nimmt 1–365; ein anderer Wert → `400`, gespeichert wird dann nichts. Der
gespeicherte Wert gilt ab der nächsten Rotation und nach einem Neustart weiter (sonst
`automation.backup.retain-count`).

### `GET /api/v1/system/tray-config`

Laufzeit-Einstellungen des Host-Tray-Icons abrufen.

**Response:**

```json
{
  "popupsEnabled": true,
  "notifyApprovals": true,
  "notifyFailures": true,
  "notifySuccess": false,
  "publicUrl": "https://mysuricates.example.com"
}
```

### `PUT /api/v1/system/tray-config`

Tray-Einstellungen aktualisieren. Teil-Updates sind erlaubt. Externe Browserziele müssen HTTPS verwenden; HTTP ist nur für Loopback-Adressen zulässig. Das Browserziel ist von den internen Healthcheck-URLs getrennt.

**Request/Response:** dieselben Felder wie bei `GET`; die Response enthält den vollständigen validierten Stand.

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
| `run-retention-days` | 0–730 |
| `run-retention-max-per-night` | 10–5000 |
| `dashboard-layout-retention-count` | 1–1000 |
| `model-catalog-ttl-seconds` | 30–86400 |
| `compress-ai-max-input-chars` | 1000–400000 |
| `todo-similarity-keyword-threshold` | 0.05–0.9 |
| `dashboard-report-assist-enabled` | 0–1 |
| `request-metrics-enabled` | 0–1 |
| `request-metrics-retention-days` | 0–730 |
| `request-metrics-slow-request-ms` | 0–600000 |
| `request-metrics-drift-detection-enabled` | 0–1 |
| `request-metrics-drift-factor` | 1.1–100.0 |
| `request-metrics-drift-min-calls` | 1–100000 |
| `request-metrics-baseline-days` | 1–365 |

Die Liste ist der Stand von `ExecutionSettingKeys.BOUNDS`. Ein Schlüssel ohne Grenzen dort ist
über diesen Endpunkt **nicht** erreichbar, auch wenn ein Applier existiert — zwei Wächter-Tests
in `ExecutionSettingsServiceTest` halten beide Seiten deckungsgleich (TODO fc0994d6).

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

Markdown-Integration-Snippet für ein Projekt rendern (für AGENTS.md o.ä.): Basis-Onboarding-Profil mit Adresse und Key-Befehl dieser Instanz, dazu die Workflows des Projekts.

**Query-Parameter:** `projectPath` (optional), `locale` (`de`/`en`, optional; Standard `en`)

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

**Query-Parameter:** `days` (Standard: 30), `projectId` (optional). `projectId` schränkt alle Werte auf die
Workflows und Läufe dieses Projekts ein — auch die Gesamtzahlen (`totalWorkflows`, `totalRuns`, `runsToday`,
`successRate`, `topWorkflows`), die unabhängig von `days` über die gesamte Laufzeit zählen.

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

## Dashboard-Layouts

Verlauf des Dashboard-Layouts (alle Ansichten), geräteübergreifend. Das Frontend speichert beim Beenden der Bearbeitung einen Snapshot. Unbenannte Versionen werden auf die neuesten `dashboard-layout-retention-count` gekürzt (Default 100, siehe `/system/execution-config`), benannte bleiben.

### `GET /api/v1/dashboard/layouts`

Versionen, neueste zuerst, **ohne** Layout-Inhalt.

**Query-Parameter:** `page` (Standard: 0), `size` (Standard: 20, höchstens 100)

**Response:** `PageResponse<DashboardLayoutVersionSummary>`

```json
{
  "content": [
    { "id": "version-uuid", "createdAt": "2026-09-15T10:00:00Z", "deviceLabel": "Browser 3f2a",
      "name": null, "formatVersion": 2, "viewCount": 1, "contentHash": "9b1c…" }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

### `GET /api/v1/dashboard/layouts/{id}`

Eine Version mit Inhalt: Felder wie oben plus `layout` (`{ "activeViewId": …, "views": [ … ] }`).

### `POST /api/v1/dashboard/layouts`

Speichert einen Snapshot. Body höchstens 256 KiB; unbekannte Felder → 400.

```json
{
  "deviceLabel": "Browser 3f2a",
  "formatVersion": 2,
  "baseVersionId": "version-uuid",
  "force": false,
  "layout": { "activeViewId": "default",
              "views": [ { "id": "default", "name": "", "widgets": [ { "instanceId": "todos", "type": "todos" } ] } ] }
}
```

- **201** neue Version; **200** Inhalt identisch mit der neuesten Version (keine neue Zeile).
- Der Dedupe-Hash (`contentHash`) lässt `activeViewId` aus: Die angezeigte Ansicht wählt jedes Gerät selbst, ein Snapshot, der sich nur darin unterscheidet, legt keine neue Version an (**200**). Gespeichert und geprüft (muss auf eine Ansicht zeigen) wird `activeViewId` weiterhin.
- **409 `STATE_CONFLICT`**: Die neueste Version ist nicht `baseVersionId` — ein anderes Gerät hat gespeichert. `"force": true` speichert trotzdem.
- **413**: Body größer als `automation.dashboard.layout.max-request-bytes` (Default 262144).
- Grenzen wie im Frontend (`LAYOUT_LIMITS`): ≤ 20 Ansichten, ≤ 100 Widgets je Ansicht, IDs `^[a-z0-9][a-z0-9-]*$` mit ≤ 80 Zeichen, Ansichtsnamen ≤ 80 Unicode-Codepoints, `config` nur string (≤ 500) / Zahl / boolean mit ≤ 20 Keys `^[a-zA-Z][a-zA-Z0-9]{0,39}$`, `filter.days` 1–365.

### `PATCH /api/v1/dashboard/layouts/{id}`

Body `{ "name": "vor dem Umbau" }` benennt eine Version (1–80 Zeichen), `{ "name": null }` entfernt den Namen. Benannte Versionen werden nie automatisch gelöscht.

---

## Dashboard-Workflow-Widgets

Daten der Workflow-Widgets des Dashboards (Status-Widget und Ergebnis-Widget). Kern-Endpunkt, kein Modul-Gate.

### `GET /api/v1/dashboard/workflow-results`

Je angefragtem Workflow der **letzte Run** und das geprüfte Ergebnis — **ohne** Step-Ausgaben.

**Query-Parameter:** `workflowIds` (mehrfach oder kommagetrennt, höchstens 50; jede ID muss `^[a-z0-9][a-z0-9-]{0,79}$` erfüllen, sonst **400**). Ohne Parameter: leere Liste.

**Response:** ein Eintrag je angefragter ID, in Anfragereihenfolge.

```json
[
  { "workflowId": "wf-a1", "workflowName": "Nightly QA", "available": true,
    "run": { "runId": "run-1", "status": "COMPLETED", "startedAt": "2026-09-18T08:00:00Z",
             "completedAt": "2026-09-18T08:02:00Z", "durationMs": 120000, "gateStatus": "GREEN" },
    "resultState": "VALID",
    "result": { "title": "Nightly QA", "status": "YELLOW",
                "kpis": [{ "label": "Hunts", "value": "1361", "trend": "UP" }],
                "items": ["3 Hunts ohne Route"], "summary": "Zwei Befunde." } }
]
```

- `available: false` (soft-gelöschter oder unbekannter Workflow) ⇒ `workflowName`, `run` und `result` sind `null`, `resultState` ist `MISSING`.
- `resultState`: `VALID` (Vertrag erfüllt), `INVALID` (Marker vorhanden, aber abgelehnt), `MISSING` (kein Marker). Nur bei `VALID` steht `result`.

### Marker `dashboard_widget:` im Workflow

> **Vorrang:** Hat ein `dashboard-report`-Block des Runs bereits ein geprüftes Ergebnis geschrieben, wird der Marker gar nicht ausgewertet — auch ein kaputter Marker setzt dann kein `dashboard_widget_error`. Der Block ist der bewusst konfigurierte Weg, der Marker der opportunistische.

Ein Workflow meldet sein Dashboard-Ergebnis wie `gate_status:` als Markerzeile in der Blockausgabe; das JSON darf über mehrere Zeilen gehen:

```
dashboard_widget: {"title": "Nightly QA", "status": "YELLOW",
  "kpis": [{"label": "Hunts", "value": "1361", "trend": "UP"}],
  "items": ["3 Hunts ohne Route"], "summary": "Zwei Befunde."}
```

Vertrag (beim Run-Ende geprüft und in die Run-Metadaten geschrieben, beim Lesen erneut geprüft):

| Feld | Pflicht | Regel |
|---|---|---|
| `status` | ja | `GREEN`, `YELLOW` oder `RED` |
| `title` | nein | Text, ≤ 80 Zeichen |
| `kpis` | nein | ≤ 6 Objekte mit `label` (≤ 40), `value` (≤ 40, **String**) und optional `trend` (`UP`/`DOWN`/`FLAT`) |
| `items` | nein | ≤ 10 Texte, je ≤ 200 Zeichen |
| `summary` | nein | Text, ≤ 500 Zeichen |

- **Unbekannte Felder ⇒ der ganze Vertrag wird abgelehnt** (auch in einem KPI-Objekt).
- Texte sind einzeilig: leer, zu lang oder mit Steuer-, Zero-Width-, Bidi-, Separator-, Private-Use-Zeichen oder einzelnen UTF-16-Surrogaten ⇒ abgelehnt.
- KPI-Werte sind Strings; eine Zahl an einer String-Stelle ist ein Vertragsbruch.
- Das JSON wird höchstens 8192 Zeichen weit gelesen; schließt die Klammer bis dahin nicht, gilt der Marker als ungültig.
- Melden mehrere Blöcke einen Marker, gewinnt der **letzte**; Ausgaben von Komprimierungs-Blöcken (`derivedOutput`) werden übersprungen.
- **Innerhalb *einer* Blockausgabe gilt das Gegenteil: zwei Marker lehnen diese Ausgabe komplett ab** (`resultState: INVALID`, kein Widget-Ergebnis). Blockausgaben zitieren häufig fremden Text, und ein zitierter Marker darf das eigene Ergebnis des Berichts nie überstimmen — im Zweifel lieber kein Ergebnis als ein falsches. Wer den Marker in einem Block zweimal ausgibt, sieht deshalb „kein gültiges Ergebnis".
- Den eigenen Marker deshalb **am Zeilenanfang** schreiben, mit dem JSON direkt dahinter (ein Codeblock in der nächsten Zeile ist ebenfalls in Ordnung). Steht er mitten in einer Zeile *und* trennt ihn eine Leerzeile, ein geschütztes Leerzeichen (U+00A0) oder ein NEL (U+0085) von seinem JSON, wird er nicht erkannt — dann kann ein aus fremdem Text zitierter Marker der einzige erkannte sein.
- Der Inhalt erscheint im Dashboard **ausschließlich als Text** — kein Markdown, kein HTML, keine Links, keine Bilder. Der Vertrag enthält bewusst kein ID- und kein URL-Feld.

### WebSocket `/topic/dashboard/runs`

Nach dem Speichern eines beendeten Runs: `{"type":"run_completed","runId":"run-1","workflowId":"wf-a1","status":"COMPLETED"}`. Nur IDs und Status — das Dashboard lädt daraufhin seine Workflow-Widgets neu.

### `POST /api/v1/dashboard/report-assist`

KI-Vorschlag für die Felder eines `dashboard-report`-Blocks, abgeleitet aus dem **letzten Run** des Workflows. Ein einziger werkzeugloser KI-Aufruf (Anbieter `CLAUDE`, kein Modell-Flag); die Antwort wird im Backend gegen denselben Vertrag geprüft wie ein Marker. Roh-Antwort und Run-Ausgabe verlassen den Server nie.

Modul `dashboard-report`; zusätzlich muss die Einstellung `dashboard-report-assist-enabled` (siehe `/system/execution-config`) auf `1` stehen.

**Body:** `{ "workflowId": "wf-a1" }`

**Response:**

```json
{ "runId": "run-1", "runCompletedAt": "2026-09-18T08:02:00Z", "provider": "CLAUDE",
  "proposal": { "title": "Nightly QA", "status": "YELLOW",
                "kpis": [{ "label": "Hunts", "value": "1361", "trend": "UP" }],
                "items": ["3 Hunts ohne Route"], "summary": "Ein Befund." } }
```

- **400**: `workflowId` fehlt oder ist leer.
- **404**: Modul aus (`MODULE_DISABLED`) oder Workflow unbekannt/soft-gelöscht.
- **409**: Einstellung aus, Claude-CLI auf diesem Host nicht verfügbar (geprüft über den Modellkatalog des Anbieters `CLAUDE`), kein Run oder keine Ausgabe im letzten Run, KI antwortet nicht oder nicht vertragskonform. Die Meldung ist fest — sie enthält nie einen Teil der Antwort.

Der Vorschlag beschreibt normalerweise nur einen Lauf, ohne Variablen. Für dauerhafte Kennzahlen im Block `{{ variable }}` eintragen.

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

**Query-Parameter:** `page`, `size`, `status`, `search` — `status` wie bei `GET /api/v1/executions`: Groß- und Kleinschreibung egal, ein unbekannter Wert ergibt `400` mit den erlaubten Werten.

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
  "chainName": "Meine CI/CD Pipeline",
  "locale": "de"
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `projectId` | String | Ja |
| `chainName` | String | Nein |
| `locale` | String (`de`/`en`) | Nein: Sprache der Namen, Labels und der Schritte zweisprachiger Templates; ohne Angabe Deutsch |

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
| `goalId` | String | Nur TODOs dieses Ziels (V85); `none` = nur TODOs ohne Ziel (Kandidaten der Relationskarte) |

Bei `status`, `priority` und `source` zählt die Groß- und Kleinschreibung nicht. Ein unbekannter Wert ergibt `400` (`INVALID_ARGUMENT`) mit den erlaubten Werten, etwa `status muss OPEN, IN_PROGRESS, DONE, BACKLOG oder ARCHIVED sein, nicht 'BLOCKED'`.

**Response:** `PageResponse<TodoResponse>` – mit `planning` (Ziel, Rang, Einschätzung), sobald davon etwas gesetzt ist

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
  "agentSessionRef": "4fde1b91-2cce-4ab2-b7d0-fd2a138f9322",
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
| `agentSessionRef` | String | Nein — UUID der Agenten-Konversation (`claude --resume <uuid>`). Ein ganzer Befehl wird angenommen, die UUID daraus übernommen; was keine UUID ist, wird verworfen. |
| `workflowRunId` | String | Nein |
| `referenceType` | String | Nein |
| `referenceId` | String | Nein |
| `dueDate` | Instant | Nein |

**Response:** `201` mit `TodoResponse`

**TodoResponse-Felder:** `id`, `title`, `description`, `status`, `priority`, `tags`, `projectId`, `projectName`, `linkedWorkflowId`, `linkedWorkflowName`, `source`, `sourceAgent`, `sessionId`, `agentSessionRef`, `workflowRunId`, `referenceType`, `referenceId`, `exportedProvider`, `exportedRef`, `exportedUrl`, `fixAttempts`, `lastFixRunId`, `resolutionState` (`IDLE`, `RUNNING`, `FAILED`, `SUCCEEDED`), `dueDate`, `createdAt`, `updatedAt`

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

Anzahl TODOs nach Status. Optionaler Query-Parameter `projectId` zählt nur die TODOs dieses Projekts (Dashboard-Projektfilter).

**Response:** `TodoCountResponse` mit `open`, `inProgress`, `done`, `backlog`, `archived`

### `GET /api/v1/todos/overview`

TODO-Übersicht mit Projekt-Rollups und "Needs You"-Items.

**Response:** `TodoOverviewResponse` mit `projects` (List), `needsYou` (List), `generatedAt`, `staleThresholdDays`, `milestonesDue` (V88: Meilensteine aktiver Ziele mit `DUE_SOON`/`OVERDUE` – `id`, `title`, `goalId`, `goalTitle`, `dueDate`, `state`, `total`, `done`)

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

### `PATCH /api/v1/todos/{id}/planning`

Ziel, Rang, Meilenstein und Einschätzung eines TODOs setzen (V85, `milestoneId` V88). `null` lässt ein Feld stehen, ein leerer Text leert es: `goalId: ""` löst das TODO vom Ziel, `milestoneId: ""` vom Meilenstein, `openQuestion: ""` markiert die Frage als geklärt. `milestoneId` muss ein Meilenstein des eigenen Ziels sein (sonst 400, unbekannt 404; `goalId` im selben Request zählt zuerst); wechselt oder verlässt das TODO sein Ziel, wird `milestoneId` geleert. `planning` in der Antwort trägt `milestoneId`. `goalRank <= 0` nimmt den Rang zurück. Ohne `ratedBy` gilt die Einschätzung als menschlich (`USER`); `ratedAt` setzt der Server, sobald `complexity`, `suggestedEffort` oder `ratingNote` mitkommt.

```json
{
  "goalId": "uuid",
  "goalRank": 2,
  "complexity": "M",
  "suggestedEffort": "high",
  "ratingNote": "Viele Dateien, aber klare Vorlage",
  "openQuestion": "Welcher Video-Stil?",
  "ratedBy": "AI",
  "milestoneId": "uuid"
}
```

`complexity`: `S`, `M`, `L`, `XL` · `suggestedEffort`: `low`, `medium`, `high`, `xhigh` · andere Werte → 400.

**Response:** `TodoResponse`

---

## Ziele

Ein Ziel sammelt TODOs projektübergreifend und zeigt den Fortschritt (V85). Siehe [architektur.md](architektur.md#ziele--todos-projektübergreifend-einordnen-v85).

### `GET /api/v1/goals`

Ziele mit Fortschritt, neueste zuerst. **Query:** `status` (mehrfach; ohne Angabe `ACTIVE` und `DONE`, archivierte nur ausdrücklich).

**Response:** `GoalResponse[]` – `id`, `title`, `description`, `status`, `targetDate`, `createdAt`, `updatedAt`, `progress` (`total`, `open`, `inProgress`, `backlog`, `done`, `openQuestions`, `percentDone`; `total` ohne ARCHIVED), `guard` (`enabled`, `workflowId`, `scheduleId`, `nextRunAt`; nie eingeschaltet = `enabled: false`, Rest `null`).

### `POST /api/v1/goals`

```json
{ "title": "Open-Source-Launch", "description": "Repo öffentlich, README mit Video", "targetDate": "2026-10-15" }
```

`targetDate` als Datum oder ISO-Zeitpunkt. **Response:** `201` + `GoalResponse`

### `GET /api/v1/goals/{id}`

Ziel mit seinen TODOs in Arbeitsreihenfolge (Rang, dann Priorität, dann Alter) – ohne Beschreibungen – und Fortschritt je Projekt.

**Response:** `GoalDetailResponse` – `goal`, `todos[]` (`id`, `title`, `status`, `priority`, `projectId`, `projectName`, `tags`, `goalRank`, `complexity`, `suggestedEffort`, `ratingNote`, `ratedBy`, `ratedAt`, `openQuestion`, `updatedAt`, `dueDate`, `milestoneId`), `byProject[]` (`projectId`, `projectName`, `total`, `done`), `milestones[]` (`GoalMilestoneResponse`, nach Datum). Erkennt das Lesen einen Meilenstein zum ersten Mal als erreicht, speichert es `reachedAt`.

### `GET /api/v1/goals/{id}/relations`

Relationskarte: je TODO des Ziels, woher es kommt, welcher Workflow es bearbeitet und was der letzte Fix-Lauf ergab. Ein Ziel ist mit Workflows nie direkt verknüpft – nur über diese Beziehungen seiner TODOs. **Query:** `include` – bis zu 20 TODO-IDs (kommagetrennt oder mehrfach), die noch keinem Ziel gehören; sie kommen als Kandidaten (`candidate: true`) hinter die TODOs des Ziels. IDs aus einem Ziel werden übergangen.

**Response:** `GoalRelationsResponse` – `goalId`, `activeTriggers` (aktive TodoTrigger insgesamt), `todos[]` in Arbeitsreihenfolge mit `id`, `title`, `status`, `priority`, `projectId`, `projectName`, `tags`, `goalRank`, `candidate` und

- `origin` – `kind` (`AUDIT` | `HEALTH` | `WORKFLOW_RUN` | `AGENT` | `USER`, in dieser Reihenfolge geprüft), `workflowId`/`workflowName` des erzeugenden Laufs (oder der `workflow_run`-Referenz), `runId`, `runStatus`, `findingIds` (Audit-Befunde aus `reference_id` und `audit_finding_todos`), `agent` (`sourceAgent`)
- `workers[]` – `kind` `LINKED` (Fix-Workflow am TODO) oder `TRIGGER` (jeder aktive TodoTrigger, dessen Tags, Projekt, Quelle und Mindest-Priorität passen), `workflowId`, `workflowName`, `triggerId`, `triggerLabel`; gelöschte Workflows fallen weg
- `result` – `runId` (letzter Fix-Lauf), `workflowId`, `runStatus`, `at` (Ende, solange er läuft der Start), `fixAttempts`, `resolutionState`; `null`, solange nie ein Fix-Lauf lief

Gebündelt gelesen: eine Abfrage je Art, unabhängig von der Zahl der TODOs (`RequestCostRatchetTest`). Unbekanntes Ziel → `404`.

### `PUT /api/v1/goals/{id}`

Teiländerung: `title`, `description`, `status`, `targetDate` (`null` = unverändert, leer = leeren). **Response:** `GoalResponse`

### `DELETE /api/v1/goals/{id}`

Löscht das Ziel; seine TODOs bleiben und verlieren nur die Zuordnung (samt Meilenstein), seine Meilensteine fallen weg, ein Ziel-Wächter pausiert (sein Workflow bleibt). **Response:** `204`

### `POST /api/v1/goals/{id}/todos`

TODOs zuordnen: `{ "todoIds": ["…"] }`. Ein TODO aus einem anderen Ziel wechselt hierher und verliert seinen Meilenstein; neu Zugeordnete bekommen keinen Rang. **Response:** `GoalDetailResponse`

### `PUT /api/v1/goals/{id}/order`

Reihenfolge setzen: `{ "todoIds": ["…"] }` → Rang 1..n in dieser Folge, alle übrigen TODOs des Ziels verlieren den Rang. Jede ID muss schon zum Ziel gehören, sonst 400. **Response:** `GoalDetailResponse`

### Meilensteine (V88)

Ein Meilenstein ist ein eigener Eintrag mit Titel und Datum; TODOs des Ziels werden ihm über `PATCH /todos/{id}/planning` (`milestoneId`) zugeordnet. Der Zustand wird beim Lesen berechnet: `REACHED` (alle zugeordneten TODOs ohne ARCHIVED erledigt, mindestens eins), sonst `OVERDUE`, `DUE_SOON` (≤ 3 Tage) oder `OPEN`. `reachedAt` hält das erste Erkennen fest und bleibt; ist der Meilenstein danach wieder offen, steht `reopened: true`.

**`GoalMilestoneResponse`:**

```json
{
  "id": "b1f0…", "goalId": "6c2e…", "title": "Beta fertig",
  "dueDate": "2026-10-01T21:59:59Z", "state": "DUE_SOON",
  "total": 4, "done": 2, "reachedAt": null, "reopened": false,
  "createdAt": "2026-09-24T08:00:00Z", "updatedAt": "2026-09-24T08:00:00Z"
}
```

- `GET /api/v1/goals/{id}/milestones` – nach Datum. **Response:** `GoalMilestoneResponse[]`
- `POST /api/v1/goals/{id}/milestones` – `{ "title": "Beta fertig", "dueDate": "2026-10-01T21:59:59Z" }`; `dueDate` Pflicht, ISO-Zeitpunkt oder Datum (`2026-10-01` = bis 23:59:59 UTC); Titel höchstens 200 Zeichen. **Response:** `201` + `GoalMilestoneResponse`
- `PUT /api/v1/goals/{id}/milestones/{milestoneId}` – Teiländerung `{ "title"?, "dueDate"? }` (`null` = unverändert). **Response:** `GoalMilestoneResponse`
- `DELETE /api/v1/goals/{id}/milestones/{milestoneId}` – die TODOs bleiben im Ziel und verlieren nur die Zuordnung. **Response:** `204`

Unbekanntes Ziel oder ein Meilenstein eines anderen Ziels → `404`.

### `POST /api/v1/goals/{id}/guard`

Ziel-Wächter ein- oder ausschalten (V88): `{ "enabled": true, "projectId": "…"?, "cron": "0 0 8 * * *"?, "locale": "de"? }`.

- **Erstes Einschalten:** legt aus der mitgelieferten Vorlage `goal-guard` einen Workflow „Ziel-Wächter: <Titel>“ (`locale` `en`: „Goal guard: …“) mit `defaultContext` `{goal_id, goal_locale}` an (`changeSource` `goal-guard`) und dazu einen CRON-Zeitplan (Vorgabe täglich 08:00, `inputContext.goal_id`, Konflikt `SKIP`). Projekt: `projectId`, sonst das mit den meisten TODOs des Ziels, sonst das älteste aktive; ohne Projekt → 400.
- **Wieder einschalten:** aktiviert den vorhandenen Zeitplan; ein gesetzter `cron` ersetzt den bisherigen. Ist der Workflow gelöscht, entsteht ein neuer.
- **Ausschalten** (`enabled: false`): pausiert den Zeitplan, der Workflow bleibt. Löschen des Ziels pausiert ihn ebenso.

Ungültiger `cron` → 400, Modul `dashboard-report` aus → 409, unbekanntes Ziel/Projekt → 404. **Response:** `GoalResponse` mit `guard`:

```json
{ "id": "6c2e…", "title": "Open-Source-Launch", "…": "…",
  "guard": { "enabled": true, "workflowId": "9a4d…", "scheduleId": "e71b…", "nextRunAt": "2026-09-25T06:00:00Z" } }
```

Den Lauf „jetzt prüfen“ startet `POST /api/v1/workflows/{workflowId}/execute`; das Ergebnis erscheint als Dashboard-Kachel des Workflows.

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

Bei `level` und `category` zählt die Groß- und Kleinschreibung nicht. Ein unbekannter Wert ergibt `400` (`INVALID_ARGUMENT`) mit den erlaubten Werten, etwa `level muss DEBUG, INFO, WARN oder ERROR sein, nicht 'TRACE'`.

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

**Query-Parameter:** `page` (String, Pflicht), `locale` (`de` | `en`, Vorgabe `de` – Vorschläge gibt es je Sprache)

**Response:** `String[]`

### `POST /api/v1/assistant/suggestions/refresh`

Vorschläge für eine Seite neu generieren.

**Query-Parameter:** `page` (String, Pflicht), `locale` (`de` | `en`, Vorgabe `de`)

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

Alle verfügbaren Pool-Workflows auflisten. Query `locale` (optional): Sprache der Schritte zweisprachiger Pool-Dateien, ohne Angabe Englisch.

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
  "forceCreate": false,
  "locale": "de"
}
```

| Feld | Typ | Pflicht |
|---|---|---|
| `projectId` | String | Ja |
| `name` | String | Nein |
| `forceCreate` | Boolean | Nein |
| `locale` | String (`de`/`en`) | Nein: Sprache von Name, Beschreibung und Schritten; ohne Angabe Englisch. Ein früher in der anderen Sprache installierter Workflow wird aktualisiert, nicht dupliziert |

**Response:** `{ "workflowId": "uuid", "workflowName": "...", "created": true }`

### `POST /api/v1/pool/export/{workflowId}`

Bestehenden Workflow in den Pool exportieren.

**Query-Parameter:** `scope` = `repo` | `portable` (optional, Default `portable`) — Pool-Metadatum, das repo-gebundene von portablen Einträgen trennt. Ein unbekannter Wert ergibt `400`. Regelwerk: [workflow-pool-kuratierung.md](workflow-pool-kuratierung.md).

Der Export schreibt keine Instanz-Bindungen mit: `projectId`, `workflowId`, `runId`, `scheduleId`, `chainId`, `executionId` mit konkretem Wert und UUID-Werte fallen weg, eine UUID innerhalb eines Textes wird durch `REDACTED-UUID` ersetzt.

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

**Response:** `201` mit `ExportToPoolResponse` (`poolId`, `filename`, `sanitizedPaths`). `sanitizedPaths` nennt die bereinigten Stellen mit Pfad — bei einem `sub-workflow`-Block steht dort `blocks[N].config.workflowId`, und der Eintrag ist erst nach dem Nachtragen dieser Referenz installierbar.

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

JSON-Objekte, unterschieden über `type`. Step-Nachrichten (Placeholder beim Start und fertiger Step) haben kein `type`:

```json
{ "stepIndex": 1, "status": "COMPLETED", "input": "...", "output": "...", "durationMs": 4200, "blockType": "shell", "blockName": "Build" }
```

Weitere Typen: `status`, `approval_required`/`approval_resolved`, `human_input_required`/`human_input_resolved`,
`agentic_*`, `foreach_item_started`/`foreach_item_completed`, `container_starting`/`container_started`/`container_skipped`,
`command` und `live_output` — Ausgabe laufender Blöcke, gebündelt höchstens alle 250 ms:

```json
{
  "type": "live_output",
  "runId": "run-uuid",
  "bufferId": "buffer-uuid",
  "lines": [{ "seq": 41, "stepIndex": 1, "kind": "line", "text": "[INFO] BUILD SUCCESS", "ts": 1757844005000 }],
  "skipped": 0
}
```

`seq` ist pro `bufferId` lückenlos; Lücken bedeuten übersprungene Zeilen. Nachladen: `GET /api/v1/executions/{id}/live-log`.

### Terminal-WebSocket

```
Endpunkt: ws://localhost:9090/ws/terminal/{runId}?ticket=...
```

Für Echtzeit-Container-Zugriff (interaktive Shell). Raw WebSocket (kein SockJS/STOMP). Max. 3 Sessions pro Container. Authentifizierung über ein kurzlebiges Einmal-Ticket aus `POST /api/v1/ws-ticket` (API-Key im Header) — ein `apiKey`-Query-Parameter wird abgelehnt. Gleiches gilt für den STOMP/SockJS-Handshake auf `/ws`.

### Handshake-Ticket

```
POST /api/v1/ws-ticket
Header: X-API-Key: <key>
```

Antwort:

```json
{ "ticket": "kxJ9…", "expiresInSeconds": 30 }
```

Genau einmal einlösbar, danach ungültig; nach Ablauf ebenso. Für jeden Verbindungsversuch (auch jeden Reconnect) ein frisches Ticket holen.
