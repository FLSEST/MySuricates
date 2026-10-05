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

Source: `ActivityLogController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/activity-log` |  | core |
| `POST /api/v1/activity-log` |  | core |

## /api/v1/assistant

Sources:

- `AssistantController`
- `PageSuggestionController`

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/assistant/chat` |  | `assistant` |
| `POST /api/v1/assistant/pending-tools/{id}/confirm` | Executes a previously proposed tool after explicit user confirmation. | `assistant` |
| `POST /api/v1/assistant/pending-tools/{id}/reject` |  | `assistant` |
| `GET /api/v1/assistant/sessions` |  | `assistant` |
| `GET /api/v1/assistant/sessions/{id}` |  | `assistant` |
| `DELETE /api/v1/assistant/sessions/{id}` |  | `assistant` |
| `GET /api/v1/assistant/suggestions` |  | `assistant` |
| `POST /api/v1/assistant/suggestions/refresh` |  | `assistant` |

## /api/v1/audit-findings

Source: `AuditController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/audit-findings` |  | `audit` |
| `GET /api/v1/audit-findings/{id}` |  | `audit` |
| `PATCH /api/v1/audit-findings/{id}` |  | `audit` |
| `POST /api/v1/audit-findings/{id}/todo-links` |  | `audit` |
| `DELETE /api/v1/audit-findings/{id}/todo-links/{todoId}` |  | `audit` |
| `POST /api/v1/audit-findings/{id}/todos` |  | `audit` |

## /api/v1/audit-reports

Source: `AuditController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/audit-reports/{id}` |  | `audit` |

## /api/v1/audits

Source: `AuditController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/audits` |  | `audit` |
| `GET /api/v1/audits/{auditKey}` |  | `audit` |
| `GET /api/v1/audits/{auditKey}/reports` |  | `audit` |

## /api/v1/chain-templates

Source: `ChainTemplateController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/chain-templates` |  | `chains` |
| `GET /api/v1/chain-templates/{id}` |  | `chains` |
| `POST /api/v1/chain-templates/{id}/install` |  | `chains` |

## /api/v1/chains

Source: `ChainController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/chains` |  | `chains` |
| `POST /api/v1/chains` |  | `chains` |
| `GET /api/v1/chains/runs` |  | `chains` |
| `POST /api/v1/chains/runs/{runId}/cancel` |  | `chains` |
| `GET /api/v1/chains/{id}` |  | `chains` |
| `PUT /api/v1/chains/{id}` |  | `chains` |
| `DELETE /api/v1/chains/{id}` |  | `chains` |
| `POST /api/v1/chains/{id}/execute` |  | `chains` |
| `GET /api/v1/chains/{id}/runs` |  | `chains` |
| `GET /api/v1/chains/{id}/runs/{runId}` |  | `chains` |

## /api/v1/completion-triggers

Source: `CompletionTriggerController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/completion-triggers` |  | core |
| `POST /api/v1/completion-triggers` |  | core |
| `GET /api/v1/completion-triggers/workflow/{workflowId}/as-source` |  | core |
| `GET /api/v1/completion-triggers/workflow/{workflowId}/as-target` |  | core |
| `GET /api/v1/completion-triggers/{id}` |  | core |
| `DELETE /api/v1/completion-triggers/{id}` |  | core |
| `POST /api/v1/completion-triggers/{id}/disable` |  | core |
| `POST /api/v1/completion-triggers/{id}/enable` |  | core |

## /api/v1/conversions

Source: `ConversionController`

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/conversions/runs/{runId}/create-workflow` |  | `conversion` |
| `GET /api/v1/conversions/runs/{runId}/suggest` |  | `conversion` |

## /api/v1/dashboard

Sources:

- `DashboardLayoutController` – Version history of the dashboard layout.
- `DashboardReportAssistController` – The editor's AI proposal for a `dashboard-report` block.
- `DashboardWorkflowController` – Data of the dashboard's workflow widgets (spec E3).

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/dashboard/layouts` |  | core |
| `POST /api/v1/dashboard/layouts` | The body is validated field by field in DashboardLayoutValidator (unknown fields → 400). | core |
| `GET /api/v1/dashboard/layouts/{id}` |  | core |
| `PATCH /api/v1/dashboard/layouts/{id}` |  | core |
| `POST /api/v1/dashboard/report-assist` | 400 for a missing id, 404 for an unknown workflow, 409 when the setting is off, the provider is missing, there is no usable run, or the answer did not match the contract. | `dashboard-report` |
| `GET /api/v1/dashboard/workflow-results` | Ids outside the allowed shape or more than DashboardWorkflowService.MAX_WORKFLOWS of them → 400. | core |

## /api/v1/debug

Source: `WorkflowDebugController` – Workflow-Debug endpoints — back the /debug "what is currently broken" page.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/debug/failed-workflows` | Workflows whose most recent run failed (FAILED / COMPLETED_WITH_ERRORS), latest first. | `debug` |

