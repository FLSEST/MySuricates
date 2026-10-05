<!-- Erzeugt von scripts/docs-reference.py – nicht von Hand ändern -->

# API Reference

> **Scope:** The MySuricates REST API, generated from the code from “Error codes” on
> (`python3 scripts/docs-reference.py --write`, see [Development → Generated references](entwicklung.md#generated-references)).
> The curated short reference for external agents, with examples, is [AGENTS.md](../../AGENTS.md).

One table per area (first path segment after `/api/v1`): method and path, as purpose the first sentence
of the handler method's Javadoc – without Javadoc the cell stays empty – and the module from
`@RequiresModule` (“core” = always on). Request and response fields are in the DTOs under
`ai-automation-backend/src/main/java/com/automation/dto/`, the behaviour in the controller named in the
“Source” line.

## General

- **Base URL:** `http://localhost:9090/api/v1`, content type `application/json`
- **Authentication:** header `X-API-Key: <key>` (when `automation.security.enabled=true`)
- **Proxy:** always call `curl` with an API key as `curl --noproxy '*'` – with `http_proxy` set,
  curl sends even requests to `localhost` through the proxy, key included. The documentation gate
  `doc-facts-check.sh` checks every example in `docs/` and `AGENTS.md` for it.
- **Modules:** if the module of an endpoint is switched off, it answers `404` with code `MODULE_DISABLED`.
- **Pagination:** paged lists take `page` (from 0) and `size` and return a page object with `content`
  and `totalElements`.

### Error format

```json
{ "code": "INTERNAL_ERROR", "error": "Error message", "timestamp": "2025-01-15T10:30:00Z" }
```

`details` is only included when needed (`@JsonInclude(NON_NULL)`), validation errors
(`VALIDATION_FAILED`) also carry `fieldErrors` (field → message).

`params` (an object of strings) is only included when code and params describe the message completely –
e.g. `{"code":"WORKFLOW_SEALED","params":{"name":"Nightly"}}`, empty (`{}`) for a fixed text. The UI
then shows its own translation of the code; `error` stays the English text for logs and API clients.
Without `params`, `error` is free text from the throw site and is shown as it is.

Where one code stands for many messages (`INVALID_ARGUMENT`, `STATE_CONFLICT`, …), `messageKey` comes
along too – a key of the backend enum `ErrorMessage`, e.g.
`{"code":"INVALID_ARGUMENT","messageKey":"INVALID_CRON","params":{"expression":"…"}}`. The UI then
translates the key instead of the code.

## WebSocket

STOMP over SockJS on `ws://localhost:9090/ws`. The handshake needs a short-lived single-use ticket from
`POST /api/v1/ws-ticket` (API key in the header, response `{ "ticket": "kxJ9…", "expiresInSeconds": 30 }`) –
redeemable exactly once, a fresh one for every connect attempt (including every reconnect). An `apiKey`
query parameter is refused.

| Topic | Content |
|---|---|
| `/topic/executions/{runId}/logs` | Workflow execution logs (steps, status, approval prompts) |
| `/topic/chains/{chainRunId}/status` | Chain execution status updates |
| `/topic/assistant/{requestId}/status` | Assistant chat progress (phase, detail, elapsed) |
| `/topic/assistant/{sessionId}/events` | Assistant push events (created items, suggestions) |
| `/topic/wishes/{wishId}/status` | Wish iteration status |
| `/topic/activity-log` | Global activity log events |
| `/topic/dashboard/runs` | After a finished run is saved `{"type":"run_completed","runId":…,"workflowId":…,"status":…}` – only ids and status |

Execution logs are JSON objects distinguished by `type`; step messages have none. Other types: `status`,
`approval_required`/`approval_resolved`, `human_input_required`/`human_input_resolved`, `agentic_*`,
`foreach_item_started`/`foreach_item_completed`, `container_starting`/`container_started`/`container_skipped`,
`command` and `live_output` – output of running blocks, batched at most every 250 ms, with `seq` gap-free
per `bufferId` (gaps = skipped lines; replay via `GET /api/v1/executions/{id}/live-log`).

The terminal (`ws://localhost:9090/ws/terminal/{runId}?ticket=...`) is a raw WebSocket without
SockJS/STOMP, likewise with a fresh ticket, at most 3 sessions per container.

## Error codes

From `ErrorCode` (`ai-automation-backend/src/main/java/com/automation/exception/ErrorCode.java`). If the throw site gives no text of its own, the default text is in the field `error`.

| Code | HTTP status | Default text |
|---|---|---|
| `RESOURCE_NOT_FOUND` | 404 | Resource not found |
| `MODULE_DISABLED` | 404 | Module disabled |
| `MODULES_NOT_EDITABLE` | 403 | Modules cannot be switched at runtime |
| `VALIDATION_FAILED` | 400 | Validation failed |
| `INVALID_ARGUMENT` | 400 | Invalid argument |
| `WORKFLOW_EXECUTION_FAILED` | 500 | Workflow execution failed |
| `CONTAINER_ERROR` | 500 | Container error |
| `STAGING_ERROR` | 500 | Staging error |
| `STATE_CONFLICT` | 409 | State conflict |
| `WORKFLOW_SEALED` | 409 | Workflow is sealed |
| `WORKFLOW_TAMPERED` | 409 | Sealed workflow definition was modified |
| `SKILL_PINNED_BY_SEAL` | 409 | Skill is used by a sealed workflow |
| `SEAL_IMMUTABLE` | 403 | Unsealing is disabled in the startup configuration |
| `UNATTENDED_REQUIRES_SEAL` | 403 | Unattended runs require a sealed workflow |
| `UNATTENDED_PLATFORM_ACCESS` | 403 | Unattended runs of workflows that access the platform directly are refused |
| `WEBHOOK_SIGNATURE_INVALID` | 401 | Missing or invalid webhook signature |
| `NO_TASK_TARGET` | 400 | No task export target configured |
| `PROVIDER_NOT_CONNECTED` | 409 | Integration provider not connected |
| `PROVIDER_NOT_TASK_CAPABLE` | 400 | Integration provider cannot receive tasks |
| `SERVICE_UNAVAILABLE` | 503 | Service unavailable |
| `TOKEN_BUDGET_EXCEEDED` | 429 | Token budget exceeded |
| `RATE_LIMITED` | 429 | Rate limit exceeded |
| `PAYLOAD_TOO_LARGE` | 413 | Payload too large |
| `OPERATION_TIMEOUT` | 504 | Operation timed out |
| `METHOD_NOT_ALLOWED` | 405 | Method not allowed |
| `RUN_DELETION_DISABLED` | 403 | Deleting runs is disabled |
| `INTERNAL_ERROR` | 500 | Internal server error |

## /api/v1/activity-log

Source: `ActivityLogController` – Activity log: read the platform's event log and let plugins add their own entries.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/activity-log` | Lists activity log entries page by page, newest first, optionally filtered by level, category and workflow. | core |
| `POST /api/v1/activity-log` | Writes an entry of category `PLUGIN` to the activity log and returns it with 201. | core |

## /api/v1/assistant

Sources:

- `AssistantController` – AI assistant chat: messages, stored chat sessions and confirming or rejecting the tools it proposes.
- `PageSuggestionController` – Quick-action suggestions the assistant offers per page of the UI.

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/assistant/chat` | Sends a message to the AI assistant and returns its answer with suggested actions and pending tools. | `assistant` |
| `POST /api/v1/assistant/pending-tools/{id}/confirm` | Executes a previously proposed tool after explicit user confirmation. | `assistant` |
| `POST /api/v1/assistant/pending-tools/{id}/reject` | Rejects a tool the assistant proposed, without executing it. | `assistant` |
| `GET /api/v1/assistant/sessions` | Lists the most recent assistant chat sessions, newest first. | `assistant` |
| `GET /api/v1/assistant/sessions/{id}` | Returns one assistant chat session with its messages. | `assistant` |
| `DELETE /api/v1/assistant/sessions/{id}` | Deletes an assistant chat session. | `assistant` |
| `GET /api/v1/assistant/suggestions` | Returns the stored quick-action suggestions for `page`, or built-in defaults if none are stored yet. | `assistant` |
| `POST /api/v1/assistant/suggestions/refresh` | Generates new suggestions for `page` with an AI call based on recent assistant sessions, stores and returns them. | `assistant` |

## /api/v1/audit-findings

Source: `AuditController` – Audit center: results of audit workflows (gate status per report), the findings derived from them and their TODOs.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/audit-findings` | Lists findings across all audits page by page, most severe first, then most recently seen. | `audit` |
| `GET /api/v1/audit-findings/{id}` | Returns one finding with its linked TODOs. | `audit` |
| `PATCH /api/v1/audit-findings/{id}` | Changes a finding's status and/or disposition note; fields left out stay unchanged. | `audit` |
| `POST /api/v1/audit-findings/{id}/todo-links` | Links an existing TODO (`todoId`) to the finding. | `audit` |
| `DELETE /api/v1/audit-findings/{id}/todo-links/{todoId}` | Removes the link between a finding and a TODO; answers 204. | `audit` |
| `POST /api/v1/audit-findings/{id}/todos` | Creates a TODO managed by MySuricates from the finding and returns the finding with its TODO links. | `audit` |

## /api/v1/audit-reports

Source: `AuditController` – Audit center: results of audit workflows (gate status per report), the findings derived from them and their TODOs.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/audit-reports/{id}` | Returns one audit report including its Markdown text. | `audit` |

## /api/v1/audits

Source: `AuditController` – Audit center: results of audit workflows (gate status per report), the findings derived from them and their TODOs.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/audits` | Lists every audit (per project and `auditKey`) with its latest valid report and finding counts. | `audit` |
| `GET /api/v1/audits/{auditKey}` | Returns one audit: its summary with finding counts and its latest valid report including the text. | `audit` |
| `GET /api/v1/audits/{auditKey}/reports` | Lists the reports of an audit page by page, newest first; `projectId` is required. | `audit` |

## /api/v1/chain-templates

Source: `ChainTemplateController` – Built-in chain templates that can be installed into a project as a chain with its workflows.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/chain-templates` | Lists the built-in chain templates with their entries. | `chains` |
| `GET /api/v1/chain-templates/{id}` | Returns one built-in chain template. | `chains` |
| `POST /api/v1/chain-templates/{id}/install` | Installs a chain template into a project: creates one workflow per entry plus the chain, answers 201. | `chains` |

## /api/v1/chains

Source: `ChainController` – Workflow chains: define sequences of workflows, start them and follow their runs.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/chains` | Lists all workflow chains. | `chains` |
| `POST /api/v1/chains` | Creates a chain from an ordered list of workflow entries and returns it with 201. | `chains` |
| `GET /api/v1/chains/runs` | Lists the runs of all chains page by page, newest first. | `chains` |
| `POST /api/v1/chains/runs/{runId}/cancel` | Asks a running chain run to stop; it starts no further entries. | `chains` |
| `GET /api/v1/chains/{id}` | Returns one chain with its entries. | `chains` |
| `PUT /api/v1/chains/{id}` | Updates a chain; fields left out stay unchanged, `entries` replaces the whole entry list. | `chains` |
| `DELETE /api/v1/chains/{id}` | Deletes a chain; answers 204. | `chains` |
| `POST /api/v1/chains/{id}/execute` | Starts a chain run in the background and returns it with 201; the entries then run one after another. | `chains` |
| `GET /api/v1/chains/{id}/runs` | Lists the runs of one chain, newest first. | `chains` |
| `GET /api/v1/chains/{id}/runs/{runId}` | Returns one chain run with its entry runs. | `chains` |

## /api/v1/completion-triggers

Source: `CompletionTriggerController` – Completion triggers: start a target workflow automatically when a source workflow finishes.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/completion-triggers` | Lists all completion triggers. | core |
| `POST /api/v1/completion-triggers` | Creates a completion trigger from source to target workflow and returns it with 201. | core |
| `GET /api/v1/completion-triggers/workflow/{workflowId}/as-source` | Lists the triggers that fire when the given workflow finishes. | core |
| `GET /api/v1/completion-triggers/workflow/{workflowId}/as-target` | Lists the triggers that start the given workflow. | core |
| `GET /api/v1/completion-triggers/{id}` | Returns one completion trigger. | core |
| `DELETE /api/v1/completion-triggers/{id}` | Deletes a completion trigger; answers 204. | core |
| `POST /api/v1/completion-triggers/{id}/disable` | Disables a trigger without deleting it. | core |
| `POST /api/v1/completion-triggers/{id}/enable` | Enables a trigger; the cycle check runs again first. | core |

## /api/v1/conversions

Source: `ConversionController` – Turns the successful agentic steps of a run into a reusable workflow.

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/conversions/runs/{runId}/create-workflow` | Creates a workflow from a run's suggestion in the given project. | `conversion` |
| `GET /api/v1/conversions/runs/{runId}/suggest` | Suggests a workflow built from the agentic steps of a run that reached their goal. | `conversion` |

## /api/v1/dashboard

Sources:

- `DashboardLayoutController` – Version history of the dashboard layout.
- `DashboardReportAssistController` – The editor's AI proposal for a `dashboard-report` block.
- `DashboardWorkflowController` – Data of the dashboard's workflow widgets (spec E3).

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/dashboard/layouts` | Lists saved dashboard layout versions page by page, newest first, without their content. | core |
| `POST /api/v1/dashboard/layouts` | Saves the dashboard layout as a new version (`201`), or answers `200` when it equals the newest one. | core |
| `GET /api/v1/dashboard/layouts/{id}` | Returns one dashboard layout version including its layout content. | core |
| `PATCH /api/v1/dashboard/layouts/{id}` | Sets or removes (`null`) the name of a layout version. | core |
| `POST /api/v1/dashboard/report-assist` | Has an AI propose the fields of a `dashboard-report` block for `workflowId` from one of its runs. | `dashboard-report` |
| `GET /api/v1/dashboard/workflow-results` | Ids outside the allowed shape or more than DashboardWorkflowService.MAX_WORKFLOWS of them → 400. | core |

## /api/v1/debug

Source: `WorkflowDebugController` – Workflow-Debug endpoints — back the /debug "what is currently broken" page.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/debug/failed-workflows` | Workflows whose most recent run failed (FAILED / COMPLETED_WITH_ERRORS), latest first. | `debug` |

## /api/v1/dialog

Source: `DialogController` – Dialog import: turns recorded Claude Code sessions (JSONL files under `~/.claude/projects`) into workflows.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/dialog/projects` | Gibt alle bekannten Projekt-Pfade zurück (für Filter-Dropdown). | `dialog-import` |
| `GET /api/v1/dialog/sessions` | Listet Sessions mit Metadaten. | `dialog-import` |
| `POST /api/v1/dialog/sessions/analyze` | Analysiert eine Session mit KI und liefert eine Workflow-Suggestion. | `dialog-import` |
| `GET /api/v1/dialog/sessions/count` | Zählt Sessions pro Projekt-Verzeichnis (schnell, kein File-Lesen). | `dialog-import` |
| `POST /api/v1/dialog/sessions/create-workflow` | Erstellt einen Workflow aus einer Suggestion (mit optionalen User-Anpassungen). | `dialog-import` |

## /api/v1/dlq

Source: `DeadLetterController` – Dead letter queue: failed workflow runs, kept for retry or discard.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/dlq` | Lists dead letter entries page by page, most recent failure first; optional `status` filter. | core |
| `GET /api/v1/dlq/{id}` | Returns one dead letter entry. | core |
| `DELETE /api/v1/dlq/{id}` | Discards a dead letter entry: it is marked `DISCARDED`, not deleted; answers 204. | core |
| `POST /api/v1/dlq/{id}/retry` | Starts the workflow again with the input context of the failed run and marks the entry as retried. | core |

## /api/v1/docs

Source: `DocsController` – Serves the Markdown documentation from the configured docs directory (`automation.docs.directory`) to the UI.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/docs` | Lists the Markdown documents of the docs directory, German and English, each with name, label and language. | core |
| `GET /api/v1/docs/{name}` | Returns the Markdown content of one document; `lang` is `de` (default) or `en`. | core |

## /api/v1/executions

Source: `ExecutionController` – Workflow runs: list and inspect them, control running ones and answer the questions they wait on.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/executions` | Lists workflow runs page by page, newest first, without their steps (fetch one run for those). | core |
| `GET /api/v1/executions/pending-input` | Alle Läufe, die gerade auf eine Antwort warten — Approvals und Human-Input, die ältesten zuerst. | core |
| `GET /api/v1/executions/{id}` | Returns one workflow run including all its steps. | core |
| `DELETE /api/v1/executions/{id}` | Loescht einen Lauf samt Schritten und den losen Verweisen darauf. | core |
| `GET /api/v1/executions/{id}/approval` | Returns the approval or human input the run is waiting for, or `{"pending": false}`. | core |
| `POST /api/v1/executions/{id}/approve` | Beantwortet die offene Frage des Laufs. | core |
| `POST /api/v1/executions/{id}/cancel` | Cancels a running or queued run. | core |
| `POST /api/v1/executions/{id}/command` | Sends an ad-hoc command to the Claude session in the container of a running run and returns its output. | core |
| `GET /api/v1/executions/{id}/live-log` | Gepufferte Live-Zeilen eines Laufs (nur im Speicher, bis ~10 min nach Laufende) fuer das Replay nach einem Reload. | core |
| `PATCH /api/v1/executions/{id}/notes` | Stores a free-text note on the run; a blank `notes` removes it. | core |
| `POST /api/v1/executions/{id}/pause` | Pauses a running run before its next block; answers 200 even if the run is not active. | core |
| `POST /api/v1/executions/{id}/resume` | Resumes a paused run; answers 200 even if the run is not active. | core |
| `POST /api/v1/executions/{id}/resume-from-step` | Continues an `INTERRUPTED` run after its last completed step; without completed steps it starts over. | core |
| `POST /api/v1/executions/{id}/retry` | Starts the run's workflow again as a new run with the same input context. | core |
| `POST /api/v1/executions/{id}/todos/approve` | Creates (`approved: true`) or discards the TODOs a run proposed for approval. | core |

## /api/v1/factory

Source: `WorkflowFactoryController`

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/factory/generate` | Generate a workflow from a topic (preview only, no testing). | `factory` |
| `POST /api/v1/factory/promote/{id}` | Promote a verified workflow as a template. | `factory` |
| `POST /api/v1/factory/run` | Full factory pipeline: generate → deploy to test → verify → optionally promote. | `factory` |
| `GET /api/v1/factory/topics` | Get available topic categories for the factory. | `factory` |
| `POST /api/v1/factory/verify/{id}` | Verify an existing workflow on the test instance. | `factory` |

## /api/v1/goals

Sources:

- `GoalController` – Ziele: TODOs projektuebergreifend auf ein Ergebnis hin sammeln, ordnen und den Fortschritt zeigen.
- `GoalWorkflowController` – Workflows am Ziel oder an einem Arbeitspaket: anzeigen, anhaengen, Rolle aendern, loesen.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/goals` | Ohne `status`: aktive und erledigte Ziele, neueste zuerst. | `goals` |
| `POST /api/v1/goals` | Creates a goal (201); `title` is required, optional `projectIds` are attached in that order. | `goals` |
| `GET /api/v1/goals/{goalId}/workflows` | Lists the workflows attached to the goal: the guard first (if switched on), then those on the goal, then those on work packages. | `goals` |
| `POST /api/v1/goals/{goalId}/workflows` | Attaches a workflow to the goal or one of its work packages (201); `role` is `WORKER` (default) or `STATUS`. | `goals` |
| `PATCH /api/v1/goals/{goalId}/workflows/{attachmentId}` | Changes the role of an attachment (`WORKER` or `STATUS`); the `guard` entry cannot be changed (400). | `goals` |
| `DELETE /api/v1/goals/{goalId}/workflows/{attachmentId}` | Loest die Zuordnung, der Workflow bleibt; `guard` → 400 (der Waechter hat seinen Schalter). | `goals` |
| `GET /api/v1/goals/{id}` | Returns the goal with its TODOs, progress per project, projects, work packages and milestones. | `goals` |
| `PUT /api/v1/goals/{id}` | Partially updates a goal: `null` keeps a field, an empty `description` or `targetDate` clears it. | `goals` |
| `DELETE /api/v1/goals/{id}` | Loescht das Ziel; seine TODOs bleiben und verlieren nur die Zuordnung, ein Waechter pausiert. | `goals` |
| `GET /api/v1/goals/{id}/candidates` | „Könnte dazugehören“: offene TODOs ohne Ziel, standardmaessig nur aus den Projekten des Ziels (`allProjects=true`: aus allen). | `goals` |
| `POST /api/v1/goals/{id}/guard` | Ziel-Waechter ein- oder ausschalten: beim ersten Einschalten Workflow aus der Vorlage `goal-guard` plus taeglicher Zeitplan, danach nur noch Zeitplan an/aus. | `goals` |
| `GET /api/v1/goals/{id}/milestones` | Meilensteine des Ziels nach Datum, dann Titel, mit Zustand und Paket-Zaehlern. | `goals` |
| `POST /api/v1/goals/{id}/milestones` | Creates a milestone (201); `dueDate` is required, `workPackageIds` must belong to the same goal. | `goals` |
| `PUT /api/v1/goals/{id}/milestones/{milestoneId}` | `workPackageIds` ersetzt die Verknuepfungen; `reached` nur ohne verknuepfte Pakete. | `goals` |
| `DELETE /api/v1/goals/{id}/milestones/{milestoneId}` | Loescht den Meilenstein; die verknuepften Arbeitspakete bleiben. | `goals` |
| `PUT /api/v1/goals/{id}/order` | Reihenfolge: die genannten TODOs bekommen Rang 1..n, alle uebrigen des Ziels keinen. | `goals` |
| `POST /api/v1/goals/{id}/projects` | Projekt ans Ende der Zielprojekte haengen; schon zugeordnet = unveraendert (idempotent). | `goals` |
| `PUT /api/v1/goals/{id}/projects/order` | Zielprojekte neu ordnen: die genannten vorn, die uebrigen in bisheriger Folge dahinter. | `goals` |
| `DELETE /api/v1/goals/{id}/projects/{projectId}` | Projekt vom Ziel loesen. | `goals` |
| `GET /api/v1/goals/{id}/relations` | Relationskarte: je TODO Herkunft, Bearbeitung (Fix-Workflow, passende TodoTrigger) und Ergebnis. | `goals` |
| `POST /api/v1/goals/{id}/todos` | Ordnet TODOs dem Ziel zu (ohne Rang – sie stehen hinter den eingeordneten). | `goals` |
| `GET /api/v1/goals/{id}/work-packages` | Arbeitspakete des Ziels nach Projekt-Position und Position im Projekt, mit Zustand (OPEN/DUE_SOON/OVERDUE/REACHED auf Basis des Endes; ohne Zeitraum nur OPEN/REACHED) und Zaehlern. | `goals` |
| `POST /api/v1/goals/{id}/work-packages` | Adds a work package at the end of its project (201); without `projectId` only if the goal has exactly one project. | `goals` |
| `PUT /api/v1/goals/{id}/work-packages/order` | Arbeitspakete EINES Projekts neu ordnen; Antwort: alle Arbeitspakete des Ziels. | `goals` |
| `PUT /api/v1/goals/{id}/work-packages/{workPackageId}` | Partially updates a work package; `""` clears a date, the result needs both dates or none (else 400). | `goals` |
| `DELETE /api/v1/goals/{id}/work-packages/{workPackageId}` | Loescht das Arbeitspaket; seine TODOs bleiben im Ziel, Meilensteine verlieren die Verknuepfung. | `goals` |

## /api/v1/integrations

Source: `IntegrationController` – Integration providers and their connections.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/integrations` | Lists all providers with connection status, actions and account info; top-level fields describe `default`. | `integrations` |
| `GET /api/v1/integrations/{providerId}` | Returns one provider with the same fields as the list. | `integrations` |
| `GET /api/v1/integrations/{providerId}/actions` | Lists the provider's actions; `available` is `false` when the connection lacks a required scope. | `integrations` |
| `POST /api/v1/integrations/{providerId}/actions/{action}` | Runs one action with the body as its parameters and returns the provider's result; an unconnected provider is a 409. | `integrations` |
| `GET /api/v1/integrations/{providerId}/callback` | OAuth2 callback endpoint — public (no API key required). | `integrations` |
| `GET /api/v1/integrations/{providerId}/connect` | Returns the OAuth `authorizationUrl` for the popup and the `callbackOrigin` its result comes from. | `integrations` |
| `DELETE /api/v1/integrations/{providerId}/connection` | Disconnects: clears the stored tokens and revokes an OAuth token at the provider; client credentials stay (204). | `integrations` |
| `GET /api/v1/integrations/{providerId}/connections` | Every stored connection of a provider, `default` first. | `integrations` |
| `POST /api/v1/integrations/{providerId}/credentials` | Stores `clientId` and `clientSecret` encrypted for the connection, creating it if new. | `integrations` |
| `DELETE /api/v1/integrations/{providerId}/credentials` | Deletes the whole connection including its client credentials and revokes an OAuth token at the provider (204). | `integrations` |
| `GET /api/v1/integrations/{providerId}/task-targets` | Lists the targets a TODO can be exported to (e.g. boards or lists); empty if the provider cannot export or the call fails. | `integrations` |

## /api/v1/learning

Source: `LearningController` – Run statistics: dashboard figures, platform counts, per-workflow and per-run analytics, and run ratings.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/learning/dashboard` | Returns the dashboard figures for the last `days` days: runs, success rate, cost, tokens, time series and per-model stats. | core |
| `GET /api/v1/learning/platform-stats` | Returns platform-wide counts: projects, workflows, skills, templates, schedules, webhooks, chains, triggers, TODOs and wishes. | core |
| `GET /api/v1/learning/runs/{runId}/insights` | Returns cost and token sums of one run plus the figures per step. | `learning` |
| `POST /api/v1/learning/runs/{runId}/rate` | Stores a user rating for a run; the query parameter `rating` must be between 1 and 5. | core |
| `GET /api/v1/learning/workflows/{workflowId}/agentic-analytics` | Summarizes the recent agentic steps of a workflow: how often the goal was reached and the average iterations. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/agentic-history` | Lists the most recent agentic steps of a workflow with goal, iterations, outcome and the sequence of actions taken. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/execution-insights` | Returns cost and token sums over all runs of a workflow, plus the models used. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/similar` | Lists other workflows with a similar set of block types, most similar first. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/stats` | Returns run statistics of one workflow: counts per outcome, success rate, durations and average rating. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/step-analytics` | Returns statistics per step index of a workflow across all its runs. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/suggestions` | Returns optimization hints derived from the workflow's runs, e.g. low success rate, failing or slow steps, high cost. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/tool-patterns` | Returns which tools the workflow's AI steps used, how often, and recurring tool sequences. | `learning` |

## /api/v1/notifications

Source: `NotificationController` – Notification configs that send a webhook or desktop message when a workflow run finishes.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/notifications` | Lists all notification configs. | core |
| `POST /api/v1/notifications` | Creates a notification config (201); `name` and `channelType` (`WEBHOOK` or `DESKTOP`) are required. | core |
| `GET /api/v1/notifications/{id}` | Returns one notification config. | core |
| `DELETE /api/v1/notifications/{id}` | Deletes the notification config (204). | core |
| `POST /api/v1/notifications/{id}/disable` | Switches the notification config off. | core |
| `POST /api/v1/notifications/{id}/enable` | Switches the notification config on. | core |

## /api/v1/onboarding-profiles

Source: `OnboardingProfileController` – Onboarding profiles: the built-in and custom texts a project's onboarding file for AI agents is assembled from.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/onboarding-profiles` | Lists all onboarding profiles in their sort order. | `onboarding-profiles` |
| `POST /api/v1/onboarding-profiles` | Creates a custom profile (201); `key` (lowercase letters, digits, dashes), `name`, `content` and `type` are required. | `onboarding-profiles` |
| `GET /api/v1/onboarding-profiles/{id}` | Returns one onboarding profile. | `onboarding-profiles` |
| `PUT /api/v1/onboarding-profiles/{id}` | Updates name, description or content of a profile; only the languages sent in `content` are replaced. | `onboarding-profiles` |
| `DELETE /api/v1/onboarding-profiles/{id}` | Deletes a custom profile and its history (204); built-in profiles cannot be deleted (400). | `onboarding-profiles` |
| `GET /api/v1/onboarding-profiles/{id}/history` | Lists the earlier versions of a profile's content, newest first. | `onboarding-profiles` |
| `POST /api/v1/onboarding-profiles/{id}/reset` | Sets a built-in profile back to the current shipped text; the previous text goes into the history. | `onboarding-profiles` |
| `GET /api/v1/onboarding-profiles/{id}/shipped` | The current shipped text of a built-in profile, to compare with an edited one before taking it over. | `onboarding-profiles` |

## /api/v1/pool

Source: `WorkflowPoolController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/pool` |  | core |
| `POST /api/v1/pool/export/{workflowId}` |  | core |
| `GET /api/v1/pool/{id}` |  | core |
| `POST /api/v1/pool/{id}/install` |  | core |

## /api/v1/projects

Sources:

- `ProjectBundleController` – A whole project as one file: export, preview of an import, import.
- `ProjectController` – Projects: create, edit, archive and restore them, start or stop their container, and manage their onboarding file.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/projects` | Lists all projects that are not archived. | core |
| `POST /api/v1/projects` | Creates a project (201); `name`, `type` and `path` are required. | core |
| `GET /api/v1/projects/archived` | Lists the archived projects. | core |
| `POST /api/v1/projects/example` | The bundled example project for a fresh installation: created on the first call (201), returned on every further call (200) — with any example workflow it lacks added. | core |
| `POST /api/v1/projects/import` | Imports a bundle into a new or existing project in one transaction (201); schedules arrive disabled. | core |
| `POST /api/v1/projects/import/preview` | Validates a bundle and shows what an import would create, including its shell commands; writes nothing. | core |
| `GET /api/v1/projects/{id}` | Returns one project; an archived project is a 404. | core |
| `PUT /api/v1/projects/{id}` | Updates the fields sent and keeps the others; a new `path` is checked like on create. | core |
| `DELETE /api/v1/projects/{id}` | Archives the project (204) and stops its container if it was running; its workflows stay. | core |
| `GET /api/v1/projects/{id}/export` | Downloads the project as a bundle file: master data, workflows, chains and schedules, with local paths as tokens. | core |
| `POST /api/v1/projects/{id}/generate-context` | Generates an AI context text from the project directory with the default AI provider and stores it as `aiContext`. | core |
| `POST /api/v1/projects/{id}/onboarding/disable` | Switches onboarding off and deletes the onboarding file if MySuricates wrote it. | `onboarding-profiles` |
| `POST /api/v1/projects/{id}/onboarding/enable` | Switches onboarding on and writes the onboarding file from the chosen profiles into the project directory. | `onboarding-profiles` |
| `GET /api/v1/projects/{id}/onboarding/preview` | Returns the onboarding file text as it would be written, as `{"content": …}`, without writing it. | `onboarding-profiles` |
| `POST /api/v1/projects/{id}/onboarding/refresh` | Writes the onboarding file again from the current profiles; does nothing while onboarding is off. | `onboarding-profiles` |
| `GET /api/v1/projects/{id}/onboarding/status` | Returns the onboarding state: on or off, format, profiles, language, file path and whether the file exists or is foreign. | `onboarding-profiles` |
| `POST /api/v1/projects/{id}/restore` | Restores an archived project. | core |
| `POST /api/v1/projects/{id}/start` | Creates and starts the project's Docker container and returns the project with status `RUNNING`, or `ERROR` if it failed. | core |
| `POST /api/v1/projects/{id}/stop` | Stops the project's Docker container and returns the project with status `STOPPED`. | core |
| `GET /api/v1/projects/{id}/workflows` | Lists the workflows of a project with their run counts. | core |

## /api/v1/reports

Source: `ReportsController` – Lists and reads the Markdown reports in the configured reports directory (`automation.reports.directory`).

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/reports` | Lists the `.md` files of the reports directory as `name` and `label`, newest date in the name first. | `audit` |
| `GET /api/v1/reports/{name}` | Returns the content of one report as `content`. | `audit` |

## /api/v1/schedules

Source: `ScheduleController` – Time-based starts of workflows and chains: cron, one-off and delayed schedules, calendar and the scheduler pauses.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/schedules` | Lists all schedules. | core |
| `POST /api/v1/schedules` | Creates a schedule for a workflow (`workflowId`) or a chain (`targetType: CHAIN`, `chainId`) and answers 201. | core |
| `GET /api/v1/schedules/calendar` | Returns the calendar data between `from` and `to`: upcoming schedules and past runs. | core |
| `GET /api/v1/schedules/catch-up` | Sequential catch-up queue: the missed occurrence currently running and the ones waiting. | core |
| `GET /api/v1/schedules/chain/{chainId}` | Lists the schedules of one chain. | core |
| `GET /api/v1/schedules/maintenance` | Both pauses: the maintenance switch and an active skip-until window from the configuration. | core |
| `POST /api/v1/schedules/maintenance` | Switches the scheduler maintenance mode on or off with `enabled`; while it is on, no schedule starts. | core |
| `POST /api/v1/schedules/preview` | Previews the next run times of the cron expression in `cronExpression`. | core |
| `GET /api/v1/schedules/remaining-this-week` | Lists the runs still due this week; `projectId` (optional) filters like `remaining-today`. | core |
| `GET /api/v1/schedules/remaining-today` | Lists the runs still due today. | core |
| `GET /api/v1/schedules/workflow/{workflowId}` | Lists the schedules of one workflow. | core |
| `GET /api/v1/schedules/{id}` | Returns one schedule; unknown id is 404. | core |
| `PUT /api/v1/schedules/{id}` | Updates a schedule; only the fields sent are changed. | core |
| `DELETE /api/v1/schedules/{id}` | Deletes a schedule (204). | core |
| `POST /api/v1/schedules/{id}/disable` | Disables a schedule and clears its next run. | core |
| `POST /api/v1/schedules/{id}/enable` | Enables a schedule and computes its next run. | core |
| `GET /api/v1/schedules/{id}/runs` | Die neuesten Runs eines Zeitplans als Listenzeilen: Anzahl und Vorschau des letzten Schritts statt aller Schritte — die Detail-Route eines Runs liefert den Volltext. | core |

## /api/v1/search

Source: `SearchController` – Aggregates search across workflows (and later runs/skills/integrations).

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/search` | Searches workflows by name, description, tags and category for `q` and returns the best matches. | core |
| `POST /api/v1/search/workflows/semantic` | Ranks workflows against a natural-language `query` with the AI suggestions model, each with a match reason. | core |

## /api/v1/skills

Source: `SkillController` – Skills and their capabilities: list, create, edit, delete, version history and JSON import/export.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/skills` | Lists all skills, built-in and custom, with their capabilities. | `skills` |
| `POST /api/v1/skills` | Creates a custom skill and answers 201; `name` and `category` are required. | `skills` |
| `GET /api/v1/skills/export/{id}` | Exports a skill with its capabilities as JSON in the format the import accepts. | `skills` |
| `POST /api/v1/skills/import` | Imports a skill from its exported JSON as a new custom skill and answers 201; every capability needs a `name`. | `skills` |
| `GET /api/v1/skills/seal-pins` | skill id → names of the sealed workflows that use it; such skills cannot be changed or deleted. | `skills` |
| `GET /api/v1/skills/{id}` | Returns one skill with its capabilities; unknown id is 404. | `skills` |
| `PUT /api/v1/skills/{id}` | Updates a custom skill. | `skills` |
| `DELETE /api/v1/skills/{id}` | Deletes a custom skill (204). | `skills` |
| `GET /api/v1/skills/{id}/capabilities` | Lists the capabilities of one skill. | `skills` |
| `GET /api/v1/skills/{id}/versions` | Returns the change history of a skill, newest first, also for a skill deleted since; the snapshots are not included. | `skills` |

## /api/v1/staging

Sources:

- `StagedSyncController` – Definition sync of the staged setup: Dev → Test / Prod.
- `StagingController` – The local Stable environment (Docker container): status, start/stop, promote and definition sync from this instance.

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/staging/promote` | Starts a promote in the background (202): stop Stable, build backend, frontend and image, start it again. | `staging` |
| `POST /api/v1/staging/promote/cancel` | Cancels a running promote and kills its current build step. | `staging` |
| `POST /api/v1/staging/start` | Starts the Stable container and waits until it is healthy. | `staging` |
| `GET /api/v1/staging/status` | Returns whether the Stable container runs and is healthy, the promote progress and the Stable version. | `staging` |
| `POST /api/v1/staging/stop` | Stops the Stable container. | `staging` |
| `POST /api/v1/staging/sync-definitions` | Transfers projects, workflows, skills and chains from this instance to Stable; an optional body narrows the selection by ids. | `staging` |
| `GET /api/v1/staging/sync-preview` | Previews which projects, workflows, skills and chains a sync to Stable would transfer; 409 while Stable is not healthy. | `staging` |
| `GET /api/v1/staging/targets` | Lists the configured sync targets (`test`, `prod`) with URL, key presence and reachability. | `staging` |
| `POST /api/v1/staging/targets/{target}/sync-definitions` | Transfers projects, workflows, skills and chains to `test` or `prod`; an optional body narrows the selection by ids. | `staging` |
| `GET /api/v1/staging/targets/{target}/sync-preview` | Previews what a sync from this instance to `test` or `prod` would transfer; for `prod` only sealed definitions. | `staging` |

## /api/v1/system

Sources:

- `SystemAccessController` – Zugriffs-Oberfläche unter /api/v1/system: Remote Access (Tailscale) und lokales Dateisystem.
- `SystemAiProviderController` – AI-Provider- und Capability-Auskunft unter /api/v1/system.
- `SystemController` – Kern-Auskunft unter /api/v1/system: Health, Plattform-Info, Auth-Status, Heap-Belegung und Client-Fehler-Meldungen.
- `SystemMaintenanceController` – Wartung unter /api/v1/system: Auto-Repair, VACUUM, Datenbank-Backups.
- `SystemModulesController` – Runtime module switches for the settings UI.
- `SystemRequestMetricsController` – Request costs per route: the live window from memory and the stored daily history.
- `SystemSettingsController` – Laufzeit-Settings unter /api/v1/system: App-Settings und Execution-Config.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/system/ai-providers` | Lists the registered AI providers, the default provider and each provider's declared capabilities. | core |
| `GET /api/v1/system/ai-providers/PI/catalog` | Returns the pi runtime catalog read from `~/.pi/agent`: which backend providers are authenticated, which are custom-defined in models.json, and what pi's own default-provider/-model is set to. | core |
| `GET /api/v1/system/ai-providers/status` | Per provider: program on the PATH, login found (file or environment variable), and the commands for what is missing — for the start page and the settings. | core |
| `POST /api/v1/system/ai-providers/status/refresh` | Looks at the machine again now, e.g. right after logging in. | core |
| `GET /api/v1/system/ai-providers/{id}/models` | Models the provider offers on this host, discovered from the CLI / its local caches and served from a TTL cache. | core |
| `POST /api/v1/system/ai-providers/{id}/models/refresh` | Bypasses the cache and probes the host now. | core |
| `GET /api/v1/system/auth-status` | Tells whether API-key security is switched on (`securityEnabled`); reachable without a key. | core |
| `GET /api/v1/system/backup` | Creates a backup of the database now and returns it as a file download. | core |
| `POST /api/v1/system/backup` | Creates a backup of the database and returns its file name, size and time. | core |
| `GET /api/v1/system/backups` | Lists the backup files with name, size and creation time, newest first. | core |
| `GET /api/v1/system/backups/{filename}` | Downloads one backup file; an unknown name or one with a path in it is 404. | core |
| `GET /api/v1/system/browse` | Lists the subdirectories of `path` (default: the home directory) without hidden ones, plus the parent path. | core |
| `GET /api/v1/system/capabilities` | Describes what this instance offers: API and app version, all API routes, the block types usable now and the module state. | core |
| `POST /api/v1/system/client-errors` | Accepts an error report from the browser client and writes it to the backend log (202). | core |
| `GET /api/v1/system/execution-config` | Returns the current execution settings (concurrency, timeouts, rate limit and more) plus the read-only `activeRuns`. | core |
| `PUT /api/v1/system/execution-config` | Changes execution settings with immediate effect and returns the full current set. | core |
| `GET /api/v1/system/health` | Health check without a key: `UP`, `DEGRADED` (Docker expected but not answering) or `DOWN` (database unreachable). | core |
| `GET /api/v1/system/info` | Returns name, version, build, Java version, runtime mode, environment and the rate-limit figures; reachable without a key. | core |
| `GET /api/v1/system/integration-snippet` | Renders the base onboarding text as a Markdown snippet to paste into a project, e.g. into an `AGENTS.md`. | `onboarding-profiles` |
| `GET /api/v1/system/jvm-memory` | Heap-Belegung als genau drei Zahlen, ohne API-Key: der Performance-Audit misst damit den Speicher, ohne dass die ganze Metrik-Oberfläche unter `/actuator/metrics` frei wird — die verrät Routen und Lastprofil und bleibt hinter dem Key. | core |
| `PUT /api/v1/system/modules/{name}` | Switches a feature module on or off (`enabled`) with immediate effect and returns the state of all modules. | core |
| `DELETE /api/v1/system/modules/{name}/override` | Drops the settings override of a module so it follows the startup configuration again; returns the state of all modules. | core |
| `POST /api/v1/system/open-path` | Opens a file or directory (`path`) in the desktop's file manager on the host. | core |
| `POST /api/v1/system/remote-access/disable` | Takes Tailscale down on the host; a failing command or a container instance is 503. | `remote-access` |
| `POST /api/v1/system/remote-access/enable` | Brings Tailscale up on the host and returns the Tailscale IP and hostname. | `remote-access` |
| `GET /api/v1/system/remote-access/status` | Returns whether Tailscale is up, with IP and hostname, and whether this instance runs in a container. | `remote-access` |
| `POST /api/v1/system/repair-failed` | Queues a repair for every workflow with a failed run and answers 202 with the number queued. | `factory` |
| `GET /api/v1/system/request-metrics` | Routes measured since the last daily snapshot, most expensive (p95) first. | core |
| `GET /api/v1/system/request-metrics/daily` | Stored daily rows for one route, oldest first — the baseline a drift check compares against. | core |
| `GET /api/v1/system/settings/{key}` | Returns one application setting; a key that is not allowed answers `400`. | core |
| `PUT /api/v1/system/settings/{key}` | Stores an application setting from `value` in the body. | core |
| `GET /api/v1/system/tray-config` | Returns the settings of the host tray icon: popups, which events notify, and the public URL the browser opens. | core |
| `PUT /api/v1/system/tray-config` | Updates the tray settings; only the fields sent change, and the answer is the full state. | core |
| `POST /api/v1/system/vacuum` | Runs a full SQLite VACUUM and reports its duration; a failed VACUUM is 500. | core |

## /api/v1/testing

Source: `TestingController` – Controls the Test instance container: start, stop, reset, push definitions to it and pull changes back.

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/testing/pull-changes` | Takes instructions and model of the Test instance's workflows into the workflows with the same name and project here. | `staging` |
| `POST /api/v1/testing/report-result` | Records a test result as test status of the workflow with this name and project name. | `staging` |
| `POST /api/v1/testing/reset` | Wipes the Test database volume, starts the container fresh and pushes all definitions to it. | `staging` |
| `POST /api/v1/testing/start` | Starts the Test container with this instance's API key and waits for its health check. | `staging` |
| `GET /api/v1/testing/status` | Reports whether the Test container is running and healthy, plus its version and build timestamp when healthy. | `staging` |
| `POST /api/v1/testing/stop` | Stops the Test container. | `staging` |
| `POST /api/v1/testing/sync-definitions` | Copies definitions from this instance to the Test instance, after a backup there. | `staging` |
| `GET /api/v1/testing/sync-preview` | Lists which projects, workflows, skills and chains a sync would create or update on the Test instance. | `staging` |

## /api/v1/todo-triggers

Source: `TodoTriggerController` – TODO triggers: start a fix workflow automatically when an open TODO matches tags, project, source and priority.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/todo-triggers` | Lists all TODO triggers. | core |
| `POST /api/v1/todo-triggers` | Creates a TODO trigger and returns it with 201; `tagPattern` and `targetWorkflowId` are required. | core |
| `GET /api/v1/todo-triggers/workflow/{workflowId}` | Lists the TODO triggers that start the given workflow. | core |
| `GET /api/v1/todo-triggers/{id}` | Returns one TODO trigger. | core |
| `DELETE /api/v1/todo-triggers/{id}` | Deletes a TODO trigger. | core |
| `POST /api/v1/todo-triggers/{id}/disable` | Disables a TODO trigger; it then ignores new and existing TODOs. | core |
| `POST /api/v1/todo-triggers/{id}/enable` | Enables a TODO trigger. | core |

## /api/v1/todos

Source: `TodoController` – TODOs: list, create, edit and close them, start their fix workflow, export them to a tracker, find duplicates.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/todos` | Lists TODOs page by page, most urgent first, filtered by project, status, priority, source, tag, text or goal. | core |
| `POST /api/v1/todos` | Creates a TODO and returns it with 201; only `title` is required. | core |
| `POST /api/v1/todos/archive-stale` | Archive stale auto-todos now (cockpit "Verstaubtes archivieren"). | core |
| `POST /api/v1/todos/bulk-status` | Bulk status change (e.g. archive selected todos). | core |
| `GET /api/v1/todos/count` | Counts TODOs per status, optionally only those of `projectId`. | core |
| `GET /api/v1/todos/overview` | Cross-project cockpit overview: per-project rollups + a global "Braucht dich" lane. | core |
| `GET /api/v1/todos/similar` | Check for similar TODOs before creating a new one. | core |
| `GET /api/v1/todos/{id}` | Returns one TODO. | core |
| `PUT /api/v1/todos/{id}` | Updates the fields sent in the body and leaves the others unchanged. | core |
| `DELETE /api/v1/todos/{id}` | Deletes a TODO permanently. | core |
| `POST /api/v1/todos/{id}/export` | Creates a task for the TODO in an external tracker and returns its reference; a TODO already exported returns the existing link. | `integrations` |
| `PATCH /api/v1/todos/{id}/planning` | Ziel, Rang und Einschaetzung (Komplexitaet, Effort, Begruendung, offene Frage). | `goals` |
| `POST /api/v1/todos/{id}/resolve` | Starts the TODO's linked fix workflow and sets the TODO to IN_PROGRESS. | core |
| `PATCH /api/v1/todos/{id}/status` | Sets the status of a TODO (`status` required), optionally recording the `sessionId` that changed it. | core |

## /api/v1/webhook

Source: `WebhookController` – Webhooks: manage the tokens that start a workflow from outside, and the public trigger endpoint itself.

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/webhook/trigger/{token}` | Starts the webhook's workflow without API key; the token in the path authorizes the call. | `webhooks` |

## /api/v1/webhooks

Source: `WebhookController` – Webhooks: manage the tokens that start a workflow from outside, and the public trigger endpoint itself.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/webhooks` | Lists all webhooks; tokens and URLs are masked to their first characters. | `webhooks` |
| `POST /api/v1/webhooks` | Creates an enabled webhook for `workflowId` and returns it with 201. | `webhooks` |
| `GET /api/v1/webhooks/workflow/{workflowId}` | Lists the webhooks of a workflow with masked tokens. | `webhooks` |
| `GET /api/v1/webhooks/{id}` | Returns one webhook with masked token. | `webhooks` |
| `DELETE /api/v1/webhooks/{id}` | Deletes a webhook; its token stops working. | `webhooks` |
| `POST /api/v1/webhooks/{id}/disable` | Disables a webhook; calls to its trigger URL then return 409. | `webhooks` |
| `POST /api/v1/webhooks/{id}/enable` | Enables a webhook. | `webhooks` |
| `POST /api/v1/webhooks/{id}/regenerate-token` | Rotates a webhook's secret token. | `webhooks` |
| `POST /api/v1/webhooks/{id}/secret` | Creates or rotates the signing secret; the response is the only place it is ever shown. | `webhook-signatures` |
| `DELETE /api/v1/webhooks/{id}/secret` | Removes the signing secret, so trigger calls no longer need a signature. | `webhook-signatures` |

## /api/v1/wishes

Source: `WishController` – Wishes: a request in plain words that an AI loop decomposes into workflows, runs, evaluates and refines.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/wishes` | Lists all wishes, newest first. | `wishes` |
| `POST /api/v1/wishes` | Creates a wish and starts fulfilling it in the background; returns it with 201. | `wishes` |
| `GET /api/v1/wishes/{id}` | Returns one wish with its status, iterations and generated workflows. | `wishes` |
| `DELETE /api/v1/wishes/{id}` | Deletes a wish; only COMPLETED, FAILED or CANCELLED wishes can be deleted (else 409). | `wishes` |
| `POST /api/v1/wishes/{id}/cancel` | Stops the fulfillment loop and marks a wish that is not finished yet as CANCELLED. | `wishes` |
| `POST /api/v1/wishes/{id}/crystallize` | Saves the first generated workflow of a completed wish as a template and returns `templateId`. | `wishes` |
| `POST /api/v1/wishes/{id}/respond` | Responds to a wish with `action` ACCEPT, REFINE, CANCEL, CLARIFY or RETRY plus optional `feedback`. | `wishes` |

## /api/v1/workflow-templates

Source: `WorkflowTemplateController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/workflow-templates` |  | core |
| `POST /api/v1/workflow-templates` |  | core |
| `POST /api/v1/workflow-templates/from-workflow/{workflowId}` |  | core |
| `GET /api/v1/workflow-templates/{id}` |  | core |
| `PUT /api/v1/workflow-templates/{id}` |  | core |
| `DELETE /api/v1/workflow-templates/{id}` |  | core |
| `POST /api/v1/workflow-templates/{id}/install` | Creates a workflow from the template in a project, plus the blueprints its sub-workflow blocks call, wired to their new ids. | core |

## /api/v1/workflows

Sources:

- `WorkflowAnalyzerController` – Deterministische Baufehler-Prüfung von Workflow-Definitionen — auf Abruf, nie blockierend.
- `WorkflowAssistController` – Hilfen rund um einen bestehenden Workflow: Optimierungsvorschlag, ähnliche Workflows, Reparatur nach fehlgeschlagenen Runs.
- `WorkflowController`
- `WorkflowImportController` – Import externer Prozessformate als NEUER Workflow (Roundtrip zum Export in `WorkflowController`).
- `WorkflowMemoryController`
- `WorkflowSealController` – Seal / unseal a workflow.
- `WorkflowSealStatusController` – Read-only seal status for the UI.
- `WorkflowVersionController` – Versionsgeschichte eines Workflows: auflisten, einzeln abrufen, wiederherstellen.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/workflows` |  | core |
| `POST /api/v1/workflows` |  | core |
| `GET /api/v1/workflows/analyze` | Bestandsprüfung über alle Definitionen in aktiven Projekten. | core |
| `GET /api/v1/workflows/export/{id}` |  | core |
| `POST /api/v1/workflows/import` |  | core |
| `POST /api/v1/workflows/import/bpmn` | Importiert einen zuvor exportierten BPMN-Prozess (mit `mysuricates:blockConfig`) als neuen Workflow im Projekt. | `bpmn` |
| `GET /api/v1/workflows/seal-status` | Every sealed workflow plus every workflow with refused starts in the last seven days. | core |
| `GET /api/v1/workflows/{id}` |  | core |
| `PUT /api/v1/workflows/{id}` |  | core |
| `DELETE /api/v1/workflows/{id}` |  | core |
| `GET /api/v1/workflows/{id}/analyze` | Befunde einer Definition. | core |
| `POST /api/v1/workflows/{id}/execute` |  | core |
| `GET /api/v1/workflows/{id}/export/bpmn` | Exportiert den Workflow als BPMN 2.0 XML (Spec: docs/plans/2026-07-09-bpmn-integration-design.md). | `bpmn` |
| `POST /api/v1/workflows/{id}/optimize` |  | core |
| `POST /api/v1/workflows/{id}/repair` |  | `factory` |
| `GET /api/v1/workflows/{id}/runs` |  | core |
| `GET /api/v1/workflows/{id}/runs/{runId}` |  | core |
| `POST /api/v1/workflows/{id}/seal` |  | `sealing` |
| `GET /api/v1/workflows/{id}/seal-events` | Sealing and unsealing of one workflow, newest first — kept beyond the activity log's retention. | core |
| `GET /api/v1/workflows/{id}/similar` |  | core |
| `POST /api/v1/workflows/{id}/skills` |  | `skills` |
| `DELETE /api/v1/workflows/{id}/skills/{skillId}` |  | `skills` |
| `POST /api/v1/workflows/{id}/unseal` |  | `sealing` |
| `GET /api/v1/workflows/{id}/versions` |  | core |
| `GET /api/v1/workflows/{id}/versions/{versionId}` |  | core |
| `POST /api/v1/workflows/{id}/versions/{versionId}/restore` |  | core |
| `GET /api/v1/workflows/{workflowId}/memory` |  | `memory` |
| `POST /api/v1/workflows/{workflowId}/memory/extract` |  | `memory` |
| `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}` |  | `memory` |
| `DELETE /api/v1/workflows/{workflowId}/memory/{memoryId}` |  | `memory` |
| `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}/activate` |  | `memory` |

## /api/v1/ws-ticket

Source: `WebSocketTicketController` – Hands out short-lived, single-use tickets for the WebSocket handshake.

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/ws-ticket` | Issues a single-use ticket for the WebSocket handshake URL; it expires after `expiresInSeconds`. | core |
