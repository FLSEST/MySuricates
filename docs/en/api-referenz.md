# API Reference

## General

- **Base URL:** `http://localhost:9090/api/v1`
- **Content-Type:** `application/json`
- **Authentication:** Header `X-API-Key: <key>` (when `automation.security.enabled=true`)

### Error Format

All errors follow this format:

```json
{
  "code": "INTERNAL_ERROR",
  "error": "Error message",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

The `details` field is only included when needed (`@JsonInclude(NON_NULL)`).

**Error Codes:**

| Code | HTTP Status |
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

For validation errors (`VALIDATION_FAILED`, 400) additionally:

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

### HTTP Status Codes

| Code | Meaning |
|---|---|
| 200 | Success |
| 201 | Resource created |
| 202 | Accepted (asynchronous processing) |
| 204 | Deleted (no body) |
| 400 | Validation error |
| 404 | Resource not found |
| 409 | Conflict (invalid state) |
| 429 | Rate limit exceeded |
| 500 | Internal server error |
| 503 | Service unavailable (e.g. test instance unhealthy) |
| 504 | Operation timeout |

---

## Projects

### `GET /api/v1/projects`

List all projects.

**Response:** `ProjectResponse[]`

```json
[
  {
    "id": "uuid",
    "name": "My Project",
    "subtitle": null,
    "description": null,
    "type": "CODE",
    "path": "/workspace/project",
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

Create a new project.

**Request:**

```json
{
  "name": "My Project",
  "type": "CODE",
  "path": "/workspace/project",
  "config": {}
}
```

| Field | Type | Required | Values |
|---|---|---|---|
| `name` | String | Yes | |
| `subtitle` | String | No | |
| `description` | String | No | |
| `type` | Enum | Yes | `CODE`, `CONTENT`, `RESEARCH`, `BUSINESS`, `PERSONAL` |
| `path` | String | Yes | Path in the container |
| `config` | Object | No | Free-form JSON |
| `aiContext` | String | No | AI context for the project |

**Response:** `201` with `ProjectResponse`

### `POST /api/v1/projects/example`

Create the bundled example project, meant for a fresh installation. The UI offers it while there is no project yet.

- **Directory:** The template `classpath:examples/example-project/` is copied to `<automation.examples.directory>/example-project`. The default is `mysuricates-examples` below the workspace root, or `./data/examples` next to the database without a workspace root. The environment variable for it is `AUTOMATION_EXAMPLES_DIR`. An existing directory is never overwritten.
- **Workflows:** one workflow per shipped template listed in `ExampleProjectService.EXAMPLE_TEMPLATE_IDS` (currently the blueprint `blueprint-approval`), with `changeSource` `example-project`.
- **Idempotent:** An active project that already points at the directory is returned, and missing example workflows are added.

| Parameter | Type | Required | Values |
|---|---|---|---|
| `locale` | Query | No | `de`, `en` (default `en`): language of the project name and the example workflows |

**Response:** `201` with `ProjectResponse` when the project was created, `200` otherwise.

### `GET /api/v1/projects/{id}`

Retrieve a single project.

**Response:** `ProjectResponse`

### `PUT /api/v1/projects/{id}`

Update a project.

**Request:**

```json
{
  "name": "New Name",
  "subtitle": "Subtitle",
  "description": "Description",
  "type": "RESEARCH",
  "path": "/workspace/new",
  "config": {},
  "aiContext": "..."
}
```

All fields are optional; only provided fields are updated. Editable are `cronExpression` (`CRON` only),
`scheduledAt` (`ONCE` only), `description`, `inputContext`, `endDate` and `maxRuns` (`CRON` only),
`catchUpOnResume` and `conflictBehavior` (`SKIP`/`OVERLAP`, since 2026-09-24; any other value → `400`).
Target and type of a schedule stay fixed.

**Response:** `ProjectResponse`

### `DELETE /api/v1/projects/{id}`

Delete a project (including container and workflows).

**Response:** `204 No Content`

### `GET /api/v1/projects/archived`

List archived projects.

**Response:** `ProjectResponse[]`

### `POST /api/v1/projects/{id}/restore`

Restore an archived project.

**Response:** `ProjectResponse`

### `POST /api/v1/projects/{id}/start`

Start the Docker container for the project.

**Response:** `ProjectResponse` (status changes to `STARTING` → `RUNNING`)

### `POST /api/v1/projects/{id}/stop`

Stop the Docker container.

**Response:** `ProjectResponse` (status changes to `STOPPED`)

### `GET /api/v1/projects/{id}/workflows`

List all workflows of a project.

**Response:** `WorkflowResponse[]`

### `POST /api/v1/projects/{id}/generate-context`

Generate AI context for the project (analyzes the project directory).

**Response:** `{ "context": "..." }`

---

## Workflows

### `GET /api/v1/workflows`

List all workflows.

**Response:** `WorkflowResponse[]`

```json
[
  {
    "id": "uuid",
    "projectId": "uuid",
    "projectName": "My Project",
    "projectSubtitle": null,
    "projectDescription": null,
    "name": "Code Review",
    "description": "Automated code review",
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

Create a new workflow.

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Code Review",
  "description": "Automated code review",
  "icon": "🔍",
  "blocks": [
    { "type": "simple", "config": { "command": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
  ],
  "activatedSkillIds": ["skill-uuid-1"]
}
```

| Field | Type | Required |
|---|---|---|
| `projectId` | String | Yes |
| `name` | String | Yes |
| `description` | String | No |
| `icon` | String | No |
| `blocks` | BlockDefinition[] | Yes |
| `activatedSkillIds` | String[] | No |
| `instructions` | String | No |
| `model` | String | No |
| `provider` | String | No |
| `templateId` | String | No |
| `metadata` | Object | No |
| `defaultContext` | Object | No |
| `version` | String | No |

**BlockDefinition Fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `type` | String | Yes | Block type (simple, shell, agentic, ...) |
| `name` | String | No | Human-readable name for `{{ block.<name>.output }}` references |
| `config` | Object | Yes | Type-specific configuration |
| `requiredCapabilities` | String[] | No | Required capabilities |
| `skillIds` | String[] | No | Skill IDs additionally activated for this block on top of workflow skills (additive) |

**Response:** `201` with `WorkflowResponse`

### `GET /api/v1/workflows/{id}`

Retrieve a single workflow.

**Response:** `WorkflowResponse`

### `PUT /api/v1/workflows/{id}`

Update a workflow.

**Request:**

```json
{
  "name": "New Name",
  "description": "New description",
  "icon": "✨",
  "blocks": []
}
```

**Response:** `WorkflowResponse`

The server decides the change's origin (`changeSource` of the new version). Sealed workflows: definition changes → `409 WORKFLOW_SEALED`, renaming still works.

### `DELETE /api/v1/workflows/{id}`

Delete a workflow.

**Response:** `204 No Content`

### `GET /api/v1/workflows/export/{id}`

Export a workflow as portable JSON (without IDs, without project assignment).

**Response:** `ExportWorkflowResponse`

```json
{
  "name": "Code Review",
  "description": "Automated code review",
  "icon": "🔍",
  "blocks": [
    { "type": "simple", "config": { "command": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
  ],
  "instructions": null,
  "model": null,
  "version": "1.0.0",
  "exportedAt": "2025-02-22T14:00:00Z"
}
```

### `GET /api/v1/workflows/{id}/export/bpmn`

Export a workflow as BPMN 2.0 XML (Camunda Modeler compatible).

**Response:** File download (`.bpmn`, `Content-Disposition: attachment`)

### `POST /api/v1/workflows/import/bpmn`

Import a workflow previously exported via `GET /api/v1/workflows/{id}/export/bpmn` as a **new** workflow (roundtrip). Only accepts MySuricates' own exports (BPMN extension `mysuricates:blockConfig`); foreign BPMN, broken XML, or oversized input returns `400` — nothing is created in that case.

**Query parameter:** `projectId` (required) — target project

**Request:** `Content-Type: application/xml` or `text/xml`, body = BPMN XML

**Response:** `201` with `WorkflowResponse`

### `POST /api/v1/workflows/import`

Import a workflow from JSON (exported format + target project).

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Code Review",
  "description": "Automated code review",
  "icon": "🔍",
  "blocks": [
    { "type": "simple", "config": { "command": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
  ],
  "instructions": null,
  "model": null,
  "updateIfExists": false
}
```

| Field | Type | Required |
|---|---|---|
| `projectId` | String | No (fallback: "Collection" project) |
| `name` | String | Yes |
| `description` | String | No |
| `icon` | String | No |
| `blocks` | BlockDefinition[] | No |
| `instructions` | String | No |
| `model` | String | No |
| `updateIfExists` | Boolean | No (default: `false`) |

When `updateIfExists=true` and a workflow with the same name exists in the target project, it will be updated instead of creating a new one.

#### Block Config: `timeout_seconds`

All block types support an optional `timeout_seconds` field in the block config.
This overrides the Claude CLI timeout per block (default: global value from `automation.host-claude.timeout-seconds`, default 300s).

```json
{
  "type": "simple",
  "config": {
    "command": "Analyze the code in src/",
    "timeout_seconds": 600
  }
}
```

| Block Type | Effect of `timeout_seconds` |
|---|---|
| `simple` | Claude CLI timeout for the step |
| `retry` | Claude CLI timeout per retry attempt (shell: shell timeout) |
| `conditional` | Claude CLI timeout for condition evaluation and branch execution |
| `foreach` | Claude CLI timeout per iteration (shell: shell timeout) |
| `while` | Claude CLI timeout for condition and body (shell: shell timeout) |
| `skill-capability` | Claude CLI timeout for capability execution |
| `agentic` | Claude CLI timeout for REASON and ACT phase per iteration |
| `shell` | Shell process timeout (as before) |

Without `timeout_seconds`, the global default (`automation.host-claude.timeout-seconds`) applies.

**Response:** `201` with `WorkflowResponse`

### `POST /api/v1/workflows/{id}/execute`

Execute a workflow.

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
  "projectName": "My Project",
  "projectPath": "/workspace/project",
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
  "definitionVersionId": "version-uuid"
}
```

`definitionHash`/`definitionVersionId` are `null` when `automation.hardening.provenance` is off. Refused starts create no run: `409 WORKFLOW_TAMPERED` (sealed definition modified), `403 UNATTENDED_REQUIRES_SEAL` (unattended triggers only).

### `GET /api/v1/workflows/{id}/runs`

All runs of a workflow (descending by start time, paginated).

**Query Parameters:** `page` (default: 0), `size` (default: 50)

**Response:** `PageResponse<WorkflowRunListItemResponse>` — **without steps**, the same list rows as
`GET /api/v1/executions` (`stepCount`, `lastStepStatus`, `lastStepPreview`). Until 2026-09-23 this
route carried every run with all its steps. A run's steps come from `GET /api/v1/workflows/{id}/runs/{runId}`.

### `GET /api/v1/workflows/{id}/runs/{runId}`

Retrieve a single run.

**Response:** `WorkflowRunResponse`

### `POST /api/v1/workflows/{id}/optimize`

Generate an optimization suggestion for a workflow (AI-based).

**Response:** `Map<String, Object>` with optimization suggestion

### `GET /api/v1/workflows/{id}/similar`

Find similar workflows.

**Query Parameters:** `limit` (default: 5, max: 20)

**Response:** `List<Map<String, Object>>`

### `POST /api/v1/workflows/{id}/repair`

Repair a failed workflow on the test instance. Requires that the workflow has failed runs and the test instance is healthy.

**Response:** `202 Accepted` with `{ "status": "repair_started", "workflowId": "...", "workflowName": "..." }` — sealed workflows: `409 WORKFLOW_SEALED` before the repair starts

### `POST /api/v1/workflows/{id}/skills`

Activate skills for a workflow.

**Request:**

```json
{
  "skillIds": ["skill-uuid-1", "skill-uuid-2"]
}
```

**Response:** `WorkflowResponse`

### `DELETE /api/v1/workflows/{id}/skills/{skillId}`

Deactivate a skill from a workflow.

**Response:** `WorkflowResponse`

### `GET /api/v1/workflows/{id}/versions`

List all versions of a workflow (paginated).

**Query Parameters:** `page` (default: 0), `size` (default: 20)

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
      "changeMessage": "Prompt adjusted",
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

Retrieve a single version.

**Response:** `WorkflowVersionResponse`

### `POST /api/v1/workflows/{id}/versions/{versionId}/restore`

Restore a workflow to a previous version.

**Response:** `WorkflowResponse`

### `POST /api/v1/workflows/{id}/seal`

Seal a workflow (switch `automation.hardening.sealing`, otherwise `404 MODULE_DISABLED`). Records the current definition
hash; afterwards the workflow rejects definition changes and deletion (`409 WORKFLOW_SEALED`) and does not start when the
definition no longer matches the seal (`409 WORKFLOW_TAMPERED`). Idempotent; logs a `SECURITY` entry.

**Response:** `WorkflowResponse` with `sealed: true`, `sealedHash`, `sealedAt`

### `POST /api/v1/workflows/{id}/unseal`

Remove the seal (same switch). Logs a `SECURITY` entry with the previous hash.

**Response:** `WorkflowResponse` with `sealed: false`

---

## Workflow Templates

### `GET /api/v1/workflow-templates`

List all workflow templates, sorted by kind and `sortOrder`. The field `kind` separates templates (`TEMPLATE`) from blueprints (`BLUEPRINT`: empty scaffolds that show how block types connect, see [WORKFLOW-GUIDE → Blueprints](WORKFLOW-GUIDE.md#blueprints)).

**Query:** `locale` (optional, `de`/`en`): language of the steps (`blocks`, `instructions`) of bilingual templates. English when omitted. `name` and `description` stay maps. See [WORKFLOW-GUIDE → Bilingual templates](WORKFLOW-GUIDE.md#bilingual-templates).

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

`verifications` contains one object per project in which the template was verified, with `projectId`, `projectName`, `lastRunId`, `verifiedAt`.

### `GET /api/v1/workflow-templates/{id}`

Retrieve a single template. Query `locale` as for the list.

**Response:** `WorkflowTemplateResponse`

### `POST /api/v1/workflow-templates`

Create a new user template (`builtIn` is automatically set to `false`).

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

| Field | Type | Required |
|---|---|---|
| `name` | Map<String, String> | Yes |
| `description` | Map<String, String> | No |
| `icon` | String | No |
| `category` | String | No |
| `blocks` | BlockDefinition[] | Yes |
| `instructions` | String | No |
| `model` | String | No |

**Response:** `201` with `WorkflowTemplateResponse`

### `PUT /api/v1/workflow-templates/{id}`

Update a template. For built-in templates, only `sortOrder` and `category` can be changed.

**Request:**

```json
{
  "name": {"de": "Neuer Name", "en": "New Name"},
  "description": {"de": "Neue Beschreibung", "en": "New Description"},
  "sortOrder": 5
}
```

All fields are optional; only provided fields are updated.

**Response:** `WorkflowTemplateResponse`

### `DELETE /api/v1/workflow-templates/{id}`

Delete a user template. Built-in templates cannot be deleted (→ `400`).

**Response:** `204 No Content`

### `POST /api/v1/workflow-templates/from-workflow/{workflowId}`

Save an existing workflow as a new template (name, description, blocks, instructions, model are carried over).

**Response:** `201` with `WorkflowTemplateResponse`

---

## Executions

### `GET /api/v1/executions`

List all executions (paginated, filterable).

**Query Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `page` | int | Page (default: 0) |
| `size` | int | Entries per page (default: 50) |
| `status` | String | Filter by status: `PENDING`, `RUNNING`, `PAUSED`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`, `CANCELLED`, `INTERRUPTED` |
| `workflowId` | String | Filter by workflow |
| `projectId` | String | Filter by project |
| `search` | String | Full-text search |

**Response:** `PageResponse<WorkflowRunListItemResponse>` — **without steps.** Instead of the
steps, a list row carries `stepCount`, `lastStepStatus` and `lastStepPreview` (the last step's
output, truncated to 500 characters — cut in the database, the step rows are not loaded). Why: the list used to carry every `step_results.output`
(TEXT) — 3,061,816 bytes measured for 60 runs, 51,716 afterwards. If you need the steps, fetch the
run individually via `GET /api/v1/executions/{id}`; the detail route still returns everything.

### `GET /api/v1/executions/{id}`

Retrieve execution status (including steps).

**Response:** `WorkflowRunResponse`

### `POST /api/v1/executions/{id}/pause`

Pause a running execution.

**Response:** `200 OK`

### `POST /api/v1/executions/{id}/resume`

Resume a paused execution.

**Response:** `200 OK`

### `POST /api/v1/executions/{id}/cancel`

Cancel an execution.

**Response:** `200 OK`

### `POST /api/v1/executions/{id}/retry`

Retry a failed execution (creates a new run with the same input context).

**Response:** `WorkflowRunResponse`

### `POST /api/v1/executions/{id}/resume-from-step`

Resume an interrupted execution from the failed step.

**Response:** `WorkflowRunResponse`

### `PATCH /api/v1/executions/{id}/notes`

Update notes of an execution.

**Request:**

```json
{
  "notes": "Free-text note about the run"
}
```

**Response:** `WorkflowRunResponse`

### `POST /api/v1/executions/{id}/approve`

Answer an approval gate or human input.

**Request:**

```json
{
  "answer": "Yes"
}
```

| Field | Type | Required |
|---|---|---|
| `answer` | String | Yes |

**Note:** The field is called `answer`, not `approved`.

**Response:** `200 OK`

### `GET /api/v1/executions/{id}/approval`

Check whether an approval or human input is pending.

**Response (no approval pending):**

```json
{
  "pending": false
}
```

**Response (approval pending):**

```json
{
  "pending": true,
  "blockId": "block-id",
  "question": "Should we proceed?",
  "options": ["Yes", "No"],
  "stepIndex": 2,
  "type": "approval"
}
```

**Response (human input pending):**

```json
{
  "pending": true,
  "blockId": "block-id",
  "question": "Please enter a filename:",
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

Buffered live lines of a run for replay after a reload or late open (memory only, until 10 minutes after the run ends).
Query: `afterSeq` (default `-1` = everything). Without a buffer `200` with `available: false`.

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

`kind`: `line` (raw line), `text`, `tool`, `tool_result`, `tool_error`, `result`, `system`. `stepNames`: block names of steps with buffered lines (key = `stepIndex`).

### `DELETE /api/v1/executions/{id}`

Delete a run together with its steps and loose references to it. `409` if the run is still active or an audit report is attached to it. TODOs and audit data are never deleted along with it.

`403 RUN_DELETION_DISABLED` if the instance was started with `automation.executions.allow-delete: false` (env `AUTOMATION_EXECUTIONS_ALLOW_DELETE`, default `true`) — checked before any lookup, for every id. Startup configuration only, no REST or settings switch. `GET /api/v1/system/capabilities` reports it as `runDeletionAllowed`; the UI then hides the delete buttons. Run retention (`run-retention-days`) and the nightly INTERRUPTED sweep still apply — to lose nothing, also set `run-retention-days` to `0`. While deletion is off, `PUT /api/v1/system/execution-config` accepts `run-retention-days` only if it is equal or longer (`0` counts as the longest), otherwise `403 RUN_DELETION_DISABLED` — and none of the call is applied.

**Response:** `204 No Content`

### `POST /api/v1/executions/{id}/todos/approve`

Approve or reject auto-generated TODOs of a run.

**Request:**

```json
{
  "approved": true
}
```

**Response:** `200 OK`

### `POST /api/v1/executions/{id}/command`

Send an ad-hoc command in the context of a running execution.

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

List all skills.

**Response:** `SkillResponse[]`

```json
[
  {
    "id": "uuid",
    "name": "Code Analysis",
    "description": "Analyzes source code",
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
        "description": "Analyzes code",
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

Retrieve a single skill.

**Response:** `SkillResponse`

### `GET /api/v1/skills/{id}/capabilities`

List capabilities of a skill.

**Response:** `CapabilityResponse[]`

### `GET /api/v1/skills/{id}/versions`

Change history of a skill, newest first — also for deleted skills.

**Response:** `SkillVersionResponse[]` with `id`, `skillId`, `versionNumber`, `operation` (`CREATE`/`UPDATE`/`DELETE`), `changeSource` (`user`/`import`/`builtin-seed`), `contentHash`, `createdAt`; the snapshot itself is not returned.

### `GET /api/v1/skills/seal-pins`

Maps each skill id to the names of the sealed workflows that use the skill. Such skills cannot be changed or deleted.

**Response:** `{"<skillId>": ["workflow name", ...]}`

### `POST /api/v1/skills`

Create a new skill.

**Request:**

```json
{
  "name": "Custom Skill",
  "description": "Description",
  "icon": "⚡",
  "category": "CODE",
  "aiContext": "System prompt...",
  "configSchema": {}
}
```

| Field | Type | Required | Values |
|---|---|---|---|
| `name` | String | Yes | |
| `category` | Enum | Yes | `DOCUMENT`, `CODE`, `DATA`, `CONTENT`, `RESEARCH`, `BUSINESS`, `PRODUCTIVITY` |
| `description` | String | No | |
| `icon` | String | No | |
| `aiContext` | String | No | |
| `configSchema` | Object | No | |

**Response:** `201` with `SkillResponse`

### `PUT /api/v1/skills/{id}`

Edit a custom skill. `name`, `description`, `icon`, `category` and `aiContext` can be changed (required fields as for creation); `configSchema`, capabilities and the version label stay as they are. Each change writes a row to `GET /api/v1/skills/{id}/versions` (source `user`).

**Response:** `200` with `SkillResponse` · `409 STATE_CONFLICT` for a built-in skill (the next release update would overwrite the change) · `409 SKILL_PINNED_BY_SEAL` while a sealed workflow uses the skill

### `DELETE /api/v1/skills/{id}`

Delete a skill.

**Response:** `204 No Content` · `409 STATE_CONFLICT` for a built-in skill · `409 SKILL_PINNED_BY_SEAL` while a sealed workflow uses the skill

### `GET /api/v1/skills/export/{id}`

Export a skill as JSON (including capabilities).

**Response:** `ExportSkillResponse`

### `POST /api/v1/skills/import`

Import a skill from JSON.

**Request:** `ImportSkillRequest` (exported format); every capability needs a `name`, otherwise `400`

**Response:** `201` with `SkillResponse`

---

## Schedules

### `GET /api/v1/schedules`

List all schedules.

**Response:** `ScheduledWorkflowResponse[]`

```json
[
  {
    "id": "uuid",
    "workflowId": "uuid",
    "workflowName": "Nightly Build",
    "projectName": "My Project",
    "chainId": null,
    "chainName": null,
    "targetType": "WORKFLOW",
    "cronExpression": "0 0 2 * * ?",
    "scheduleType": "CRON",
    "scheduledAt": null,
    "delayExpression": null,
    "enabled": true,
    "description": "Nightly build",
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

Remaining scheduled executions for today.

**Response:** `UpcomingScheduleResponse`

### `GET /api/v1/schedules/remaining-this-week`

Remaining scheduled executions for this week.

**Response:** `UpcomingScheduleResponse`

### `GET /api/v1/schedules/{id}`

Retrieve a single schedule.

**Response:** `ScheduledWorkflowResponse`

### `GET /api/v1/schedules/{id}/runs`

Runs triggered by this schedule.

**Response:**

```json
{
  "workflowRuns": [...],
  "chainRuns": [...]
}
```

`workflowRuns` are `WorkflowRunListItemResponse` — **without steps**, the same list rows as
`GET /api/v1/executions` (`stepCount`, `lastStepStatus`, `lastStepPreview`). Until 2026-09-23 this
route carried every run with all its steps. A run's steps come from `GET /api/v1/executions/{id}`.

### `GET /api/v1/schedules/workflow/{workflowId}`

Schedules of a specific workflow.

**Response:** `ScheduledWorkflowResponse[]`

### `GET /api/v1/schedules/chain/{chainId}`

Schedules of a specific chain.

**Response:** `ScheduledWorkflowResponse[]`

### `GET /api/v1/schedules/calendar`

Calendar data: scheduled executions and past runs within a time range.

**Query Parameters:**

| Parameter | Type | Required |
|---|---|---|
| `from` | Instant | Yes |
| `to` | Instant | Yes |

**Response:** `CalendarDataResponse` with `upcoming` (`ScheduledWorkflowResponse[]`, schedules whose next
date lies in the range) and `pastRuns` (`WorkflowRunListItemResponse[]` — **without steps**, the same list
rows as `GET /api/v1/executions`; until 2026-09-23 with every step of every run in the range)

### `POST /api/v1/schedules/preview`

Preview the next 5 execution times for a cron expression.

**Request:**

```json
{
  "cronExpression": "0 0 2 * * ?"
}
```

**Response:** `{ "runs": ["2025-01-16T02:00:00Z", ...] }`

### `POST /api/v1/schedules`

Create a new schedule.

**Request:**

```json
{
  "workflowId": "uuid",
  "cronExpression": "0 0 2 * * ?",
  "description": "Nightly build",
  "inputContext": {}
}
```

| Field | Type | Required |
|---|---|---|
| `workflowId` | String | Yes (when `targetType` = `WORKFLOW`) |
| `chainId` | String | Yes (when `targetType` = `CHAIN`) |
| `targetType` | String | No (default: `WORKFLOW`, values: `WORKFLOW`, `CHAIN`) |
| `cronExpression` | String | Yes (Spring Cron, 6 fields) |
| `description` | String | No |
| `inputContext` | Object | No |
| `scheduleType` | String | No (default: `CRON`, values: `CRON`, `ONCE`, `DELAYED`) |
| `scheduledAt` | Instant | No (for `ONCE` schedules) |
| `delayExpression` | String | No (for `DELAYED` schedules, e.g. `"2h"`, `"30m"`) |
| `endDate` | Instant | No (optional end date for `CRON` schedules) |
| `maxRuns` | Integer | No (max starts, 0 = unlimited — see below) |
| `conflictBehavior` | String | No (default: `SKIP`, values: `SKIP`, `OVERLAP` — see below) |
| `catchUpOnResume` | boolean | No (default: `false` — see below) |

- **`conflictBehavior`:** with `SKIP`, an occurrence is dropped while the target still has a run in
  `RUNNING` or `PAUSED`: `nextRunAt` moves on to the next occurrence, a one-off schedule (`ONCE`,
  `DELAYED`) expires, and the activity log notes “Schedule skipped (previous run still in progress)”.
  `OVERLAP` starts a second run next to it anyway.
- **`maxRuns`:** counts every start of the schedule, a failed or refused one too; an occurrence dropped by
  `SKIP` does not count. Once the number is reached, the schedule is disabled. Re-enabling does not reset
  `runCount` — exactly one more start follows.
- **`catchUpOnResume`:** catches up occurrences of this schedule that fell into a standby or a restart,
  even when the global setting `scheduler-catch-up-on-resume` is off. That setting is on by default — then
  every schedule is caught up anyway, one after another through the catch-up queue.

**Response:** `201` with `ScheduledWorkflowResponse`

### `PUT /api/v1/schedules/{id}`

Update a schedule.

**Request:**

```json
{
  "cronExpression": "0 30 1 * * ?",
  "description": "New schedule",
  "inputContext": {},
  "endDate": null,
  "maxRuns": 10
}
```

All fields are optional; only provided fields are updated.

**Response:** `ScheduledWorkflowResponse`

### `POST /api/v1/schedules/{id}/enable`

Enable a schedule.

**Response:** `ScheduledWorkflowResponse`

### `POST /api/v1/schedules/{id}/disable`

Disable a schedule.

**Response:** `ScheduledWorkflowResponse`

### `DELETE /api/v1/schedules/{id}`

Delete a schedule.

**Response:** `204 No Content`

### `GET /api/v1/schedules/catch-up`

Query the sequential catch-up queue: the missed occurrence currently being caught up (after a cold start/resume) and the ones still waiting. Not persisted — due occurrences are re-queued after a restart.

**Response:** `CatchUpQueue.Snapshot` (`active`, `pending[]`)

### `GET /api/v1/schedules/maintenance`

Query maintenance mode status.

**Response:** `{ "enabled": false }`

### `POST /api/v1/schedules/maintenance`

Toggle maintenance mode on/off. In maintenance mode no schedule starts; `nextRunAt` stays where it is.
When it is switched off, every schedule whose time fell into the pause starts once, right away — all at
the same time, without the catch-up queue.

**Request:**

```json
{
  "enabled": true
}
```

**Response:** `{ "enabled": true }`

---

## Webhooks

The secret token is only ever returned in full (`tokenVisible: true`) right after creation
(`POST /api/v1/webhooks`) or regeneration (`POST /api/v1/webhooks/{id}/regenerate-token`). Every
other management response (list, get, enable, disable) masks it: only the first 8 characters plus
`…` (U+2026), and `webhookUrl` uses that same masked token. Save the full token immediately after
creating/regenerating — it cannot be retrieved again afterwards.

**Upgrade note:** existing webhook tokens are no longer readable in full after this update. To
recover, call `POST /api/v1/webhooks/{id}/regenerate-token` and reconfigure the external caller
(e.g. the GitHub webhook settings) with the new token.

### `GET /api/v1/webhooks`

List all webhooks.

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

Retrieve a single webhook.

**Response:** `WebhookTriggerResponse` (token masked, `tokenVisible: false`)

### `GET /api/v1/webhooks/workflow/{workflowId}`

Webhooks of a specific workflow.

**Response:** `WebhookTriggerResponse[]` (token masked, `tokenVisible: false`)

### `POST /api/v1/webhooks`

Create a new webhook (token is automatically generated).

**Request:**

```json
{
  "workflowId": "uuid",
  "description": "GitHub Push Hook",
  "defaultContext": {}
}
```

| Field | Type | Required |
|---|---|---|
| `workflowId` | String | Yes |
| `description` | String | No |
| `defaultContext` | Object | No |

**Response:** `201` with `WebhookTriggerResponse` — token shown in full, `tokenVisible: true`

### `POST /api/v1/webhooks/{id}/enable`

Enable a webhook.

**Response:** `WebhookTriggerResponse` (token masked, `tokenVisible: false`)

### `POST /api/v1/webhooks/{id}/disable`

Disable a webhook.

**Response:** `WebhookTriggerResponse` (token masked, `tokenVisible: false`)

### `POST /api/v1/webhooks/{id}/regenerate-token`

Generates a new secret token for the webhook. The previous token stops working immediately;
`lastTriggeredAt` and `triggerCount` are left unchanged. Logs a `SECURITY` entry to the
ActivityLog.

**Response:** `WebhookTriggerResponse` — new token shown in full, `tokenVisible: true`

### `POST /api/v1/webhooks/{id}/secret`

Set or rotate the signing secret (switch `automation.hardening.webhook-signatures`, otherwise `404 MODULE_DISABLED`).
The secret appears only in this response and is stored encrypted; from now on trigger calls need a signature. Logs a
`SECURITY` entry.

**Response:** `{ "secret": "<64 hex>", "header": "X-Signature" }`

### `DELETE /api/v1/webhooks/{id}/secret`

Remove the signing secret (same switch).

**Response:** `204 No Content`

### `DELETE /api/v1/webhooks/{id}`

Delete a webhook.

**Response:** `204 No Content`

### `POST /api/v1/webhook/trigger/{token}` (Public)

Trigger a webhook. **No API key required.**

**Request (optional):**

```json
{
  "branch": "main",
  "commit": "abc123"
}
```

The payload is merged with the webhook's `defaultContext` and passed as context to the workflow execution.
It must be a JSON object (otherwise `400`); keys prefixed with `__` are reserved.

**Signature:** If the webhook has a signing secret (`signatureRequired: true`), the call needs the header
`X-Signature: sha256=<hex>` (or `X-Hub-Signature-256`) carrying the HMAC-SHA256 of the raw request bytes — otherwise
`401 WEBHOOK_SIGNATURE_INVALID`:

```bash
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | cut -d' ' -f2)
curl -X POST "$URL" -H 'Content-Type: application/json' -H "X-Signature: sha256=$SIG" -d "$BODY"
```

With `automation.hardening.require-sealed-for-unattended` a webhook only starts sealed workflows (otherwise
`403 UNATTENDED_REQUIRES_SEAL`).

**Response:** `WorkflowRunResponse`

---

## Completion Triggers

Completion triggers automatically start a target workflow when a source workflow completes.

### `GET /api/v1/completion-triggers`

List all completion triggers.

**Response:** `CompletionTriggerResponse[]`

### `GET /api/v1/completion-triggers/{id}`

Retrieve a single trigger.

**Response:** `CompletionTriggerResponse`

### `GET /api/v1/completion-triggers/workflow/{workflowId}/as-source`

Retrieve triggers where the specified workflow acts as a source.

**Response:** `CompletionTriggerResponse[]`

### `GET /api/v1/completion-triggers/workflow/{workflowId}/as-target`

Retrieve triggers where the specified workflow acts as a target.

**Response:** `CompletionTriggerResponse[]`

### `POST /api/v1/completion-triggers`

Create a new completion trigger.

**Request:**

```json
{
  "sourceWorkflowId": "uuid",
  "targetWorkflowId": "uuid",
  "triggerOnSuccess": true,
  "triggerOnFailure": false,
  "triggerAlways": false,
  "description": "After successful build: start deployment",
  "contextVariables": {}
}
```

| Field | Type | Required |
|---|---|---|
| `sourceWorkflowId` | String | Yes |
| `targetWorkflowId` | String | Yes |
| `triggerOnSuccess` | boolean | No |
| `triggerOnFailure` | boolean | No |
| `triggerAlways` | boolean | No |
| `description` | String | No |
| `contextVariables` | Object | No |

**Response:** `201` with `CompletionTriggerResponse`

**CompletionTriggerResponse Fields:** `id`, `sourceWorkflowId`, `sourceWorkflowName`, `targetWorkflowId`, `targetWorkflowName`, `enabled`, `triggerOnSuccess`, `triggerOnFailure`, `triggerAlways`, `description`, `contextVariables`, `lastTriggeredAt`, `triggerCount`, `createdAt`

### `POST /api/v1/completion-triggers/{id}/enable`

Enable a trigger.

**Response:** `CompletionTriggerResponse`

### `POST /api/v1/completion-triggers/{id}/disable`

Disable a trigger.

**Response:** `CompletionTriggerResponse`

### `DELETE /api/v1/completion-triggers/{id}`

Delete a trigger.

**Response:** `204 No Content`

---

## Integrations (OAuth Providers)

Plugin system for external services (Google Calendar, Gmail, Jira, Confluence, Trello). Each provider is automatically discovered and offers OAuth connection + actions.

### `GET /api/v1/integrations`

List all providers with connection status, actions, and account info.

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
      { "scope": "https://www.googleapis.com/auth/calendar.events", "label": "Manage events", "granted": true }
    ],
    "supportsTaskExport": false,
    "actions": [
      { "name": "list_events", "description": "...", "inputSchema": {...}, "available": true }
    ]
  }
]
```

### `GET /api/v1/integrations/{providerId}`

Retrieve a single provider with details.

### `GET /api/v1/integrations/{providerId}/connect`

Generate an OAuth authorization URL.

**Query Parameters:** `scopes` (optional, list of additional OAuth scopes)

**Response:** `200 OK`
```json
{ "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?..." }
```

### `GET /api/v1/integrations/{providerId}/callback`

OAuth callback (public, no API key). Exchanges authorization code for tokens and closes the popup window via `postMessage`.

### `POST /api/v1/integrations/{providerId}/credentials`

Save client credentials (Client ID + Secret). Stored AES-256-GCM encrypted in the database.

**Body:**
```json
{ "clientId": "...", "clientSecret": "..." }
```

**Response:** `IntegrationProviderResponse`

### `DELETE /api/v1/integrations/{providerId}/connection`

Disconnect OAuth connection. Deletes stored tokens.

**Response:** `204 No Content`

### `DELETE /api/v1/integrations/{providerId}/credentials`

Delete client credentials.

**Response:** `204 No Content`

### `GET /api/v1/integrations/{providerId}/actions`

List all available actions of the provider. `available` is `false` when required OAuth scopes are missing.

### `POST /api/v1/integrations/{providerId}/actions/{action}`

Execute an action. The body contains the action-specific parameters.

**Body (example Google Calendar `create_event`):**
```json
{
  "summary": "Meeting",
  "startDateTime": "2026-04-15T10:00:00+02:00",
  "endDateTime": "2026-04-15T11:00:00+02:00"
}
```

### `GET /api/v1/integrations/{providerId}/task-targets`

List available task export targets (e.g. Jira boards, Trello lists).

**Response:** `List<TaskTarget>`

---

## Onboarding Profiles

AI context templates for project onboarding. 5 built-in profiles (Developer, Research, Content, Ops, Base) + custom profiles.

### `GET /api/v1/projects/{id}/onboarding/status`

Check onboarding status of a project (active profiles, generated file).

### `POST /api/v1/projects/{id}/onboarding/enable`

Enable onboarding for a project. Generates the onboarding file from the selected profiles.

**Body:**
```json
{ "format": "md", "profileIds": ["profile-uuid-1", "profile-uuid-2"] }
```

**Response:** `OnboardingStatusResponse`

### `POST /api/v1/projects/{id}/onboarding/disable`

Disable onboarding for a project.

**Response:** `OnboardingStatusResponse`

### `POST /api/v1/projects/{id}/onboarding/refresh`

Regenerate the onboarding file.

**Response:** `OnboardingStatusResponse`

### `GET /api/v1/projects/{id}/onboarding/preview`

Preview the generated onboarding file.

**Response:** `{ "content": "..." }`

### `GET /api/v1/onboarding-profiles`

List all onboarding profiles.

**Response:** `OnboardingProfileResponse[]`

### `GET /api/v1/onboarding-profiles/{id}`

Retrieve a single profile.

**Response:** `OnboardingProfileResponse`

### `POST /api/v1/onboarding-profiles`

Create a new profile.

**Request:**

```json
{
  "key": "my-profile",
  "name": {"de": "Mein Profil", "en": "My Profile"},
  "description": {"de": "...", "en": "..."},
  "content": "Profile content...",
  "type": "PROFILE"
}
```

| Field | Type | Required | Values |
|---|---|---|---|
| `key` | String | Yes | Pattern: `^[a-z0-9-]+$` |
| `name` | Map<String, String> | Yes | |
| `description` | Map<String, String> | No | |
| `content` | String | Yes | |
| `type` | Enum | Yes | `BASE`, `PROFILE` |

**Response:** `201` with `OnboardingProfileResponse`

**OnboardingProfileResponse Fields:** `id`, `key`, `name`, `description`, `content`, `type`, `builtIn`, `sortOrder`, `version`, `historyCount`, `createdAt`, `updatedAt`

### `PUT /api/v1/onboarding-profiles/{id}`

Update profile content (automatically creates a history entry).

**Response:** `OnboardingProfileResponse`

### `DELETE /api/v1/onboarding-profiles/{id}`

Delete a profile.

**Response:** `204 No Content`

### `POST /api/v1/onboarding-profiles/{id}/reset`

Set a built-in profile to the current shipped text; the previous text goes into the history, `customized` becomes `false`.

**Response:** `OnboardingProfileResponse` (includes `customized` — changed by hand — and `updateAvailable` — a newer shipped text exists than the one the profile is based on)

### `GET /api/v1/onboarding-profiles/{id}/shipped`

The current shipped text of a built-in profile, to compare before taking it over.

**Response:** `{ "version": 4, "content": { "de": "...", "en": "..." } }` · 404 for custom profiles

### `GET /api/v1/onboarding-profiles/{id}/history`

Retrieve the change history of a profile.

**Response:** `OnboardingProfileHistoryResponse[]`

---

## Todo Triggers (Self-Improvement Loop)

Todo triggers automatically start fix workflows when TODOs are created. Closes the self-improvement loop: Audit → TODO → Fix → Verify → Close.

### `GET /api/v1/todo-triggers`

List all todo triggers.

**Response:** `TodoTriggerResponse[]`

### `GET /api/v1/todo-triggers/{id}`

Retrieve a single todo trigger.

**Response:** `TodoTriggerResponse`

### `GET /api/v1/todo-triggers/workflow/{workflowId}`

Retrieve todo triggers that have the specified workflow as a target.

**Response:** `TodoTriggerResponse[]`

### `POST /api/v1/todo-triggers`

Create a new todo trigger.

**Request:**

```json
{
  "tagPattern": "backend,testing",
  "targetWorkflowId": "<workflow-id>",
  "projectId": null,
  "sourceFilter": "WORKFLOW",
  "minPriority": "HIGH",
  "description": "Auto-fix: missing backend tests",
  "maxAttempts": 3,
  "autoClose": true,
  "contextTemplate": { "extra_key": "extra_value" }
}
```

| Field | Required | Description |
|---|---|---|
| `tagPattern` | Yes | Comma-separated tags (AND logic). All tags must be present in the TODO |
| `targetWorkflowId` | Yes | ID of the fix workflow to be started |
| `projectId` | No | Optional: only match TODOs in this project |
| `sourceFilter` | No | Optional: `"USER"`, `"AI"` or `"WORKFLOW"` |
| `minPriority` | No | Optional: Minimum priority (`"LOW"`, `"MEDIUM"`, `"HIGH"`, `"CRITICAL"`) |
| `description` | No | Human-readable description |
| `maxAttempts` | No | Max fix attempts per TODO (default: 3) |
| `autoClose` | No | Automatically close TODO on GREEN gate (default: true) |
| `processBacklog` | No | Process existing matching TODOs on creation (default: true) |
| `contextTemplate` | No | Static context variables, merged with TODO data |

**Response:** `201` with `TodoTriggerResponse`

**TodoTriggerResponse Fields:** `id`, `tagPattern`, `projectId`, `sourceFilter`, `minPriority`, `targetWorkflowId`, `targetWorkflowName`, `contextTemplate`, `enabled`, `maxAttempts`, `autoClose`, `processBacklog`, `description`, `lastTriggeredAt`, `triggerCount`, `createdAt`

**Context variables** automatically provided to the fix workflow:

| Variable | Description |
|---|---|
| `todo_id` | TODO UUID |
| `todo_title` | TODO title |
| `todo_description` | TODO description (Markdown) |
| `todo_tags` | TODO tags as JSON array |
| `todo_priority` | `LOW`, `MEDIUM`, `HIGH` or `CRITICAL` |
| `todo_project_id` | Project UUID of the TODO |
| `todo_source` | `USER`, `AI` or `WORKFLOW` |
| `todo_reference_type` | Reference type (e.g. `workflow_run`) |
| `todo_reference_id` | Reference ID (e.g. workflow ID) |
| `todo_trigger_id` | ID of the triggering TodoTrigger |

### `POST /api/v1/todo-triggers/{id}/enable`

Enable a trigger.

**Response:** `TodoTriggerResponse`

### `POST /api/v1/todo-triggers/{id}/disable`

Disable a trigger.

**Response:** `TodoTriggerResponse`

### `DELETE /api/v1/todo-triggers/{id}`

Delete a trigger.

**Response:** `204 No Content`

### Loop Prevention

Fix workflows started by a TodoTrigger set `todoTriggerRun: true` in the run metadata. This prevents:
- **AutoTodoService** skips TODO creation for these runs (no Audit→TODO→Fix→TODO loop)
- **Max Attempts**: After `maxAttempts` failed attempts, the trigger is no longer fired for this TODO
- **Running Check**: If `lastFixRunId` is still RUNNING, no second fix run is started

---

## Audit Center

Read-only view of audit workflow results (gate status per audit run) and the findings derived from them, plus promotion of individual findings to TODOs. Module `audit` (`automation.modules.audit`) — when disabled, every endpoint below responds with `404 MODULE_DISABLED`.

### `GET /api/v1/audits`

List all audits (grouped by project + `auditKey`) with the latest valid report for each. Optional filters: `projectId`, `category`, `gateStatus`.

**Response:** `AuditSummary[]`

### `GET /api/v1/audits/{auditKey}`

Details of an audit (summary + latest report). Required parameter: `projectId`.

**Response:** `AuditDetail`

### `GET /api/v1/audits/{auditKey}/reports`

All reports of an audit, paginated (`page`, `size`). Required parameter: `projectId`.

**Response:** `PageResponse<AuditReportResponse>`

### `GET /api/v1/audit-reports/{id}`

Retrieve a single audit report.

**Response:** `AuditReportResponse`

### `GET /api/v1/audit-findings`

List findings across all audits, paginated (`page`, `size`). Optional filters: `projectId`, `auditKey`, `status`, `severity`.

**Response:** `PageResponse<AuditFindingResponse>`

### `GET /api/v1/audit-findings/{id}`

Retrieve a single finding.

**Response:** `AuditFindingResponse`

### `PATCH /api/v1/audit-findings/{id}`

Change a finding's status or disposition note.

**Request:**

| Field | Type | Required |
|---|---|---|
| `status` | Enum (`AuditFindingStatus`) | No |
| `dispositionNote` | String | No |

**Response:** `AuditFindingResponse`

### `POST /api/v1/audit-findings/{id}/todos`

Create a MySuricates-managed TODO from the finding ("managed TODO").

**Response:** `AuditFindingResponse`

### `POST /api/v1/audit-findings/{id}/todo-links`

Manually link an existing TODO to the finding.

**Request:** `{ "todoId": "uuid" }`

**Response:** `AuditFindingResponse`

### `DELETE /api/v1/audit-findings/{id}/todo-links/{todoId}`

Remove the link between a finding and a TODO.

**Response:** `204 No Content`

---

## Notifications

Notifications upon workflow completion (webhook or desktop).

### `GET /api/v1/notifications`

List all notification configurations.

**Response:** `NotificationConfigResponse[]`

### `GET /api/v1/notifications/{id}`

Retrieve a single notification configuration.

**Response:** `NotificationConfigResponse`

### `POST /api/v1/notifications`

Create a new notification configuration.

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

| Field | Type | Required | Values |
|---|---|---|---|
| `name` | String | Yes | |
| `channelType` | Enum | Yes | `WEBHOOK`, `DESKTOP` |
| `webhookUrl` | String | No (required for `WEBHOOK`) | Slack/Discord/Teams webhook URL |
| `workflowId` | String | No | `null` = global (all workflows) |
| `triggerOnSuccess` | boolean | No | |
| `triggerOnFailure` | boolean | No | |
| `triggerAlways` | boolean | No | |
| `messageTemplate` | String | No | Supports `{{workflow_name}}`, `{{status}}`, `{{duration}}`, `{{error_message}}` |

**Response:** `201` with `NotificationConfigResponse`

**NotificationConfigResponse Fields:** `id`, `name`, `enabled`, `channelType`, `webhookUrl`, `workflowId`, `triggerOnSuccess`, `triggerOnFailure`, `triggerAlways`, `messageTemplate`, `createdAt`

### `POST /api/v1/notifications/{id}/enable`

Enable a notification.

**Response:** `NotificationConfigResponse`

### `POST /api/v1/notifications/{id}/disable`

Disable a notification.

**Response:** `NotificationConfigResponse`

### `DELETE /api/v1/notifications/{id}`

Delete a notification configuration.

**Response:** `204 No Content`

---

## System

### `GET /api/v1/system/health` (Public)

Health check.

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

`status` can be `UP`, `DEGRADED` (Docker unavailable), or `DOWN` (database unavailable).
`docker` can be `CONNECTED` or `UNAVAILABLE`.

### `GET /api/v1/system/info` (Public)

System information.

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

Query authentication status.

**Response:** `{ "securityEnabled": true }`

### `GET /api/v1/system/ai-providers`

Query available AI providers and default provider.

**Response:**

```json
{
  "providers": ["CLAUDE", "PI", "CODEX"],
  "default": "CLAUDE"
}
```

### `GET /api/v1/system/capabilities`

Retrieve system capabilities (API version, app version, available routes and block types) and the module status decided at startup.

**Response:** `SystemCapabilitiesResponse` — module fields excerpt:

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

Endpoints of disabled modules answer `404` with `{"code": "MODULE_DISABLED"}`. `configured` is the value from the startup configuration, `overridden` marks a switch made in the settings.

### `PUT /api/v1/system/modules/{name}`

Switch a feature module at runtime — only with `automation.modules.ui-editable: true`; applies immediately and survives restarts. Logs a `SECURITY` entry.

**Request:** `{ "enabled": true }`

**Response:** `{ "preset": "core", "uiEditable": true, "modules": [ … ] }` (module entries as above)

**Errors:** `403 MODULES_NOT_EDITABLE` (switch off), `400 INVALID_ARGUMENT` (hardening switch — startup configuration only), `409 STATE_CONFLICT` (dependency, e.g. `wishes` needs `chains`), `404` (unknown module)

### `DELETE /api/v1/system/modules/{name}/override`

Remove the settings switch: the module follows the startup configuration again. Same response and errors as `PUT`.

### `GET /api/v1/system/ai-providers/PI/catalog`

Retrieve the model catalog of the Pi provider.

**Response:** `PiCatalog`

### `GET /api/v1/system/ai-providers/{id}/models`

Models this provider offers on this host (host discovery via CLI or local cache, TTL-cached). Never fails because of a missing CLI — returns `available: false` with `reason` instead.

**Response:** `ModelCatalog` (`providerId`, `available`, `source`, `fetchedAt`, `reason`, `models[]`)

### `POST /api/v1/system/ai-providers/{id}/models/refresh`

Bypass the cache and probe the host now.

**Response:** `ModelCatalog`

### `GET /api/v1/system/ai-providers/status`

Whether the AI CLIs on the backend's machine are ready: per provider the program on the `PATH`, a login (login file or environment variable — the same rule as the installer, from `AiProvider#setup()`) and the commands to install and log in. Starts no CLI; the answer is cached (`automation.ai-provider-status.ttl-seconds`, default 60). It holds only names and commands, never a token or file content. "Ready" means program and login were found — not that the login is still valid.

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
    }
  ]
}
```

`state`: `READY` (program and login found), `LOGIN_MISSING` (program found, no login), `NOT_INSTALLED` (program missing). `loginSource`: the variable name or the login file as `~/…` — or as `$VARIABLE/…` when a container instance moves the directory (`CLAUDE_CONFIG_DIR`, `CODEX_HOME`, `PI_CODING_AGENT_DIR`). `container: true` means the backend runs in a container: the programs and logins inside it are what counts.

### `POST /api/v1/system/ai-providers/status/refresh`

Bypass the cache and check now, e.g. right after logging in.

**Response:** as `GET /api/v1/system/ai-providers/status`

### `POST /api/v1/system/client-errors`

Report frontend errors (for logging).

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

Enable Tailscale remote access.

**Response:** `RemoteAccessResponse`

```json
{
  "enabled": true,
  "ip": "100.64.0.1",
  "hostname": "automation.tailnet.ts.net"
}
```

### `POST /api/v1/system/remote-access/disable`

Disable Tailscale remote access.

**Response:** `200 OK`

### `GET /api/v1/system/remote-access/status`

Query Tailscale status.

**Response:** `RemoteAccessResponse`

### `POST /api/v1/system/vacuum`

Execute SQLite database VACUUM.

**Response:** `Map<String, Object>`

### `GET /api/v1/system/backup`

Download database backup.

**Response:** File download (SQLite backup)

### `POST /api/v1/system/backup`

Create a named backup.

**Query Parameters:** `reason` (optional, String)

**Response:** `BackupMetadata` with `filename`, `sizeBytes`, `createdAt`

### `GET /api/v1/system/backups`

List all available backups.

**Response:** `BackupInfo[]`

### `GET /api/v1/system/backups/{filename}`

Download a specific backup.

**Response:** File download

### `GET /api/v1/system/settings/{key}`

Retrieve an application setting.

**Allowed Keys:** `auto-todo-default`, `auto-repair-default`, `auto-repair-mode`, `auto-repair-max-retries`, `auto-repair-timeout-seconds`, `default-ai-provider`, `assistant-thinking-phrases`, `assistant-model`, `suggestions-model`, `suggestions-enabled`, `backup-retain-count`

**Response:** `{ "key": "...", "value": "..." }`

### `PUT /api/v1/system/settings/{key}`

Set an application setting.

**Request:**

```json
{
  "value": "true"
}
```

**Response:** `200 OK`

### `GET /api/v1/system/tray-config`

Retrieve runtime settings for the host tray icon.

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

Update tray settings. Partial updates are accepted. External browser targets must use HTTPS; HTTP is allowed only for loopback addresses. The browser target is separate from internal health-check URLs.

**Request/response:** the same fields as `GET`; the response contains the complete validated state.

### `GET /api/v1/system/execution-config`

Retrieve current execution configuration (timeouts, token limits, etc.). Additionally contains a read-only field `activeRuns` with the number of active runs.

**Allowed Keys:**

| Key | Range |
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
| `scheduler-catch-up-on-resume` | 0–1 |
| `scheduler-resume-grace-minutes` | 1–60 |
| `summarization-timeout-seconds` | 30–600 |
| `factory-timeout-seconds` | 60–3600 |
| `wish-total-timeout-minutes` | 5–1440 |
| `wish-workflow-wait-minutes` | 1–120 |
| `wish-default-shell-timeout-seconds` | 30–3600 |
| `wish-default-agentic-total-timeout-seconds` | 60–86400 |

**Response:** `Map<String, Object>` with current settings

### `PUT /api/v1/system/execution-config`

Update execution configuration.

**Request:** `Map<String, String>` with the settings to change (keys as above, camelCase also accepted)

**Response:** `Map<String, Object>` with updated settings

### `POST /api/v1/system/repair-failed`

Automatically repair all failed workflows. Requires a healthy test instance.

**Query Parameters:** `force` (boolean, default: `false`)

**Response:** `202 Accepted` with `{ "status": "repair_started", "workflowsQueued": 3, "force": false }`

### `POST /api/v1/system/open-path`

Open a file or directory in the native file manager (Linux: `xdg-open`).

**Request:**

```json
{
  "path": "/home/user/project"
}
```

**Response:** `{ "status": "ok" }`

### `GET /api/v1/system/integration-snippet`

Render a Markdown integration snippet for a project (for AGENTS.md etc.): the base onboarding profile with this instance's address and key command, plus the project's workflows.

**Query Parameters:** `projectPath` (optional), `locale` (`de`/`en`, optional; default `en`)

**Response:** `String` (Content-Type: `text/markdown`)

### `GET /api/v1/system/browse`

Browse directory contents (subdirectories only, no hidden folders).

**Query Parameters:** `path` (String, optional, default: home directory)

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

Dashboard data with statistics.

**Query Parameters:** `days` (default: 30), `projectId` (optional)

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

Platform-wide statistics.

**Response:** `PlatformStatsResponse` with `activeProjects`, `archivedProjects`, `totalWorkflows`, `workflowPoolSize`, `totalSkills`, `builtinSkills`, `customSkills`, `topSkillsByUsage`, `totalTemplates`, `builtinTemplates`, `customTemplates`, `activeSchedules`, `totalSchedules`, `activeWebhooks`, `totalWebhooks`, `chainStats`, `activeTodoTriggers`, `activeCompletionTriggers`, `activeMemoryEntries`, `todoStats`, `wishStats`, `assistantStats`

### `GET /api/v1/learning/workflows/{workflowId}/stats`

Statistics of a single workflow.

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

Step-level analysis of a workflow.

**Response:** `Array<Object>` with step statistics

### `GET /api/v1/learning/workflows/{workflowId}/suggestions`

Optimization suggestions based on run data.

**Response:** `Array<Object>` with improvement suggestions

### `GET /api/v1/learning/workflows/{workflowId}/similar`

Find similar workflows.

**Response:** `Array<Object>` with similar workflows

### `GET /api/v1/learning/workflows/{workflowId}/agentic-history`

Agentic strategy history of a workflow (how the AI strategy evolved across runs).

**Response:** `Array<Object>`

### `GET /api/v1/learning/workflows/{workflowId}/agentic-analytics`

Agentic block analysis: iterations, token usage, success rate.

**Response:** `Map<String, Object>`

### `GET /api/v1/learning/workflows/{workflowId}/tool-patterns`

Tool usage patterns of a workflow (which tools are used how often).

**Response:** `Map<String, Object>`

### `GET /api/v1/learning/workflows/{workflowId}/execution-insights`

Execution insights: trends, anomalies, performance metrics.

**Response:** `Map<String, Object>`

### `GET /api/v1/learning/runs/{runId}/insights`

Detailed insights for a single run.

**Response:** `Map<String, Object>`

### `POST /api/v1/learning/runs/{runId}/rate?rating={1-5}`

Rate a run (1-5 stars).

| Parameter | Type | Required | Values |
|---|---|---|---|
| `rating` | int (Query) | Yes | 1-5 |

**Response:** `200 OK`

---

## Dashboard Layouts

History of the dashboard layout (all views), shared across devices. The frontend saves a snapshot when editing ends. Unnamed versions are trimmed to the newest `dashboard-layout-retention-count` (default 100, see `/system/execution-config`); named ones are kept.

### `GET /api/v1/dashboard/layouts`

Versions, newest first, **without** the layout content.

**Query parameters:** `page` (default: 0), `size` (default: 20, at most 100)

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

One version with content: the fields above plus `layout` (`{ "activeViewId": …, "views": [ … ] }`).

### `POST /api/v1/dashboard/layouts`

Saves a snapshot. Body at most 256 KiB; unknown fields → 400.

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

- **201** new version; **200** content identical to the newest version (no new row).
- The dedupe hash (`contentHash`) leaves out `activeViewId`: each device chooses the shown view itself, so a snapshot that differs only in it creates no new version (**200**). `activeViewId` is still stored and validated (must reference a view).
- **409 `STATE_CONFLICT`**: the newest version is not `baseVersionId` — another device saved. `"force": true` saves anyway.
- **413**: body larger than `automation.dashboard.layout.max-request-bytes` (default 262144).
- Limits as in the frontend (`LAYOUT_LIMITS`): ≤ 20 views, ≤ 100 widgets per view, ids `^[a-z0-9][a-z0-9-]*$` with ≤ 80 characters, view names ≤ 80 Unicode code points, `config` only string (≤ 500) / number / boolean with ≤ 20 keys `^[a-zA-Z][a-zA-Z0-9]{0,39}$`, `filter.days` 1–365.

### `PATCH /api/v1/dashboard/layouts/{id}`

Body `{ "name": "before the rework" }` names a version (1–80 characters), `{ "name": null }` removes the name. Named versions are never deleted automatically.

---

## Dashboard workflow widgets

Data of the dashboard's workflow widgets (status widget and result widget). Core endpoint, no module gate.

### `GET /api/v1/dashboard/workflow-results`

For each queried workflow: the **latest run** and the validated result — **without** step outputs.

**Query parameters:** `workflowIds` (multiple or comma-separated, at most 50; each id must match `^[a-z0-9][a-z0-9-]{0,79}$`, else **400**). Without parameters: empty list.

**Response:** one entry per queried id, in request order.

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

- `available: false` (soft-deleted or unknown workflow) ⇒ `workflowName`, `run` and `result` are `null`, `resultState` is `MISSING`.
- `resultState`: `VALID` (contract fulfilled), `INVALID` (marker present but rejected), `MISSING` (no marker). Only present when `VALID`.

### The `dashboard_widget:` marker in a workflow

> **Precedence:** Once a `dashboard-report` block of the run has written a validated result, the marker is not evaluated — not even a broken marker sets `dashboard_widget_error`. The block is the deliberately configured way, the marker is opportunistic.

A workflow reports its dashboard result like `gate_status:` as a marker line in the block output; the JSON may span multiple lines:

```
dashboard_widget: {"title": "Nightly QA", "status": "YELLOW",
  "kpis": [{"label": "Hunts", "value": "1361", "trend": "UP"}],
  "items": ["3 Hunts ohne Route"], "summary": "Zwei Befunde."}
```

Contract (validated at run end and written to run metadata, re-validated on read):

| Field | Required | Rule |
|---|---|---|
| `status` | yes | `GREEN`, `YELLOW` or `RED` |
| `title` | no | text, ≤ 80 characters |
| `kpis` | no | ≤ 6 objects with `label` (≤ 40), `value` (≤ 40, **string**) and optional `trend` (`UP`/`DOWN`/`FLAT`) |
| `items` | no | ≤ 10 texts, each ≤ 200 characters |
| `summary` | no | text, ≤ 500 characters |

- **Unknown fields ⇒ the whole contract is rejected** (including in KPI objects).
- Text is single-line: empty, too long, or with control, zero-width, bidi, separator, private-use characters or lone UTF-16 surrogates ⇒ rejected.
- KPI values are strings; a number in a string position is a contract violation.
- The JSON is read to a maximum of 8192 characters; if the closing bracket does not come by then, the marker is invalid.
- If multiple blocks report a marker, the **last** one wins; outputs from compression blocks (`derivedOutput`) are skipped.
- **Inside a *single* block output the opposite applies: two markers reject that output entirely** (`resultState: INVALID`, no widget result). Block outputs frequently quote foreign text, and a quoted marker must never out-vote the report's own result — better no result than a wrong one. Emitting the marker twice in one block therefore shows "no valid result".
- So write your own marker **at the start of a line**, with the JSON directly after it (a fenced code block on the next line is fine too). A marker that sits mid-line *and* is separated from its JSON by a blank line, a non-breaking space (U+00A0) or a NEL (U+0085) is not recognised — a marker quoted from foreign text can then be the only recognised one.
- Content appears in the dashboard **as text only** — no Markdown, no HTML, no links, no images. The contract deliberately contains no id and no URL field.

### WebSocket `/topic/dashboard/runs`

After a run ends and is saved: `{"type":"run_completed","runId":"run-1","workflowId":"wf-a1","status":"COMPLETED"}`. Only ids and status — the dashboard then reloads its workflow widgets.

### `POST /api/v1/dashboard/report-assist`

AI suggestion for the fields of a `dashboard-report` block, derived from the workflow's **last run**. A single tool-less AI call (provider `CLAUDE`, no model flag); the response is validated in the backend against the same contract as a marker. Raw response and run output never leave the server.

Module `dashboard-report`; the setting `dashboard-report-assist-enabled` (see `/system/execution-config`) must also be `1`.

**Body:** `{ "workflowId": "wf-a1" }`

**Response:**

```json
{ "runId": "run-1", "runCompletedAt": "2026-09-18T08:02:00Z", "provider": "CLAUDE",
  "proposal": { "title": "Nightly QA", "status": "YELLOW",
                "kpis": [{ "label": "Hunts", "value": "1361", "trend": "UP" }],
                "items": ["3 Hunts without route"], "summary": "One finding." } }
```

- **400**: `workflowId` missing or empty.
- **404**: Module off (`MODULE_DISABLED`) or workflow unknown/soft-deleted.
- **409**: setting off, no Claude CLI on this host (checked via the `CLAUDE` provider's model catalog), no run or no output in the last run, AI does not answer or answer does not match contract. The message is fixed — it never contains part of the response.

The suggestion typically describes only one run, with no variables. For persistent KPIs enter `{{ variable }}` in the block.

---

## Chains

### `GET /api/v1/chains`

List all workflow chains.

**Response:** `ChainResponse[]`

### `POST /api/v1/chains`

Create a new chain.

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

`ChainEntryResponse` additionally contains: `workflowName`, `projectName`, `label`.

**Response:** `201` with `ChainResponse`

### `GET /api/v1/chains/{id}`

Retrieve a chain.

**Response:** `ChainResponse`

### `PUT /api/v1/chains/{id}`

Update a chain.

**Response:** `ChainResponse`

### `DELETE /api/v1/chains/{id}`

Delete a chain.

**Response:** `204 No Content`

### `POST /api/v1/chains/{id}/execute`

Execute a chain.

**Request (optional):** `{ "key": "value" }` (input context)

**Response:** `201` with `ChainRunResponse`

### `GET /api/v1/chains/runs`

All chain runs (paginated).

**Query Parameters:** `page`, `size`, `status`, `search`

**Response:** `PageResponse<ChainRunResponse>`

### `GET /api/v1/chains/{id}/runs`

Runs of a specific chain.

**Response:** `ChainRunResponse[]`

### `GET /api/v1/chains/{id}/runs/{runId}`

Retrieve a single chain run.

**Response:** `ChainRunResponse`

### `POST /api/v1/chains/runs/{runId}/cancel`

Cancel a chain run.

**Response:** `200 OK`

---

## Chain Templates

Predefined chain templates that can be installed as chains into a project.

### `GET /api/v1/chain-templates`

List all chain templates.

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

Retrieve a single chain template.

**Response:** `ChainTemplateResponse`

### `POST /api/v1/chain-templates/{id}/install`

Install a chain template into a project (creates the chain and all referenced workflows).

**Request:**

```json
{
  "projectId": "uuid",
  "chainName": "My CI/CD Pipeline",
  "locale": "en"
}
```

| Field | Type | Required |
|---|---|---|
| `projectId` | String | Yes |
| `chainName` | String | No |
| `locale` | String (`de`/`en`) | No: language of names, labels and the steps of bilingual templates; German when omitted |

**Response:** `201` with `ChainInstallResponse`

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

List TODOs (paginated, filterable).

**Query Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `page` | int | Page (default: 0) |
| `size` | int | Entries per page (default: 50) |
| `projectId` | String | Filter by project |
| `status` | String[] | Filter by status: `OPEN`, `IN_PROGRESS`, `DONE`, `BACKLOG`, `ARCHIVED` |
| `priority` | String | Filter by priority: `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` |
| `source` | String | Filter by source: `USER`, `AI`, `WORKFLOW` |
| `tag` | String | Filter by tag |
| `q` | String | Full-text search |
| `goalId` | String | Only TODOs of this goal (V85) |

**Response:** `PageResponse<TodoResponse>` – with `planning` (goal, rank, assessment) once any of it is set

### `POST /api/v1/todos`

Create a new TODO.

**Request:**

```json
{
  "title": "Short imperative title",
  "description": "Markdown with context",
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

| Field | Type | Required |
|---|---|---|
| `title` | String | Yes |
| `description` | String | No |
| `status` | Enum | No (default: `OPEN`) |
| `priority` | Enum | No |
| `tags` | String[] | No |
| `projectId` | String | No |
| `linkedWorkflowId` | String | No |
| `source` | Enum | No |
| `sourceAgent` | String | No |
| `sessionId` | String | No |
| `agentSessionRef` | String | No — UUID of the agent conversation (`claude --resume <uuid>`). A whole command is accepted and the UUID taken from it; anything that is not a UUID is discarded. |
| `workflowRunId` | String | No |
| `referenceType` | String | No |
| `referenceId` | String | No |
| `dueDate` | Instant | No |

**Response:** `201` with `TodoResponse`

**TodoResponse Fields:** `id`, `title`, `description`, `status`, `priority`, `tags`, `projectId`, `projectName`, `linkedWorkflowId`, `linkedWorkflowName`, `source`, `sourceAgent`, `sessionId`, `agentSessionRef`, `workflowRunId`, `referenceType`, `referenceId`, `exportedProvider`, `exportedRef`, `exportedUrl`, `fixAttempts`, `lastFixRunId`, `resolutionState` (`IDLE`, `RUNNING`, `FAILED`, `SUCCEEDED`), `dueDate`, `createdAt`, `updatedAt`

### `GET /api/v1/todos/{id}`

Retrieve a single TODO.

**Response:** `TodoResponse`

### `PUT /api/v1/todos/{id}`

Fully update a TODO.

**Response:** `TodoResponse`

### `PATCH /api/v1/todos/{id}/status`

Change the status of a TODO.

**Request:**

```json
{
  "status": "IN_PROGRESS",
  "sessionId": "optional-session-id"
}
```

**Response:** `TodoResponse`

### `POST /api/v1/todos/{id}/resolve`

Automatically resolve a TODO (starts a matching fix workflow).

**Response:** `WorkflowRunResponse`

### `POST /api/v1/todos/{id}/export`

Export a TODO to an external system (e.g. Jira, Trello).

**Request (optional):**

```json
{
  "provider": "jira",
  "target": "board-id"
}
```

**Response:** `TodoExportResponse` with `referenceType`, `referenceId`, `url`

### `DELETE /api/v1/todos/{id}`

Delete a TODO.

**Response:** `204 No Content`

### `GET /api/v1/todos/count`

Count TODOs by status. Optional query parameter `projectId` counts only that project's TODOs (dashboard project filter).

**Response:** `TodoCountResponse` with `open`, `inProgress`, `done`, `backlog`, `archived`

### `GET /api/v1/todos/overview`

TODO overview with project rollups and "Needs You" items.

**Response:** `TodoOverviewResponse` with `projects` (List), `needsYou` (List), `generatedAt`, `staleThresholdDays`

### `POST /api/v1/todos/bulk-status`

Change the status of multiple TODOs at once.

**Request:**

```json
{
  "ids": ["uuid-1", "uuid-2"],
  "status": "DONE"
}
```

**Response:** `{ "updated": 2 }`

### `POST /api/v1/todos/archive-stale`

Archive stale TODOs.

**Query Parameters:** `projectId` (optional)

**Response:** `{ "archived": 5 }`

### `GET /api/v1/todos/similar`

Search for similar TODOs (for duplicate detection).

**Query Parameters:** `projectId` (required), `title` (required)

**Response:** `SimilarTodoResponse[]` with `id`, `title`, `priority`, `score`, `similarity`

### `PATCH /api/v1/todos/{id}/planning`

Set a TODO's goal, rank and assessment (V85). `null` leaves a field alone, an empty string clears it: `goalId: ""` detaches the TODO from its goal, `openQuestion: ""` marks the question as settled. `goalRank <= 0` removes the rank. Without `ratedBy` the assessment counts as human (`USER`); the server sets `ratedAt` whenever `complexity`, `suggestedEffort` or `ratingNote` is sent.

```json
{
  "goalId": "uuid",
  "goalRank": 2,
  "complexity": "M",
  "suggestedEffort": "high",
  "ratingNote": "Many files, but a clear template",
  "openQuestion": "Which video style?",
  "ratedBy": "AI"
}
```

`complexity`: `S`, `M`, `L`, `XL` · `suggestedEffort`: `low`, `medium`, `high`, `xhigh` · other values → 400.

**Response:** `TodoResponse`

---

## Goals

A goal collects TODOs across projects and shows progress (V85). See [architektur.md](architektur.md#goals--ordering-todos-across-projects-v85).

### `GET /api/v1/goals`

Goals with progress, newest first. **Query:** `status` (repeatable; default `ACTIVE` and `DONE`, archived ones only when asked for).

**Response:** `GoalResponse[]` – `id`, `title`, `description`, `status`, `targetDate`, `createdAt`, `updatedAt`, `progress` (`total`, `open`, `inProgress`, `backlog`, `done`, `openQuestions`, `percentDone`; `total` excludes ARCHIVED).

### `POST /api/v1/goals`

```json
{ "title": "Open-source launch", "description": "Repo public, README with video", "targetDate": "2026-10-15" }
```

`targetDate` as a date or ISO instant. **Response:** `201` + `GoalResponse`

### `GET /api/v1/goals/{id}`

The goal with its TODOs in work order (rank, then priority, then age) – without descriptions – and progress per project.

**Response:** `GoalDetailResponse` – `goal`, `todos[]` (`id`, `title`, `status`, `priority`, `projectId`, `projectName`, `tags`, `goalRank`, `complexity`, `suggestedEffort`, `ratingNote`, `ratedBy`, `ratedAt`, `openQuestion`, `updatedAt`), `byProject[]` (`projectId`, `projectName`, `total`, `done`)

### `PUT /api/v1/goals/{id}`

Partial update: `title`, `description`, `status`, `targetDate` (`null` = unchanged, empty = clear). **Response:** `GoalResponse`

### `DELETE /api/v1/goals/{id}`

Deletes the goal; its TODOs stay and only lose the assignment. **Response:** `204`

### `POST /api/v1/goals/{id}/todos`

Assign TODOs: `{ "todoIds": ["…"] }`. A TODO from another goal moves here; newly assigned TODOs get no rank. **Response:** `GoalDetailResponse`

### `PUT /api/v1/goals/{id}/order`

Set the order: `{ "todoIds": ["…"] }` → rank 1..n in this sequence; all other TODOs of the goal lose their rank. Every ID must already belong to the goal, else 400. **Response:** `GoalDetailResponse`

---

## Workflow Memory

### `GET /api/v1/workflows/{workflowId}/memory`

All memory entries of a workflow.

**Response:** `WorkflowMemoryResponse[]`

### `POST /api/v1/workflows/{workflowId}/memory/extract`

Extract knowledge from runs.

**Request:**

```json
{
  "runIds": ["run-uuid-1", "run-uuid-2"],
  "provider": "CLAUDE"
}
```

**Response:** `201` with `WorkflowMemoryResponse`

**WorkflowMemoryResponse Fields:** `id`, `workflowId`, `title`, `content`, `tags`, `sourceRunIds`, `provider`, `active`, `createdAt`

### `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}`

Update a memory entry.

**Request:** `{ "title": "...", "content": "..." }`

**Response:** `WorkflowMemoryResponse`

### `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}/activate`

Activate a memory entry (will be injected into subsequent runs).

**Response:** `204 No Content`

### `DELETE /api/v1/workflows/{workflowId}/memory/{memoryId}`

Delete a memory entry.

**Response:** `204 No Content`

---

## Activity Log

### `GET /api/v1/activity-log`

List activity log (paginated, filterable).

**Query Parameters:** `page` (default: 0), `size` (default: 50), `level` (`DEBUG`, `INFO`, `WARN`, `ERROR`), `category` (`SYSTEM`, `EXECUTION`, `SCHEDULE`, `WEBHOOK`, `SECURITY`, `CONTAINER`, `PLUGIN`), `workflowId`

**Response:** `PageResponse<ActivityLogResponse>`

### `POST /api/v1/activity-log`

Create a new activity log entry.

**Request:**

```json
{
  "level": "INFO",
  "message": "Manual entry",
  "source": "plugin",
  "referenceId": null,
  "workflowId": null,
  "metadata": {}
}
```

**ActivityLogResponse Fields:** `id`, `timestamp`, `level`, `category`, `message`, `source`, `referenceId`, `workflowId`, `metadata`, `createdAt`

**Response:** `201` with `ActivityLogResponse`

---

## Assistant

### `POST /api/v1/assistant/chat`

Send a chat message to the AI assistant.

**Request:**

```json
{
  "message": "How do I create a workflow for code reviews?",
  "history": [
    { "role": "user", "content": "Hello" },
    { "role": "assistant", "content": "Hello! How can I help?" }
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

| Field | Type | Required |
|---|---|---|
| `message` | String | Yes |
| `history` | MessageEntry[] | No |
| `context` | PageContext | No |
| `provider` | String | No |
| `language` | String | No |
| `sessionId` | String | No |
| `requestId` | String | No |

**Response:** `AssistantChatResponse`

```json
{
  "message": "Here is a guide...",
  "actions": [
    {
      "type": "run_workflow",
      "id": "workflow-uuid",
      "name": "Code Review",
      "label": "Start workflow",
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
      "description": "Create TODO: Add unit tests",
      "expiresAt": "2025-01-15T10:30:00Z"
    }
  ]
}
```

**Navigation Types:** `run_workflow`, `show_workflow`, `start_wish`, `show_run`, `ask_choice`

**Mutating Proposal Types** (handled server-side as `PendingTool` with confirm/reject UI): `propose_create_todo`, `propose_cancel_run`, `propose_rerun_workflow`, `propose_update_todo_status`

### `GET /api/v1/assistant/sessions`

List all chat sessions.

**Response:** `AssistantSessionSummary[]` with `id`, `preview`, `page`, `messageCount`, `startedAt`

### `GET /api/v1/assistant/sessions/{id}`

Retrieve a single chat session with messages.

**Response:** `AssistantSessionDetail` with `id`, `preview`, `page`, `entityId`, `messages`, `messageCount`, `startedAt`, `endedAt`

### `DELETE /api/v1/assistant/sessions/{id}`

Delete a chat session.

**Response:** `200 OK`

### `POST /api/v1/assistant/pending-tools/{id}/confirm`

Confirm a pending tool call (e.g. workflow start by the assistant).

**Request (optional):**

```json
{
  "sessionId": "session-uuid"
}
```

**Response:** `ToolExecutionResult` (or `410 Gone` if already processed/expired)

### `POST /api/v1/assistant/pending-tools/{id}/reject`

Reject a pending tool call.

**Request (optional):**

```json
{
  "sessionId": "session-uuid"
}
```

**Response:** `ToolExecutionResult` (or `410 Gone` if already processed/expired)

### `GET /api/v1/assistant/suggestions`

Retrieve context-related suggestions for a page.

**Query Parameters:** `page` (String, required)

**Response:** `String[]`

### `POST /api/v1/assistant/suggestions/refresh`

Regenerate suggestions for a page.

**Query Parameters:** `page` (String, required)

**Response:** `String[]`

---

## Dialog Import

Analysis and import of Claude Code dialog sessions as workflows.

### `GET /api/v1/dialog/sessions/count`

Count sessions per project directory (fast, no file reading).

**Response:** `Map<String, Integer>`

### `GET /api/v1/dialog/sessions`

List sessions with metadata.

**Query Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `projectPath` | String | No (optional: filter by project) |
| `limit` | int | Default: 50 (max: 2000 with projectPath, 500 without) |

**Response:** `DialogSessionInfo[]`

### `GET /api/v1/dialog/projects`

List all known project paths (for filter dropdown).

**Response:** `String[]`

### `POST /api/v1/dialog/sessions/analyze`

Analyze a session with AI and generate a workflow suggestion.

**Request:**

```json
{
  "filePath": "/home/user/.claude/projects/.../session.jsonl"
}
```

**Response:** `DialogWorkflowSuggestion`

### `POST /api/v1/dialog/sessions/create-workflow`

Create a workflow from a suggestion.

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Workflow Name",
  "suggestion": { ... }
}
```

| Field | Type | Required |
|---|---|---|
| `projectId` | String | Yes |
| `name` | String | No |
| `suggestion` | DialogWorkflowSuggestion | Yes |

**Response:** `WorkflowResponse`

---

## Staging (Stable Environment)

### `GET /api/v1/staging/status`

Status of the stable environment.

**Response:** `StagingStatusResponse` with `containerRunning`, `healthy`, `promotePhase`, `promoteStep`, `promoteTotalSteps`, `lastSnapshot`, `error`, `stableVersion`, `stableBuildTimestamp`

### `POST /api/v1/staging/start`

Start the stable container.

**Response:** `200 OK`

### `POST /api/v1/staging/stop`

Stop the stable container.

**Response:** `200 OK`

### `POST /api/v1/staging/promote`

Build a new snapshot and deploy as stable.

**Response:** `202 Accepted`

### `POST /api/v1/staging/promote/cancel`

Cancel an ongoing promote operation.

**Response:** `200 OK`

### `GET /api/v1/staging/sync-preview`

Preview of sync operations (what would be transferred from dev to stable?).

**Response:** `SyncPreview` with `projects`, `workflows`, `skills`, `chains` (each a list of `SyncPreviewItem`)

### `POST /api/v1/staging/sync-definitions`

Transfer definitions from dev to stable (projects, workflows, skills, chains).

**Request (optional):**

```json
{
  "projectIds": ["uuid"],
  "workflowIds": ["uuid"],
  "skillIds": ["uuid"],
  "chainIds": ["uuid"]
}
```

All fields are optional; without filters, all definitions are synchronized.

**Response:** `SyncResult` with `projectsSynced`, `workflowsSynced`, `skillsSynced`, `chainsSynced`, `skipped`, `errors`, `preSyncBackup`

---

## Testing (Test Instance)

### `GET /api/v1/testing/status`

Status of the test instance (port 8090).

**Response:** `TestingStatusResponse` with `containerRunning`, `healthy`, `testVersion`, `testBuildTimestamp`

### `POST /api/v1/testing/start`

Start the test container.

**Response:** `200 OK`

### `POST /api/v1/testing/stop`

Stop the test container.

**Response:** `200 OK`

### `POST /api/v1/testing/reset`

Reset the test instance database.

**Response:** `200 OK`

### `GET /api/v1/testing/sync-preview`

Preview of sync operations (dev → test).

**Response:** `SyncPreview`

### `POST /api/v1/testing/sync-definitions`

Transfer definitions from dev to test. Request format same as `POST /api/v1/staging/sync-definitions`.

**Response:** `SyncResult`

### `POST /api/v1/testing/report-result`

Report a test result from the test instance.

**Request:** `{ "workflowName": "...", "projectName": "...", "status": "PASSED|FAILED" }`

**Response:** `200 OK`

### `POST /api/v1/testing/pull-changes`

Pull changes from dev into the test instance.

**Response:** `SyncResult`

---

## Conversions

### `GET /api/v1/conversions/runs/{runId}/suggest`

Generate a conversion suggestion for an agentic run.

**Response:** `WorkflowSuggestion` (workflow structure as suggestion)

### `POST /api/v1/conversions/runs/{runId}/create-workflow`

Create a workflow from an agentic run.

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Converted Workflow"
}
```

**Response:** `WorkflowResponse`

---

## Workflow Pool

### `GET /api/v1/pool`

List all available pool workflows. Query `locale` (optional): language of the steps of bilingual pool files, English when omitted.

**Response:** `PoolWorkflowEntry[]`

### `GET /api/v1/pool/{id}`

Retrieve a single pool workflow.

**Response:** `PoolWorkflowEntry`

### `POST /api/v1/pool/{id}/install`

Install a pool workflow into a project.

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Optional Name",
  "forceCreate": false,
  "locale": "en"
}
```

| Field | Type | Required |
|---|---|---|
| `projectId` | String | Yes |
| `name` | String | No |
| `forceCreate` | Boolean | No |
| `locale` | String (`de`/`en`) | No: language of name, description and steps; English when omitted. A workflow installed earlier in the other language is updated, not duplicated |

**Response:** `{ "workflowId": "uuid", "workflowName": "...", "created": true }`

### `POST /api/v1/pool/export/{workflowId}`

Export an existing workflow to the pool.

**Query parameter:** `scope` = `repo` | `portable` (optional, default `portable`) — pool metadata separating repo-bound from portable entries. An unknown value yields `400`. Rule set: [workflow-pool-kuratierung.md](workflow-pool-kuratierung.md).

The export writes no instance bindings: `projectId`, `workflowId`, `runId`, `scheduleId`, `chainId`, `executionId` with a concrete value and UUID values are dropped; a UUID inside a text is replaced with `REDACTED-UUID`.

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

**Response:** `201` with `ExportToPoolResponse` (`poolId`, `filename`, `sanitizedPaths`). `sanitizedPaths` names the cleaned-up spots by path — for a `sub-workflow` block it holds `blocks[N].config.workflowId`, and the entry is only installable once that reference has been filled in.

---

## Workflow Factory

### `GET /api/v1/factory/topics`

Retrieve available topic categories.

**Response:** `Array<{ "id": "...", "label": "..." }>`

### `POST /api/v1/factory/generate`

Generate a workflow from a topic (preview, without test execution).

**Request:**

```json
{
  "topic": "code-review",
  "description": "Automated code review",
  "category": "CODE",
  "projectId": "uuid",
  "testInputs": {},
  "autoPromote": false,
  "autoFix": false,
  "maxRetries": 2,
  "provider": "CLAUDE"
}
```

| Field | Type | Required |
|---|---|---|
| `topic` | String | Yes |
| `description` | String | No |
| `category` | String | No |
| `projectId` | String | No |
| `testInputs` | Object | No |
| `autoPromote` | boolean | No |
| `autoFix` | boolean | No |
| `maxRetries` | int | No (default: 2) |
| `provider` | String | No |

**Response:** `201` with `WorkflowResponse`

### `POST /api/v1/factory/run`

Full factory pipeline: Generate → Test Instance → Verify → optionally Promote.

**Request:** `WorkflowFactoryRequest` (as above)

**Response:** `WorkflowFactoryResult` with `workflowId`, `workflowName`, `status`, `testRunId`, `testStatus`, `testError`, `promotedAsTemplate`, `durationMs`, `retryErrors`

### `POST /api/v1/factory/verify/{id}`

Verify an existing workflow on the test instance.

**Response:** `WorkflowFactoryResult`

### `POST /api/v1/factory/promote/{id}`

Promote a verified workflow as a template.

**Response:** `WorkflowFactoryResult`

---

## Wishes

### `GET /api/v1/wishes`

List all wishes.

**Response:** `WishResponse[]`

### `POST /api/v1/wishes`

Create a new wish and fulfill it asynchronously.

**Request:**

```json
{
  "text": "Create a workflow that summarizes the latest AI news daily",
  "projectId": "uuid",
  "maxIterations": 5,
  "referenceWorkflowIds": ["uuid"]
}
```

| Field | Type | Required |
|---|---|---|
| `text` | String | Yes |
| `projectId` | String | Yes |
| `maxIterations` | Integer | No |
| `referenceWorkflowIds` | String[] | No |
| `autoRefine` | boolean | No (default: `false`) |

**Response:** `201` with `WishResponse`

**WishResponse Fields:** `id`, `originalText`, `status`, `projectId`, `projectName`, `decomposition`, `generatedWorkflowIds`, `generatedWorkflowNames`, `referenceWorkflowIds`, `referenceWorkflowNames`, `generatedChainId`, `iterationCount`, `maxIterations`, `currentRunId`, `resultSummary`, `error`, `iterationHistory`, `metadata`, `autoRefine`, `createdAt`, `updatedAt`

**WishStatus Values:** `RECEIVED`, `DECOMPOSING`, `AWAITING_DECOMPOSE_APPROVAL`, `EXECUTING`, `EVALUATING`, `REFINING`, `AWAITING_CLARIFICATION`, `AWAITING_APPROVAL`, `COMPLETED`, `FAILED`, `CANCELLED`

### `GET /api/v1/wishes/{id}`

Retrieve a single wish.

**Response:** `WishResponse`

### `POST /api/v1/wishes/{id}/cancel`

Cancel a wish.

**Response:** `200 OK`

### `POST /api/v1/wishes/{id}/respond`

Provide feedback on the current iteration.

**Request:**

```json
{
  "action": "ACCEPT",
  "feedback": "Optional: adjustment requests"
}
```

| Field | Type | Required | Values |
|---|---|---|---|
| `action` | Enum | Yes | `ACCEPT`, `REFINE`, `CANCEL`, `CLARIFY`, `RETRY` |
| `feedback` | String | No | |

**Response:** `WishResponse`

### `POST /api/v1/wishes/{id}/crystallize`

Crystallize a successful wish as a template.

**Response:** `{ "templateId": "uuid" }`

### `DELETE /api/v1/wishes/{id}`

Delete a wish.

**Response:** `204 No Content`

---

## Docs

### `GET /api/v1/docs`

List available documentation files.

**Response:** `Array<{ "name": "architektur.md", "label": "Architektur", "lang": "de" }>`

### `GET /api/v1/docs/{name}`

Retrieve the content of a documentation file (`.md` files only).

**Query Parameters:** `lang` (default: `"de"`, values: `"de"`, `"en"`)

**Response:** `{ "content": "# Architecture\n..." }`

---

## Search

### `GET /api/v1/search`

Aggregated search across workflows (substring/fuzzy ranking).

**Query Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `q` | String | Search term |
| `types` | String | Types to search (default: `"workflow"`) |
| `limit` | int | Max results (default: 20, max: 50) |

**Response:**

```json
{
  "workflows": [...]
}
```

### `POST /api/v1/search/workflows/semantic`

Semantic workflow search via AI model. Returns `204 No Content` when semantic search is not enabled.

**Request:**

```json
{
  "query": "Check code quality",
  "limit": 5
}
```

**Response:** `SemanticSearchResult[]` with `workflowId`, `workflowName`, `reason`, `score`

---

## Reports

### `GET /api/v1/reports`

List available report files (`.md` files in the configured reports directory, sorted by date descending).

**Response:** `Array<{ "name": "report-2025-01.md", "label": "Report 2025 01" }>`

### `GET /api/v1/reports/{name}`

Retrieve the content of a report file (`.md` files only, path traversal protection).

**Response:** `{ "content": "# Report\n..." }`

---

## Dead Letter Queue

Failed workflow runs are automatically captured in the DLQ and can be managed there.

### `GET /api/v1/dlq`

List DLQ entries (paginated).

**Query Parameters:**

| Parameter | Type | Description |
|---|---|---|
| `status` | String | Filter: `OPEN`, `RETRIED`, `DISCARDED` |
| `page` | int | Page (default: 0) |
| `size` | int | Entries per page (default: 20) |

**Response:** `Page<DeadLetterEntry>` with `id`, `workflowRunId`, `workflowId`, `workflowName`, `errorMessage`, `failedStepIndex`, `retryCount`, `lastFailureAt`, `status`, `createdAt`, `updatedAt`

### `GET /api/v1/dlq/{id}`

Retrieve a single DLQ entry.

**Response:** `DeadLetterEntry`

### `POST /api/v1/dlq/{id}/retry`

Retry a DLQ entry (starts a fresh workflow execution with the original input context).

**Response:** `DeadLetterEntry` (status changes to `RETRIED`)

### `DELETE /api/v1/dlq/{id}`

Discard a DLQ entry.

**Response:** `204 No Content`

---

## Debug

### `GET /api/v1/debug/failed-workflows`

Workflows whose last run failed (`FAILED` / `COMPLETED_WITH_ERRORS`), sorted by last error descending.

**Response:** `BrokenWorkflowResponse[]`

---

## WebSocket

### Connection

```
Endpoint: ws://localhost:9090/ws (SockJS)
Protocol: STOMP
```

### Topics

| Topic | Description |
|---|---|
| `/topic/executions/{runId}/logs` | Workflow execution logs (steps, status, approval prompts) |
| `/topic/chains/{chainRunId}/status` | Chain execution status updates |
| `/topic/assistant/{requestId}/status` | Assistant chat progress (phase, detail, elapsed) |
| `/topic/assistant/{sessionId}/events` | Assistant push events (created items, suggestions) |
| `/topic/wishes/{wishId}/status` | Wish iteration status |
| `/topic/activity-log` | Global activity log events |

### Message Format (Execution Logs)

JSON objects distinguished by `type`. Step messages (placeholder at start and finished step) have no `type`:

```json
{ "stepIndex": 1, "status": "COMPLETED", "input": "...", "output": "...", "durationMs": 4200, "blockType": "shell", "blockName": "Build" }
```

Other types: `status`, `approval_required`/`approval_resolved`, `human_input_required`/`human_input_resolved`,
`agentic_*`, `foreach_item_started`/`foreach_item_completed`, `container_starting`/`container_started`/`container_skipped`,
`command` and `live_output` — output of running blocks, batched at most every 250 ms:

```json
{
  "type": "live_output",
  "runId": "run-uuid",
  "bufferId": "buffer-uuid",
  "lines": [{ "seq": 41, "stepIndex": 1, "kind": "line", "text": "[INFO] BUILD SUCCESS", "ts": 1757844005000 }],
  "skipped": 0
}
```

`seq` is gap-free per `bufferId`; gaps mean skipped lines. Replay: `GET /api/v1/executions/{id}/live-log`.

### Terminal WebSocket

```
Endpoint: ws://localhost:9090/ws/terminal/{runId}?ticket=...
```

For real-time container access (interactive shell). Raw WebSocket (no SockJS/STOMP). Max 3 sessions per container. Authentication via a short-lived single-use ticket from `POST /api/v1/ws-ticket` (API key in the header) — an `apiKey` query parameter is refused. The same applies to the STOMP/SockJS handshake on `/ws`.

### Handshake ticket

```
POST /api/v1/ws-ticket
Header: X-API-Key: <key>
```

Response:

```json
{ "ticket": "kxJ9…", "expiresInSeconds": 30 }
```

Redeemable exactly once, then invalid; likewise after it expires. Fetch a fresh ticket for every connect attempt, including every reconnect.