## /api/v1/dialog

Source: `DialogController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/dialog/projects` | Gibt alle bekannten Projekt-Pfade zurück (für Filter-Dropdown). | `dialog-import` |
| `GET /api/v1/dialog/sessions` | Listet Sessions mit Metadaten. | `dialog-import` |
| `POST /api/v1/dialog/sessions/analyze` | Analysiert eine Session mit KI und liefert eine Workflow-Suggestion. | `dialog-import` |
| `GET /api/v1/dialog/sessions/count` | Zählt Sessions pro Projekt-Verzeichnis (schnell, kein File-Lesen). | `dialog-import` |
| `POST /api/v1/dialog/sessions/create-workflow` | Erstellt einen Workflow aus einer Suggestion (mit optionalen User-Anpassungen). | `dialog-import` |

## /api/v1/dlq

Source: `DeadLetterController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/dlq` |  | core |
| `GET /api/v1/dlq/{id}` |  | core |
| `DELETE /api/v1/dlq/{id}` |  | core |
| `POST /api/v1/dlq/{id}/retry` |  | core |

## /api/v1/docs

Source: `DocsController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/docs` |  | core |
| `GET /api/v1/docs/{name}` |  | core |

## /api/v1/executions

Source: `ExecutionController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/executions` |  | core |
| `GET /api/v1/executions/pending-input` | Alle Läufe, die gerade auf eine Antwort warten — Approvals und Human-Input, die ältesten zuerst. | core |
| `GET /api/v1/executions/{id}` |  | core |
| `DELETE /api/v1/executions/{id}` | Loescht einen Lauf samt Schritten und den losen Verweisen darauf. | core |
| `GET /api/v1/executions/{id}/approval` |  | core |
| `POST /api/v1/executions/{id}/approve` | Beantwortet die offene Frage des Laufs. | core |
| `POST /api/v1/executions/{id}/cancel` |  | core |
| `POST /api/v1/executions/{id}/command` |  | core |
| `GET /api/v1/executions/{id}/live-log` | Gepufferte Live-Zeilen eines Laufs (nur im Speicher, bis ~10 min nach Laufende) fuer das Replay nach einem Reload. | core |
| `PATCH /api/v1/executions/{id}/notes` |  | core |
| `POST /api/v1/executions/{id}/pause` |  | core |
| `POST /api/v1/executions/{id}/resume` |  | core |
| `POST /api/v1/executions/{id}/resume-from-step` |  | core |
| `POST /api/v1/executions/{id}/retry` |  | core |
| `POST /api/v1/executions/{id}/todos/approve` |  | core |

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
| `POST /api/v1/goals` |  | `goals` |
| `GET /api/v1/goals/{goalId}/workflows` |  | `goals` |
| `POST /api/v1/goals/{goalId}/workflows` |  | `goals` |
| `PATCH /api/v1/goals/{goalId}/workflows/{attachmentId}` |  | `goals` |
| `DELETE /api/v1/goals/{goalId}/workflows/{attachmentId}` | Loest die Zuordnung, der Workflow bleibt; `guard` → 400 (der Waechter hat seinen Schalter). | `goals` |
| `GET /api/v1/goals/{id}` |  | `goals` |
| `PUT /api/v1/goals/{id}` |  | `goals` |
| `DELETE /api/v1/goals/{id}` | Loescht das Ziel; seine TODOs bleiben und verlieren nur die Zuordnung, ein Waechter pausiert. | `goals` |
| `GET /api/v1/goals/{id}/candidates` | „Könnte dazugehören“: offene TODOs ohne Ziel, standardmaessig nur aus den Projekten des Ziels (`allProjects=true`: aus allen). | `goals` |
| `POST /api/v1/goals/{id}/guard` | Ziel-Waechter ein- oder ausschalten: beim ersten Einschalten Workflow aus der Vorlage `goal-guard` plus taeglicher Zeitplan, danach nur noch Zeitplan an/aus. | `goals` |
| `GET /api/v1/goals/{id}/milestones` | Meilensteine des Ziels nach Datum, dann Titel, mit Zustand und Paket-Zaehlern. | `goals` |
| `POST /api/v1/goals/{id}/milestones` |  | `goals` |
| `PUT /api/v1/goals/{id}/milestones/{milestoneId}` | `workPackageIds` ersetzt die Verknuepfungen; `reached` nur ohne verknuepfte Pakete. | `goals` |
| `DELETE /api/v1/goals/{id}/milestones/{milestoneId}` | Loescht den Meilenstein; die verknuepften Arbeitspakete bleiben. | `goals` |
| `PUT /api/v1/goals/{id}/order` | Reihenfolge: die genannten TODOs bekommen Rang 1..n, alle uebrigen des Ziels keinen. | `goals` |
| `POST /api/v1/goals/{id}/projects` | Projekt ans Ende der Zielprojekte haengen; schon zugeordnet = unveraendert (idempotent). | `goals` |
| `PUT /api/v1/goals/{id}/projects/order` | Zielprojekte neu ordnen: die genannten vorn, die uebrigen in bisheriger Folge dahinter. | `goals` |
| `DELETE /api/v1/goals/{id}/projects/{projectId}` | Projekt vom Ziel loesen. | `goals` |
| `GET /api/v1/goals/{id}/relations` | Relationskarte: je TODO Herkunft, Bearbeitung (Fix-Workflow, passende TodoTrigger) und Ergebnis. | `goals` |
| `POST /api/v1/goals/{id}/todos` | Ordnet TODOs dem Ziel zu (ohne Rang – sie stehen hinter den eingeordneten). | `goals` |
| `GET /api/v1/goals/{id}/work-packages` | Arbeitspakete des Ziels nach Projekt-Position und Position im Projekt, mit Zustand (OPEN/DUE_SOON/OVERDUE/REACHED auf Basis des Endes; ohne Zeitraum nur OPEN/REACHED) und Zaehlern. | `goals` |
| `POST /api/v1/goals/{id}/work-packages` |  | `goals` |
| `PUT /api/v1/goals/{id}/work-packages/order` | Arbeitspakete EINES Projekts neu ordnen; Antwort: alle Arbeitspakete des Ziels. | `goals` |
| `PUT /api/v1/goals/{id}/work-packages/{workPackageId}` |  | `goals` |
| `DELETE /api/v1/goals/{id}/work-packages/{workPackageId}` | Loescht das Arbeitspaket; seine TODOs bleiben im Ziel, Meilensteine verlieren die Verknuepfung. | `goals` |

