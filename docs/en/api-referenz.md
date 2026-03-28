> *Last updated: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

# API Reference

## General

- **Base URL:** `http://localhost:9090/api/v1`
- **Content-Type:** `application/json`
- **Authentication:** Header `X-API-Key: <key>` (when `automation.security.enabled=true`)

### Error Format

All errors follow this format:

```json
{
  "error": "Error message",
  "timestamp": "2025-01-15T10:30:00Z"
}
```

For validation errors (400) additionally:

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
| 503 | Service unavailable (e.g. test instance not healthy) |

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
    "type": "CODE",
    "path": "/workspace/project",
    "config": {},
    "status": "STOPPED",
    "container": null,
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
| `type` | Enum | Yes | `CODE`, `CONTENT`, `RESEARCH`, `BUSINESS`, `PERSONAL` |
| `path` | String | Yes | Path in the container |
| `config` | Object | No | Free-form JSON |

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
  "type": "RESEARCH",
  "path": "/workspace/new",
  "config": {}
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

**Response:** `ProjectResponse` (status changes to `STARTING` then `RUNNING`)

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

Create a new workflow.

**Request:**

```json
{
  "projectId": "uuid",
  "name": "Code Review",
  "description": "Automated code review",
  "icon": "🔍",
  "blocks": [
    { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [] }
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
    { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [] }
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
    { "type": "simple", "config": { "prompt": "Review code" }, "requiredCapabilities": [] }
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

When `updateIfExists=true` and a workflow with the same name exists in the target project, it is updated instead of newly created.

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

**Query parameters:** `page` (default: 0), `size` (default: 50)

**Response:** `PageResponse<WorkflowRunResponse>`

### `GET /api/v1/workflows/{id}/runs/{runId}`

Retrieve a single run.

**Response:** `WorkflowRunResponse`

### `POST /api/v1/workflows/{id}/optimize`

Generate an optimization suggestion for a workflow (AI-based).

**Response:** `Map<String, Object>` with optimization suggestion

### `POST /api/v1/workflows/{id}/repair`

Repair a failed workflow on the test instance. Requires that the workflow has failed runs and the test instance is healthy.

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

Delete a user template. Built-in templates cannot be deleted (returns `400`).

**Response:** `204 No Content`

### `POST /api/v1/workflow-templates/from-workflow/{workflowId}`

Save an existing workflow as a new template (name, description, blocks, instructions, model are copied).

**Response:** `201` with `WorkflowTemplateResponse`

---

## Executions

### `GET /api/v1/executions`

List all executions (paginated, filterable).

**Query parameters:**

| Parameter | Type | Description |
|---|---|---|
| `page` | int | Page (default: 0) |
| `size` | int | Entries per page (default: 50) |
| `status` | String | Filter by status: `PENDING`, `RUNNING`, `PAUSED`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`, `CANCELLED`, `INTERRUPTED` |
| `workflowId` | String | Filter by workflow |
| `projectId` | String | Filter by project |

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
        "async": false
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
    "cronExpression": "0 0 2 * * ?",
    "enabled": true,
    "description": "Nightly build",
    "inputContext": {},
    "lastRunAt": null,
    "nextRunAt": "2025-01-16T02:00:00Z",
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

**Query parameters:**

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
| `endDate` | Instant | No (optional end for `CRON` schedules) |
| `maxRuns` | Integer | No (max executions, 0 = unlimited) |
| `conflictBehavior` | String | No (default: `SKIP`, values: `SKIP`, `OVERLAP`) |

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

Completion triggers automatically invoke a target workflow when a source workflow completes.

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

`status` can be `UP`, `DEGRADED` (Docker unavailable), or `DOWN` (database unavailable).
`docker` can be `CONNECTED` or `UNAVAILABLE`.

### `GET /api/v1/system/info` (Public)

System information.

**Response:**

```json
{
  "name": "MySuricates",
  "version": "0.6.2",
  "buildType": "dev",
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
  "providers": ["CLAUDE", "CODEX"],
  "default": "CLAUDE"
}
```

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

### `GET /api/v1/system/backup`

Create and download a database backup.

**Response:** File download (SQLite backup)

### `GET /api/v1/system/backups`

List all available backups.

**Response:** `BackupInfo[]`

### `GET /api/v1/system/backups/{filename}`

Download a specific backup.

**Response:** File download

### `GET /api/v1/system/settings/{key}`

Retrieve an application setting.

**Allowed keys:** `auto-todo-default`, `auto-repair-default`, `default-ai-provider`, `assistant-thinking-phrases`, `assistant-model`

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

Retrieve the current execution configuration (timeouts, token limits, etc.).

