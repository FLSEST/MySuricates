> *Last updated: [auto-generated]*

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
    "updatedAt": "2025-01-15T10:00:00Z"
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

All fields are optional; only provided fields are updated.

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
    "name": "Code Review",
    "description": "Automated code review",
    "icon": "🔍",
    "blocks": [
      { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
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

Create a new workflow.

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Code Review",
  "description": "Automated code review",
  "icon": "🔍",
  "blocks": [
    { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
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
| `skillIds` | String[] | No | Skill IDs activated additionally to workflow skills for this block (additive) |

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
  "description": "New Description",
  "icon": "✨",
  "blocks": []
}
```

**Response:** `WorkflowResponse`

### `DELETE /api/v1/workflows/{id}`

Delete a workflow.

**Response:** `204 No Content`

### `GET /api/v1/workflows/export/{id}`

Export a workflow as portable JSON (without IDs, without project association).

**Response:** `ExportWorkflowResponse`

```json
{
  "name": "Code Review",
  "description": "Automated code review",
  "icon": "🔍",
  "blocks": [
    { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
  ],
  "instructions": null,
  "model": null,
  "version": "1.0.0",
  "exportedAt": "2025-02-22T14:00:00Z"
}
```

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
    { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [], "skillIds": [] }
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

When `updateIfExists=true` and a workflow with the same name exists in the target project, it is updated instead of created anew.

#### Block Config: `timeout_seconds`

All block types support an optional `timeout_seconds` field in the block config.
This overrides the Claude CLI timeout per block (default: global value from `automation.host-claude.timeout-seconds`, standard 300s).

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

All runs of a workflow (descending by start time, paginated).

**Query Parameters:** `page` (default: 0), `size` (default: 50)

**Response:** `PageResponse<WorkflowRunResponse>`

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

Repair a failed workflow on the test instance. Requires the workflow to have failed runs and the test instance to be healthy.

**Response:** `202 Accepted` with `{ "status": "repair_started", "workflowId": "...", "workflowName": "..." }`

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

---

## Workflow Templates

### `GET /api/v1/workflow-templates`

List all workflow templates (sorted by `sortOrder`).

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

Retrieve a single template.

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

**Response:** `PageResponse<WorkflowRunResponse>`

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
  "question": "Please enter file name:",
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

### `DELETE /api/v1/skills/{id}`

Delete a skill.

**Response:** `204 No Content`

### `GET /api/v1/skills/export/{id}`

Export a skill as JSON (including capabilities).

**Response:** `ExportSkillResponse`

### `POST /api/v1/skills/import`

Import a skill from JSON.

**Request:** `ImportSkillRequest` (exported format)

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

**Response:** `CalendarDataResponse` with `upcoming` and `pastRuns`

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
| `maxRuns` | Integer | No (max executions, 0 = unlimited) |
| `conflictBehavior` | String | No (default: `SKIP`, values: `SKIP`, `OVERLAP`) |
| `catchUpOnResume` | boolean | No (default: `false`) |

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

### `GET /api/v1/schedules/maintenance`

Query maintenance mode status.

**Response:** `{ "enabled": false }`

### `POST /api/v1/schedules/maintenance`

Toggle maintenance mode on/off. In maintenance mode, no scheduled executions are started.

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

List all webhooks.

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

Retrieve a single webhook.

**Response:** `WebhookTriggerResponse`

### `GET /api/v1/webhooks/workflow/{workflowId}`

Webhooks of a specific workflow.

**Response:** `WebhookTriggerResponse[]`

### `POST /api/v1/webhooks`

Create a new webhook (token is generated automatically).

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

**Response:** `201` with `WebhookTriggerResponse`

### `POST /api/v1/webhooks/{id}/enable`

Enable a webhook.

**Response:** `WebhookTriggerResponse`

### `POST /api/v1/webhooks/{id}/disable`

Disable a webhook.

**Response:** `WebhookTriggerResponse`

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

Retrieve triggers where the specified workflow acts as the source.

**Response:** `CompletionTriggerResponse[]`

### `GET /api/v1/completion-triggers/workflow/{workflowId}/as-target`

Retrieve triggers where the specified workflow acts as the target.

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

List of all providers with connection status, actions, and account info.

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
    "accountEmail": "user@gmail.com",
    "accountName": "Max Mustermann",
    "connectedAt": "2026-04-01T10:00:00Z",
    "scopes": "https://www.googleapis.com/auth/calendar",
    "optionalScopes": [],
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

OAuth callback (public, no API key). Exchanges the authorization code for tokens and closes the popup window via `postMessage`.

### `POST /api/v1/integrations/{providerId}/credentials`

Save client credentials (Client ID + Secret). Stored AES-256-GCM encrypted in the database.

**Body:**
```json
{ "clientId": "...", "clientSecret": "..." }
```

### `DELETE /api/v1/integrations/{providerId}/connection`

Disconnect the OAuth connection. Deletes stored tokens.

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

Check the onboarding status of a project (active profiles, generated file).

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

### `PUT /api/v1/onboarding-profiles/{id}`

Update profile content (automatically creates a history entry).

**Response:** `OnboardingProfileResponse`

### `DELETE /api/v1/onboarding-profiles/{id}`

Delete a profile.

**Response:** `204 No Content`

### `POST /api/v1/onboarding-profiles/{id}/reset`

Reset a built-in profile to its original content.

**Response:** `OnboardingProfileResponse`

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

Retrieve todo triggers that have the specified workflow as their target.

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
| `minPriority` | No | Optional: minimum priority (`"LOW"`, `"MEDIUM"`, `"HIGH"`, `"CRITICAL"`) |
| `description` | No | Human-readable description |
| `maxAttempts` | No | Max fix attempts per TODO (default: 3) |
| `autoClose` | No | Automatically close TODO on GREEN gate (default: true) |
| `processBacklog` | No | Process existing matching TODOs on creation (default: true) |
| `contextTemplate` | No | Static context variables, merged with TODO data |

**Response:** `201` with `TodoTriggerResponse`

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

## Notifications

Notifications on workflow completion (webhook or desktop).

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

`status` can be `UP`, `DEGRADED` (Docker unavailable) or `DOWN` (database unavailable).
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

Retrieve system capabilities (API version, app version, available routes and block types).

**Response:** `SystemCapabilitiesResponse`

### `GET /api/v1/system/ai-providers/PI/catalog`

Retrieve the model catalog of the Pi provider.

**Response:** `PiCatalog`

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

Run SQLite database VACUUM.

**Response:** `Map<String, Object>`

### `GET /api/v1/system/backup`

Download a database backup.

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

**Allowed keys:** `auto-todo-default`, `auto-repair-default`, `auto-repair-mode`, `auto-repair-max-retries`, `auto-repair-timeout-seconds`, `default-ai-provider`, `assistant-thinking-phrases`, `assistant-model`, `suggestions-model`, `suggestions-enabled`, `backup-retain-count`

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

### `GET /api/v1/system/execution-config`

Retrieve current execution configuration (timeouts, token limits, etc.).

**Response:** `Map<String, Object>` with current settings

### `PUT /api/v1/system/execution-config`

Update execution configuration.

**Request:** `Map<String, String>` with the settings to change

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

Render a Markdown integration snippet for a project (for AGENTS.md etc.).

**Query Parameters:** `projectPath` (optional)

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

### `GET /api/v1/learning/platform-stats`

Platform-wide statistics (projects, workflows, skills, schedules, chains, TODOs, wishes, assistant).

**Response:** `PlatformStatsResponse`

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
  "stopOnFailure": true
}
```

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
    "name": "CI/CD Pipeline",
    "description": "Build, Test, Deploy Chain",
    "icon": "🔄",
    "entries": [
      { "templateId": "workflow-template-id", "label": "Build" }
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
  "chainName": "My CI/CD Pipeline"
}
```

| Field | Type | Required |
|---|---|---|
| `projectId` | String | Yes |
| `chainName` | String | No |

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

**Response:** `PageResponse<TodoResponse>`

### `POST /api/v1/todos`

Create a new TODO.

**Request:**

```json
{
  "title": "Short imperative title",
  "description": "Markdown with context",
  "priority": "HIGH",
  "tags": ["backend", "bug"],
  "projectId": "uuid",
  "source": "AI",
  "sourceAgent": "claude-code",
  "sessionId": "shell-session-id"
}
```

**Response:** `201` with `TodoResponse`

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

Count TODOs by status.

**Response:** `TodoCountResponse` with `open`, `inProgress`, `done`, `backlog`, `archived`

### `GET /api/v1/todos/overview`

TODO overview with project rollups and "Needs You" items.

**Response:** `TodoOverviewResponse`

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

### `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}`

Update a memory entry.

**Request:** `{ "title": "...", "content": "..." }`

**Response:** `WorkflowMemoryResponse`

### `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}/activate`

Activate a memory entry (injected into subsequent runs).

**Response:** `204 No Content`

### `DELETE /api/v1/workflows/{workflowId}/memory/{memoryId}`

Delete a memory entry.

**Response:** `204 No Content`

---

## Activity Log

### `GET /api/v1/activity-log`

List activity log (paginated, filterable).

**Query Parameters:** `page`, `size`, `level` (`INFO`, `WARN`, `ERROR`, `DEBUG`), `category` (`SYSTEM`, `EXECUTION`, `SCHEDULE`, `WEBHOOK`, `SECURITY`, `CONTAINER`, `PLUGIN`), `workflowId`

**Response:** `PageResponse<ActivityLogResponse>`

### `POST /api/v1/activity-log`

Create a new activity log entry.

**Request:**

```json
{
  "level": "INFO",
  "message": "Manual entry",
  "source": "manual",
  "referenceId": null,
  "workflowId": null,
  "metadata": {}
}
```

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
    "projectId": "project-uuid"
  },
  "provider": "CLAUDE",
  "sessionId": "session-uuid"
}
```

| Field | Type | Required |
|---|---|---|
| `message` | String | Yes |
| `history` | MessageEntry[] | No |
| `context` | PageContext | No |
| `provider` | String | No |
| `sessionId` | String | No |

**Response:** `AssistantChatResponse`

```json
{
  "message": "Here is a guide...",
  "actions": [
    {
      "type": "run_workflow",
      "id": "workflow-uuid",
      "name": "Code Review",
      "label": "Start workflow"
    }
  ],
  "model": "claude-sonnet-4-6"
}
```

Action types: `run_workflow`, `show_workflow`, `start_wish`, `show_run`, `ask_choice`

### `GET /api/v1/assistant/sessions`

List all chat sessions.

**Response:** `AssistantSessionSummary[]`

### `GET /api/v1/assistant/sessions/{id}`

Retrieve a single chat session with messages.

**Response:** `AssistantSessionDetail`

### `DELETE /api/v1/assistant/sessions/{id}`

Delete a chat session.

**Response:** `200 OK`

### `POST /api/v1/assistant/pending-tools/{id}/confirm`

Confirm a pending tool call (e.g. workflow start by assistant).

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

Retrieve context-based suggestions for a page.

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

**Response:** `StagingStatusResponse` (incl. `stableVersion`, `stableBuildTimestamp`, health status)

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

**Response:** `SyncPreview`

### `POST /api/v1/staging/sync-definitions`

Transfer definitions from dev to stable (projects, workflows, skills).

**Request (optional):** `SyncDefinitionsRequest` with optional filters

**Response:** `SyncResult`

---

## Testing (Test Instance)

### `GET /api/v1/testing/status`

Status of the test instance (port 8090).

**Response:** `TestingStatusResponse`

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

Transfer definitions from dev to test.

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

List all available pool workflows.

**Response:** `PoolWorkflowEntry[]`

### `GET /api/v1/pool/{id}`

Retrieve a single pool workflow.

**Response:** `PoolWorkflowEntry`

### `POST /api/v1/pool/{id}/install`

Install a pool workflow into a project.

**Request:**

```json
{
  "projectId": "uuid"
}
```

**Response:** `{ "workflowId": "uuid", "workflowName": "...", "created": true }`

### `POST /api/v1/pool/export/{workflowId}`

Export an existing workflow to the pool.

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

**Response:** `201` with `ExportToPoolResponse` (`poolId`, `filename`)

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

**Response:** `WorkflowFactoryResult`

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

```json
{
  "timestamp": "2025-01-15T10:00:05Z",
  "level": "INFO",
  "message": "Executing step 1: Review code..."
}
```

Level: `INFO`, `WARN`, `ERROR`, `DEBUG`

### Terminal WebSocket

```
Endpoint: ws://localhost:9090/ws/terminal
```

For real-time container access (interactive shell).