## /api/v1/integrations

Source: `IntegrationController` – Integration providers and their connections.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/integrations` |  | `integrations` |
| `GET /api/v1/integrations/{providerId}` |  | `integrations` |
| `GET /api/v1/integrations/{providerId}/actions` |  | `integrations` |
| `POST /api/v1/integrations/{providerId}/actions/{action}` |  | `integrations` |
| `GET /api/v1/integrations/{providerId}/callback` | OAuth2 callback endpoint — public (no API key required). | `integrations` |
| `GET /api/v1/integrations/{providerId}/connect` |  | `integrations` |
| `DELETE /api/v1/integrations/{providerId}/connection` |  | `integrations` |
| `GET /api/v1/integrations/{providerId}/connections` | Every stored connection of a provider, `default` first. | `integrations` |
| `POST /api/v1/integrations/{providerId}/credentials` |  | `integrations` |
| `DELETE /api/v1/integrations/{providerId}/credentials` |  | `integrations` |
| `GET /api/v1/integrations/{providerId}/task-targets` |  | `integrations` |

## /api/v1/learning

Source: `LearningController` – Dashboard, platform stats and run rating are core; analytics belong to the learning module.

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/learning/dashboard` |  | core |
| `GET /api/v1/learning/platform-stats` |  | core |
| `GET /api/v1/learning/runs/{runId}/insights` |  | `learning` |
| `POST /api/v1/learning/runs/{runId}/rate` |  | core |
| `GET /api/v1/learning/workflows/{workflowId}/agentic-analytics` |  | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/agentic-history` |  | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/execution-insights` |  | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/similar` |  | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/stats` |  | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/step-analytics` |  | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/suggestions` |  | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/tool-patterns` |  | `learning` |

## /api/v1/notifications

Source: `NotificationController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/notifications` |  | core |
| `POST /api/v1/notifications` |  | core |
| `GET /api/v1/notifications/{id}` |  | core |
| `DELETE /api/v1/notifications/{id}` |  | core |
| `POST /api/v1/notifications/{id}/disable` |  | core |
| `POST /api/v1/notifications/{id}/enable` |  | core |

## /api/v1/onboarding-profiles

Source: `OnboardingProfileController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/onboarding-profiles` |  | `onboarding-profiles` |
| `POST /api/v1/onboarding-profiles` |  | `onboarding-profiles` |
| `GET /api/v1/onboarding-profiles/{id}` |  | `onboarding-profiles` |
| `PUT /api/v1/onboarding-profiles/{id}` |  | `onboarding-profiles` |
| `DELETE /api/v1/onboarding-profiles/{id}` |  | `onboarding-profiles` |
| `GET /api/v1/onboarding-profiles/{id}/history` |  | `onboarding-profiles` |
| `POST /api/v1/onboarding-profiles/{id}/reset` |  | `onboarding-profiles` |
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
- `ProjectController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/projects` |  | core |
| `POST /api/v1/projects` |  | core |
| `GET /api/v1/projects/archived` |  | core |
| `POST /api/v1/projects/example` | The bundled example project for a fresh installation: created on the first call (201), returned on every further call (200) — with any example workflow it lacks added. | core |
| `POST /api/v1/projects/import` |  | core |
| `POST /api/v1/projects/import/preview` |  | core |
| `GET /api/v1/projects/{id}` |  | core |
| `PUT /api/v1/projects/{id}` |  | core |
| `DELETE /api/v1/projects/{id}` |  | core |
| `GET /api/v1/projects/{id}/export` |  | core |
| `POST /api/v1/projects/{id}/generate-context` |  | core |
| `POST /api/v1/projects/{id}/onboarding/disable` |  | `onboarding-profiles` |
| `POST /api/v1/projects/{id}/onboarding/enable` |  | `onboarding-profiles` |
| `GET /api/v1/projects/{id}/onboarding/preview` |  | `onboarding-profiles` |
| `POST /api/v1/projects/{id}/onboarding/refresh` |  | `onboarding-profiles` |
| `GET /api/v1/projects/{id}/onboarding/status` |  | `onboarding-profiles` |
| `POST /api/v1/projects/{id}/restore` |  | core |
| `POST /api/v1/projects/{id}/start` |  | core |
| `POST /api/v1/projects/{id}/stop` |  | core |
| `GET /api/v1/projects/{id}/workflows` |  | core |

## /api/v1/reports

Source: `ReportsController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/reports` |  | `audit` |
| `GET /api/v1/reports/{name}` |  | `audit` |

## /api/v1/schedules

Source: `ScheduleController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/schedules` |  | core |
| `POST /api/v1/schedules` |  | core |
| `GET /api/v1/schedules/calendar` | Upcoming = schedules whose next real run lies in the period (behind a skip-until pause). | core |
| `GET /api/v1/schedules/catch-up` | Sequential catch-up queue: the missed occurrence currently running and the ones waiting. | core |
| `GET /api/v1/schedules/chain/{chainId}` |  | core |
| `GET /api/v1/schedules/maintenance` | Both pauses: the maintenance switch and an active skip-until window from the configuration. | core |
| `POST /api/v1/schedules/maintenance` |  | core |
| `POST /api/v1/schedules/preview` |  | core |
| `GET /api/v1/schedules/remaining-this-week` |  | core |
| `GET /api/v1/schedules/remaining-today` | `projectId` (optional): only the schedules of that project's workflows — the dashboard filter. | core |
| `GET /api/v1/schedules/workflow/{workflowId}` |  | core |
| `GET /api/v1/schedules/{id}` |  | core |
| `PUT /api/v1/schedules/{id}` |  | core |
| `DELETE /api/v1/schedules/{id}` |  | core |
| `POST /api/v1/schedules/{id}/disable` |  | core |
| `POST /api/v1/schedules/{id}/enable` |  | core |
| `GET /api/v1/schedules/{id}/runs` | Die neuesten Runs eines Zeitplans als Listenzeilen: Anzahl und Vorschau des letzten Schritts statt aller Schritte — die Detail-Route eines Runs liefert den Volltext. | core |

## /api/v1/search

Source: `SearchController` – Aggregates search across workflows (and later runs/skills/integrations).

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/search` |  | core |
| `POST /api/v1/search/workflows/semantic` |  | core |