**Response:** `Map<String, Object>` with current settings

### `PUT /api/v1/system/execution-config`

Update the execution configuration.

**Request:** `Map<String, String>` with the settings to change

**Response:** `Map<String, Object>` with updated settings

### `POST /api/v1/system/repair-failed`

Automatically repair all failed workflows. Requires a healthy test instance.

**Query parameter:** `force` (boolean, default: `false`)

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

### `GET /api/v1/system/browse`

Browse directory contents (only subdirectories, no hidden folders).

**Query parameter:** `path` (String, optional, default: home directory)

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

Agentic block analysis: iterations, token consumption, success rate.

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

**Query parameters:** `page`, `size`, `status`

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

**Query parameters:**

| Parameter | Type | Description |
|---|---|---|
| `page` | int | Page (default: 0) |
| `size` | int | Entries per page (default: 50) |
| `projectId` | String | Filter by project |
| `status` | String[] | Filter by status: `OPEN`, `IN_PROGRESS`, `DONE`, `BACKLOG` |
| `priority` | String | Filter by priority: `HIGH`, `MEDIUM`, `LOW` |
| `source` | String | Filter by source: `USER`, `AI`, `WORKFLOW` |
| `tag` | String | Filter by tag |
| `q` | String | Full-text search |

**Response:** `PageResponse<TodoResponse>`

### `POST /api/v1/todos`

Create a new TODO.

**Request:**

```json
{
  "title": "Short title in imperative form",
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

### `DELETE /api/v1/todos/{id}`

Delete a TODO.

**Response:** `204 No Content`

### `GET /api/v1/todos/count`

Count of TODOs by status.

**Response:** `TodoCountResponse`

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

Activate a memory entry (will be injected into subsequent runs).

**Response:** `200 OK`

### `DELETE /api/v1/workflows/{workflowId}/memory/{memoryId}`

Delete a memory entry.

**Response:** `204 No Content`

---

## Activity Log

### `GET /api/v1/activity-log`

List activity log entries (paginated, filterable).

**Query parameters:** `page`, `size`, `level` (`INFO`, `WARN`, `ERROR`, `DEBUG`), `category`, `workflowId`

**Response:** `PageResponse<ActivityLogResponse>`

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

### `GET /api/v1/assistant/suggestions`

Retrieve context-based suggestions for a page.

**Query parameter:** `page` (String, required)

**Response:** `String[]`

### `POST /api/v1/assistant/suggestions/refresh`

Regenerate suggestions for a page.

**Query parameter:** `page` (String, required)

**Response:** `String[]`

---

## Dialog Import

Analysis and import of Claude Code dialog sessions as workflows.

### `GET /api/v1/dialog/sessions/count`

Count sessions per project directory (fast, no file reading).

**Response:** `Map<String, Integer>`

### `GET /api/v1/dialog/sessions`

List sessions with metadata.

**Query parameters:**

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

**Response:** `StagingStatusResponse` (including `stableVersion`, `stableBuildTimestamp`, health status)

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

Cancel a running promote operation.

**Response:** `200 OK`

### `GET /api/v1/staging/sync-preview`

Preview of sync operations (what would be transferred from Dev to Stable?).

**Response:** `SyncPreview`

### `POST /api/v1/staging/sync-definitions`

Transfer definitions from Dev to Stable (projects, workflows, skills).

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

Preview of sync operations (Dev to Test).

**Response:** `SyncPreview`

### `POST /api/v1/testing/sync-definitions`

Transfer definitions from Dev to Test.

**Response:** `SyncResult`

### `POST /api/v1/testing/report-result`

Report a test result from the test instance.

**Request:** `{ "workflowName": "...", "projectName": "...", "status": "PASSED|FAILED" }`

**Response:** `200 OK`

### `POST /api/v1/testing/pull-changes`

Pull changes from Dev into the test instance.

**Response:** `SyncResult`

---

## Conversions

### `GET /api/v1/conversions/runs/{runId}/suggest`

Generate a conversion suggestion for an agentic run.

**Response:** `WorkflowSuggestion` (workflow structure as a suggestion)

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

**Response:** `201` with `{ "workflowId": "uuid", "workflowName": "..." }`

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

Full factory pipeline: Generate -> Test Instance -> Verify -> optionally Promote.

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

**Response:** `Array<{ "name": "architektur.md", "label": "Architektur" }>`

### `GET /api/v1/docs/{name}`

Retrieve the content of a documentation file (only `.md` files).

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
| `/topic/executions/{runId}/logs` | Workflow execution logs |
| `/topic/chains/{chainRunId}/status` | Chain execution status updates |

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