## /api/v1/skills

Source: `SkillController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/skills` |  | `skills` |
| `POST /api/v1/skills` |  | `skills` |
| `GET /api/v1/skills/export/{id}` |  | `skills` |
| `POST /api/v1/skills/import` |  | `skills` |
| `GET /api/v1/skills/seal-pins` | skill id → names of the sealed workflows that use it; such skills cannot be changed or deleted. | `skills` |
| `GET /api/v1/skills/{id}` |  | `skills` |
| `PUT /api/v1/skills/{id}` | Custom skills only (built-in → 409); a skill used by a sealed workflow → 409 SKILL_PINNED_BY_SEAL. | `skills` |
| `DELETE /api/v1/skills/{id}` |  | `skills` |
| `GET /api/v1/skills/{id}/capabilities` |  | `skills` |
| `GET /api/v1/skills/{id}/versions` |  | `skills` |

## /api/v1/staging

Sources:

- `StagedSyncController` – Definition sync of the staged setup: Dev → Test / Prod.
- `StagingController`

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/staging/promote` |  | `staging` |
| `POST /api/v1/staging/promote/cancel` |  | `staging` |
| `POST /api/v1/staging/start` |  | `staging` |
| `GET /api/v1/staging/status` |  | `staging` |
| `POST /api/v1/staging/stop` |  | `staging` |
| `POST /api/v1/staging/sync-definitions` |  | `staging` |
| `GET /api/v1/staging/sync-preview` |  | `staging` |
| `GET /api/v1/staging/targets` |  | `staging` |
| `POST /api/v1/staging/targets/{target}/sync-definitions` |  | `staging` |
| `GET /api/v1/staging/targets/{target}/sync-preview` |  | `staging` |

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
| `GET /api/v1/system/ai-providers` |  | core |
| `GET /api/v1/system/ai-providers/PI/catalog` | Returns the pi runtime catalog read from `~/.pi/agent`: which backend providers are authenticated, which are custom-defined in models.json, and what pi's own default-provider/-model is set to. | core |
| `GET /api/v1/system/ai-providers/status` | Per provider: program on the PATH, login found (file or environment variable), and the commands for what is missing — for the start page and the settings. | core |
| `POST /api/v1/system/ai-providers/status/refresh` | Looks at the machine again now, e.g. right after logging in. | core |
| `GET /api/v1/system/ai-providers/{id}/models` | Models the provider offers on this host, discovered from the CLI / its local caches and served from a TTL cache. | core |
| `POST /api/v1/system/ai-providers/{id}/models/refresh` | Bypasses the cache and probes the host now. | core |
| `GET /api/v1/system/auth-status` |  | core |
| `GET /api/v1/system/backup` |  | core |
| `POST /api/v1/system/backup` |  | core |
| `GET /api/v1/system/backups` |  | core |
| `GET /api/v1/system/backups/{filename}` |  | core |
| `GET /api/v1/system/browse` |  | core |
| `GET /api/v1/system/capabilities` |  | core |
| `POST /api/v1/system/client-errors` |  | core |
| `GET /api/v1/system/execution-config` |  | core |
| `PUT /api/v1/system/execution-config` |  | core |
| `GET /api/v1/system/health` |  | core |
| `GET /api/v1/system/info` |  | core |
| `GET /api/v1/system/integration-snippet` |  | `onboarding-profiles` |
| `GET /api/v1/system/jvm-memory` | Heap-Belegung als genau drei Zahlen, ohne API-Key: der Performance-Audit misst damit den Speicher, ohne dass die ganze Metrik-Oberfläche unter `/actuator/metrics` frei wird — die verrät Routen und Lastprofil und bleibt hinter dem Key. | core |
| `PUT /api/v1/system/modules/{name}` |  | core |
| `DELETE /api/v1/system/modules/{name}/override` |  | core |
| `POST /api/v1/system/open-path` |  | core |
| `POST /api/v1/system/remote-access/disable` |  | `remote-access` |
| `POST /api/v1/system/remote-access/enable` |  | `remote-access` |
| `GET /api/v1/system/remote-access/status` |  | `remote-access` |
| `POST /api/v1/system/repair-failed` |  | `factory` |
| `GET /api/v1/system/request-metrics` | Routes measured since the last daily snapshot, most expensive (p95) first. | core |
| `GET /api/v1/system/request-metrics/daily` | Stored daily rows for one route, oldest first — the baseline a drift check compares against. | core |
| `GET /api/v1/system/settings/{key}` | `value` is the stored value or empty; `default`, only for a setting whose default comes from the startup configuration (`backup-retain-count`), is what applies while nothing is stored. | core |
| `PUT /api/v1/system/settings/{key}` |  | core |
| `GET /api/v1/system/tray-config` |  | core |
| `PUT /api/v1/system/tray-config` |  | core |
| `POST /api/v1/system/vacuum` |  | core |

## /api/v1/testing

Source: `TestingController`

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/testing/pull-changes` |  | `staging` |
| `POST /api/v1/testing/report-result` |  | `staging` |
| `POST /api/v1/testing/reset` |  | `staging` |
| `POST /api/v1/testing/start` |  | `staging` |
| `GET /api/v1/testing/status` |  | `staging` |
| `POST /api/v1/testing/stop` |  | `staging` |
| `POST /api/v1/testing/sync-definitions` |  | `staging` |
| `GET /api/v1/testing/sync-preview` |  | `staging` |

## /api/v1/todo-triggers

Source: `TodoTriggerController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/todo-triggers` |  | core |
| `POST /api/v1/todo-triggers` |  | core |
| `GET /api/v1/todo-triggers/workflow/{workflowId}` |  | core |
| `GET /api/v1/todo-triggers/{id}` |  | core |
| `DELETE /api/v1/todo-triggers/{id}` |  | core |
| `POST /api/v1/todo-triggers/{id}/disable` |  | core |
| `POST /api/v1/todo-triggers/{id}/enable` |  | core |

## /api/v1/todos

Source: `TodoController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/todos` |  | core |
| `POST /api/v1/todos` |  | core |
| `POST /api/v1/todos/archive-stale` | Archive stale auto-todos now (cockpit "Verstaubtes archivieren"). | core |
| `POST /api/v1/todos/bulk-status` | Bulk status change (e.g. archive selected todos). | core |
| `GET /api/v1/todos/count` |  | core |
| `GET /api/v1/todos/overview` | Cross-project cockpit overview: per-project rollups + a global "Braucht dich" lane. | core |
| `GET /api/v1/todos/similar` | Check for similar TODOs before creating a new one. | core |
| `GET /api/v1/todos/{id}` |  | core |
| `PUT /api/v1/todos/{id}` |  | core |
| `DELETE /api/v1/todos/{id}` |  | core |
| `POST /api/v1/todos/{id}/export` |  | `integrations` |
| `PATCH /api/v1/todos/{id}/planning` | Ziel, Rang und Einschaetzung (Komplexitaet, Effort, Begruendung, offene Frage). | `goals` |
| `POST /api/v1/todos/{id}/resolve` |  | core |
| `PATCH /api/v1/todos/{id}/status` |  | core |

## /api/v1/webhook

Source: `WebhookController`

| Endpoint | Purpose | Module |
|---|---|---|
| `POST /api/v1/webhook/trigger/{token}` |  | `webhooks` |

## /api/v1/webhooks

Source: `WebhookController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/webhooks` |  | `webhooks` |
| `POST /api/v1/webhooks` |  | `webhooks` |
| `GET /api/v1/webhooks/workflow/{workflowId}` |  | `webhooks` |
| `GET /api/v1/webhooks/{id}` |  | `webhooks` |
| `DELETE /api/v1/webhooks/{id}` |  | `webhooks` |
| `POST /api/v1/webhooks/{id}/disable` |  | `webhooks` |
| `POST /api/v1/webhooks/{id}/enable` |  | `webhooks` |
| `POST /api/v1/webhooks/{id}/regenerate-token` | Rotates a webhook's secret token. | `webhooks` |
| `POST /api/v1/webhooks/{id}/secret` | Creates or rotates the signing secret; the response is the only place it is ever shown. | `webhook-signatures` |
| `DELETE /api/v1/webhooks/{id}/secret` |  | `webhook-signatures` |

## /api/v1/wishes

Source: `WishController`

| Endpoint | Purpose | Module |
|---|---|---|
| `GET /api/v1/wishes` |  | `wishes` |
| `POST /api/v1/wishes` |  | `wishes` |
| `GET /api/v1/wishes/{id}` |  | `wishes` |
| `DELETE /api/v1/wishes/{id}` |  | `wishes` |
| `POST /api/v1/wishes/{id}/cancel` |  | `wishes` |
| `POST /api/v1/wishes/{id}/crystallize` |  | `wishes` |
| `POST /api/v1/wishes/{id}/respond` |  | `wishes` |

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
| `POST /api/v1/ws-ticket` |  | core |
