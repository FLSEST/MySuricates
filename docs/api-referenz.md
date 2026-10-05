<!-- Erzeugt von scripts/docs-reference.py – nicht von Hand ändern -->

# API-Referenz

> **Scope:** Die REST-API von MySuricates, ab „Fehlercodes“ erzeugt aus dem Code
> (`python3 scripts/docs-reference.py --write`, siehe [Entwicklung → Erzeugte Referenzen](entwicklung.md#erzeugte-referenzen)).
> Die kuratierte Kurzreferenz für externe Agents mit Beispielen ist [AGENTS.md](../AGENTS.md).

Je Bereich (erstes Pfadsegment nach `/api/v1`) eine Tabelle: Methode und Pfad, als Zweck der erste Satz
des Javadoc der Handler-Methode – ohne Javadoc bleibt die Zelle leer – und das Modul aus `@RequiresModule`
(„Kern“ = immer an). Request- und Response-Felder stehen in den DTOs unter
`ai-automation-backend/src/main/java/com/automation/dto/`, das Verhalten im Controller, den die Zeile
„Quelle“ nennt.

## Allgemein

- **Basis-URL:** `http://localhost:9090/api/v1`, Content-Type `application/json`
- **Authentifizierung:** Header `X-API-Key: <key>` (wenn `automation.security.enabled=true`)
- **Proxy:** Aufrufe mit API-Key per `curl` immer mit `--noproxy '*'` – bei gesetztem `http_proxy`
  schickt curl auch Anfragen an `localhost` über den Proxy, samt Key. Das Doku-Gate
  `doc-facts-check.sh` prüft das an jedem Beispiel in `docs/` und `AGENTS.md`.
- **Module:** Ist das Modul eines Endpunkts ausgeschaltet, antwortet er mit `404` und Code `MODULE_DISABLED`.
- **Paginierung:** Seitenweise Listen nehmen `page` (ab 0) und `size` und liefern ein Seitenobjekt mit
  `content` und `totalElements`.

### Fehlerformat

```json
{ "code": "INTERNAL_ERROR", "error": "Fehlermeldung", "timestamp": "2025-01-15T10:30:00Z" }
```

`details` kommt nur bei Bedarf mit (`@JsonInclude(NON_NULL)`), Validierungsfehler (`VALIDATION_FAILED`)
zusätzlich mit `fieldErrors` (Feld → Meldung).

`params` (Objekt aus Zeichenketten) kommt nur mit, wenn Code und Parameter die Meldung vollständig
beschreiben – etwa `{"code":"WORKFLOW_SEALED","params":{"name":"Nightly"}}`, auch leer (`{}`) bei festem
Text. Die Oberfläche zeigt dann ihre eigene Übersetzung des Codes; `error` bleibt der englische Text für
Logs und API-Clients. Ohne `params` ist `error` Freitext der Wurfstelle und wird so angezeigt.

Wo ein Code für viele Meldungen steht (`INVALID_ARGUMENT`, `STATE_CONFLICT`, …), kommt zusätzlich
`messageKey` mit – ein Schlüssel des Backend-Enums `ErrorMessage`, etwa
`{"code":"INVALID_ARGUMENT","messageKey":"INVALID_CRON","params":{"expression":"…"}}`. Die Oberfläche
übersetzt dann den Schlüssel statt des Codes.

## WebSocket

STOMP über SockJS auf `ws://localhost:9090/ws`. Der Handshake braucht ein kurzlebiges Einmal-Ticket aus
`POST /api/v1/ws-ticket` (API-Key im Header, Antwort `{ "ticket": "kxJ9…", "expiresInSeconds": 30 }`) –
genau einmal einlösbar, für jeden Verbindungsversuch (auch jeden Reconnect) ein frisches. Ein
`apiKey`-Query-Parameter wird abgelehnt.

| Topic | Inhalt |
|---|---|
| `/topic/executions/{runId}/logs` | Workflow-Ausführungs-Logs (Steps, Status, Approval-Prompts) |
| `/topic/chains/{chainRunId}/status` | Chain-Ausführungs-Status-Updates |
| `/topic/assistant/{requestId}/status` | Assistent-Chat-Fortschritt (Phase, Detail, Elapsed) |
| `/topic/assistant/{sessionId}/events` | Assistent-Push-Events (erstellte Items, Suggestions) |
| `/topic/wishes/{wishId}/status` | Wish-Iterations-Status |
| `/topic/activity-log` | Globale Activity-Log-Events |
| `/topic/dashboard/runs` | Nach dem Speichern eines beendeten Runs `{"type":"run_completed","runId":…,"workflowId":…,"status":…}` – nur IDs und Status |

Execution-Logs sind JSON-Objekte, unterschieden über `type`; Step-Nachrichten haben keins. Weitere Typen:
`status`, `approval_required`/`approval_resolved`, `human_input_required`/`human_input_resolved`,
`agentic_*`, `foreach_item_started`/`foreach_item_completed`,
`container_starting`/`container_started`/`container_skipped`, `command` und `live_output` – Ausgabe
laufender Blöcke, gebündelt höchstens alle 250 ms, mit `seq` lückenlos je `bufferId` (Lücken =
übersprungene Zeilen; Nachladen über `GET /api/v1/executions/{id}/live-log`).

Das Terminal (`ws://localhost:9090/ws/terminal/{runId}?ticket=...`) ist ein roher WebSocket ohne
SockJS/STOMP, ebenfalls mit einem frischen Ticket, höchstens 3 Sessions je Container.

## Fehlercodes

Aus `ErrorCode` (`ai-automation-backend/src/main/java/com/automation/exception/ErrorCode.java`). Nennt die Wurfstelle keinen eigenen Text, steht der Standardtext im Feld `error`.

| Code | HTTP-Status | Standardtext |
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

Quelle: `ActivityLogController` – Activity log: read the platform's event log and let plugins add their own entries.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/activity-log` | Lists activity log entries page by page, newest first, optionally filtered by level, category and workflow. | Kern |
| `POST /api/v1/activity-log` | Writes an entry of category `PLUGIN` to the activity log and returns it with 201. | Kern |

## /api/v1/assistant

Quellen:

- `AssistantController` – AI assistant chat: messages, stored chat sessions and confirming or rejecting the tools it proposes.
- `PageSuggestionController` – Quick-action suggestions the assistant offers per page of the UI.

| Endpunkt | Zweck | Modul |
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

Quelle: `AuditController` – Audit center: results of audit workflows (gate status per report), the findings derived from them and their TODOs.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/audit-findings` | Lists findings across all audits page by page, most severe first, then most recently seen. | `audit` |
| `GET /api/v1/audit-findings/{id}` | Returns one finding with its linked TODOs. | `audit` |
| `PATCH /api/v1/audit-findings/{id}` | Changes a finding's status and/or disposition note; fields left out stay unchanged. | `audit` |
| `POST /api/v1/audit-findings/{id}/todo-links` | Links an existing TODO (`todoId`) to the finding. | `audit` |
| `DELETE /api/v1/audit-findings/{id}/todo-links/{todoId}` | Removes the link between a finding and a TODO; answers 204. | `audit` |
| `POST /api/v1/audit-findings/{id}/todos` | Creates a TODO managed by MySuricates from the finding and returns the finding with its TODO links. | `audit` |

## /api/v1/audit-reports

Quelle: `AuditController` – Audit center: results of audit workflows (gate status per report), the findings derived from them and their TODOs.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/audit-reports/{id}` | Returns one audit report including its Markdown text. | `audit` |

## /api/v1/audits

Quelle: `AuditController` – Audit center: results of audit workflows (gate status per report), the findings derived from them and their TODOs.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/audits` | Lists every audit (per project and `auditKey`) with its latest valid report and finding counts. | `audit` |
| `GET /api/v1/audits/{auditKey}` | Returns one audit: its summary with finding counts and its latest valid report including the text. | `audit` |
| `GET /api/v1/audits/{auditKey}/reports` | Lists the reports of an audit page by page, newest first; `projectId` is required. | `audit` |

## /api/v1/chain-templates

Quelle: `ChainTemplateController` – Built-in chain templates that can be installed into a project as a chain with its workflows.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/chain-templates` | Lists the built-in chain templates with their entries. | `chains` |
| `GET /api/v1/chain-templates/{id}` | Returns one built-in chain template. | `chains` |
| `POST /api/v1/chain-templates/{id}/install` | Installs a chain template into a project: creates one workflow per entry plus the chain, answers 201. | `chains` |

## /api/v1/chains

Quelle: `ChainController` – Workflow chains: define sequences of workflows, start them and follow their runs.

| Endpunkt | Zweck | Modul |
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

Quelle: `CompletionTriggerController` – Completion triggers: start a target workflow automatically when a source workflow finishes.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/completion-triggers` | Lists all completion triggers. | Kern |
| `POST /api/v1/completion-triggers` | Creates a completion trigger from source to target workflow and returns it with 201. | Kern |
| `GET /api/v1/completion-triggers/workflow/{workflowId}/as-source` | Lists the triggers that fire when the given workflow finishes. | Kern |
| `GET /api/v1/completion-triggers/workflow/{workflowId}/as-target` | Lists the triggers that start the given workflow. | Kern |
| `GET /api/v1/completion-triggers/{id}` | Returns one completion trigger. | Kern |
| `DELETE /api/v1/completion-triggers/{id}` | Deletes a completion trigger; answers 204. | Kern |
| `POST /api/v1/completion-triggers/{id}/disable` | Disables a trigger without deleting it. | Kern |
| `POST /api/v1/completion-triggers/{id}/enable` | Enables a trigger; the cycle check runs again first. | Kern |

## /api/v1/conversions

Quelle: `ConversionController` – Turns the successful agentic steps of a run into a reusable workflow.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `POST /api/v1/conversions/runs/{runId}/create-workflow` | Creates a workflow from a run's suggestion in the given project. | `conversion` |
| `GET /api/v1/conversions/runs/{runId}/suggest` | Suggests a workflow built from the agentic steps of a run that reached their goal. | `conversion` |

## /api/v1/dashboard

Quellen:

- `DashboardLayoutController` – Version history of the dashboard layout.
- `DashboardReportAssistController` – The editor's AI proposal for a `dashboard-report` block.
- `DashboardWorkflowController` – Data of the dashboard's workflow widgets (spec E3).

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/dashboard/layouts` | Lists saved dashboard layout versions page by page, newest first, without their content. | Kern |
| `POST /api/v1/dashboard/layouts` | Saves the dashboard layout as a new version (`201`), or answers `200` when it equals the newest one. | Kern |
| `GET /api/v1/dashboard/layouts/{id}` | Returns one dashboard layout version including its layout content. | Kern |
| `PATCH /api/v1/dashboard/layouts/{id}` | Sets or removes (`null`) the name of a layout version. | Kern |
| `POST /api/v1/dashboard/report-assist` | Has an AI propose the fields of a `dashboard-report` block for `workflowId` from one of its runs. | `dashboard-report` |
| `GET /api/v1/dashboard/workflow-results` | Ids outside the allowed shape or more than DashboardWorkflowService.MAX_WORKFLOWS of them → 400. | Kern |

## /api/v1/debug

Quelle: `WorkflowDebugController` – Workflow-Debug endpoints — back the /debug "what is currently broken" page.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/debug/failed-workflows` | Workflows whose most recent run failed (FAILED / COMPLETED_WITH_ERRORS), latest first. | `debug` |

## /api/v1/dialog

Quelle: `DialogController` – Dialog import: turns recorded Claude Code sessions (JSONL files under `~/.claude/projects`) into workflows.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/dialog/projects` | Gibt alle bekannten Projekt-Pfade zurück (für Filter-Dropdown). | `dialog-import` |
| `GET /api/v1/dialog/sessions` | Listet Sessions mit Metadaten. | `dialog-import` |
| `POST /api/v1/dialog/sessions/analyze` | Analysiert eine Session mit KI und liefert eine Workflow-Suggestion. | `dialog-import` |
| `GET /api/v1/dialog/sessions/count` | Zählt Sessions pro Projekt-Verzeichnis (schnell, kein File-Lesen). | `dialog-import` |
| `POST /api/v1/dialog/sessions/create-workflow` | Erstellt einen Workflow aus einer Suggestion (mit optionalen User-Anpassungen). | `dialog-import` |

## /api/v1/dlq

Quelle: `DeadLetterController` – Dead letter queue: failed workflow runs, kept for retry or discard.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/dlq` | Lists dead letter entries page by page, most recent failure first; optional `status` filter. | Kern |
| `GET /api/v1/dlq/{id}` | Returns one dead letter entry. | Kern |
| `DELETE /api/v1/dlq/{id}` | Discards a dead letter entry: it is marked `DISCARDED`, not deleted; answers 204. | Kern |
| `POST /api/v1/dlq/{id}/retry` | Starts the workflow again with the input context of the failed run and marks the entry as retried. | Kern |

## /api/v1/docs

Quelle: `DocsController` – Serves the Markdown documentation from the configured docs directory (`automation.docs.directory`) to the UI.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/docs` | Lists the Markdown documents of the docs directory, German and English, each with name, label and language. | Kern |
| `GET /api/v1/docs/{name}` | Returns the Markdown content of one document; `lang` is `de` (default) or `en`. | Kern |

## /api/v1/executions

Quelle: `ExecutionController` – Workflow runs: list and inspect them, control running ones and answer the questions they wait on.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/executions` | Lists workflow runs page by page, newest first, without their steps (fetch one run for those). | Kern |
| `GET /api/v1/executions/pending-input` | Alle Läufe, die gerade auf eine Antwort warten — Approvals und Human-Input, die ältesten zuerst. | Kern |
| `GET /api/v1/executions/{id}` | Returns one workflow run including all its steps. | Kern |
| `DELETE /api/v1/executions/{id}` | Loescht einen Lauf samt Schritten und den losen Verweisen darauf. | Kern |
| `GET /api/v1/executions/{id}/approval` | Returns the approval or human input the run is waiting for, or `{"pending": false}`. | Kern |
| `POST /api/v1/executions/{id}/approve` | Beantwortet die offene Frage des Laufs. | Kern |
| `POST /api/v1/executions/{id}/cancel` | Cancels a running or queued run. | Kern |
| `POST /api/v1/executions/{id}/command` | Sends an ad-hoc command to the Claude session in the container of a running run and returns its output. | Kern |
| `GET /api/v1/executions/{id}/live-log` | Gepufferte Live-Zeilen eines Laufs (nur im Speicher, bis ~10 min nach Laufende) fuer das Replay nach einem Reload. | Kern |
| `PATCH /api/v1/executions/{id}/notes` | Stores a free-text note on the run; a blank `notes` removes it. | Kern |
| `POST /api/v1/executions/{id}/pause` | Pauses a running run before its next block; answers 200 even if the run is not active. | Kern |
| `POST /api/v1/executions/{id}/resume` | Resumes a paused run; answers 200 even if the run is not active. | Kern |
| `POST /api/v1/executions/{id}/resume-from-step` | Continues an `INTERRUPTED` run after its last completed step; without completed steps it starts over. | Kern |
| `POST /api/v1/executions/{id}/retry` | Starts the run's workflow again as a new run with the same input context. | Kern |
| `POST /api/v1/executions/{id}/todos/approve` | Creates (`approved: true`) or discards the TODOs a run proposed for approval. | Kern |

## /api/v1/factory

Quelle: `WorkflowFactoryController`

| Endpunkt | Zweck | Modul |
|---|---|---|
| `POST /api/v1/factory/generate` | Generate a workflow from a topic (preview only, no testing). | `factory` |
| `POST /api/v1/factory/promote/{id}` | Promote a verified workflow as a template. | `factory` |
| `POST /api/v1/factory/run` | Full factory pipeline: generate → deploy to test → verify → optionally promote. | `factory` |
| `GET /api/v1/factory/topics` | Get available topic categories for the factory. | `factory` |
| `POST /api/v1/factory/verify/{id}` | Verify an existing workflow on the test instance. | `factory` |

## /api/v1/goals

Quellen:

- `GoalController` – Ziele: TODOs projektuebergreifend auf ein Ergebnis hin sammeln, ordnen und den Fortschritt zeigen.
- `GoalWorkflowController` – Workflows am Ziel oder an einem Arbeitspaket: anzeigen, anhaengen, Rolle aendern, loesen.

| Endpunkt | Zweck | Modul |
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

Quelle: `IntegrationController` – Integration providers and their connections.

| Endpunkt | Zweck | Modul |
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

Quelle: `LearningController` – Run statistics: dashboard figures, platform counts, per-workflow and per-run analytics, and run ratings.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/learning/dashboard` | Returns the dashboard figures for the last `days` days: runs, success rate, cost, tokens, time series and per-model stats. | Kern |
| `GET /api/v1/learning/platform-stats` | Returns platform-wide counts: projects, workflows, skills, templates, schedules, webhooks, chains, triggers, TODOs and wishes. | Kern |
| `GET /api/v1/learning/runs/{runId}/insights` | Returns cost and token sums of one run plus the figures per step. | `learning` |
| `POST /api/v1/learning/runs/{runId}/rate` | Stores a user rating for a run; the query parameter `rating` must be between 1 and 5. | Kern |
| `GET /api/v1/learning/workflows/{workflowId}/agentic-analytics` | Summarizes the recent agentic steps of a workflow: how often the goal was reached and the average iterations. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/agentic-history` | Lists the most recent agentic steps of a workflow with goal, iterations, outcome and the sequence of actions taken. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/execution-insights` | Returns cost and token sums over all runs of a workflow, plus the models used. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/similar` | Lists other workflows with a similar set of block types, most similar first. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/stats` | Returns run statistics of one workflow: counts per outcome, success rate, durations and average rating. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/step-analytics` | Returns statistics per step index of a workflow across all its runs. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/suggestions` | Returns optimization hints derived from the workflow's runs, e.g. low success rate, failing or slow steps, high cost. | `learning` |
| `GET /api/v1/learning/workflows/{workflowId}/tool-patterns` | Returns which tools the workflow's AI steps used, how often, and recurring tool sequences. | `learning` |

## /api/v1/notifications

Quelle: `NotificationController` – Notification configs that send a webhook or desktop message when a workflow run finishes.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/notifications` | Lists all notification configs. | Kern |
| `POST /api/v1/notifications` | Creates a notification config (201); `name` and `channelType` (`WEBHOOK` or `DESKTOP`) are required. | Kern |
| `GET /api/v1/notifications/{id}` | Returns one notification config. | Kern |
| `DELETE /api/v1/notifications/{id}` | Deletes the notification config (204). | Kern |
| `POST /api/v1/notifications/{id}/disable` | Switches the notification config off. | Kern |
| `POST /api/v1/notifications/{id}/enable` | Switches the notification config on. | Kern |

## /api/v1/onboarding-profiles

Quelle: `OnboardingProfileController` – Onboarding profiles: the built-in and custom texts a project's onboarding file for AI agents is assembled from.

| Endpunkt | Zweck | Modul |
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

Quelle: `WorkflowPoolController`

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/pool` |  | Kern |
| `POST /api/v1/pool/export/{workflowId}` |  | Kern |
| `GET /api/v1/pool/{id}` |  | Kern |
| `POST /api/v1/pool/{id}/install` |  | Kern |

## /api/v1/projects

Quellen:

- `ProjectBundleController` – A whole project as one file: export, preview of an import, import.
- `ProjectController` – Projects: create, edit, archive and restore them, start or stop their container, and manage their onboarding file.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/projects` | Lists all projects that are not archived. | Kern |
| `POST /api/v1/projects` | Creates a project (201); `name`, `type` and `path` are required. | Kern |
| `GET /api/v1/projects/archived` | Lists the archived projects. | Kern |
| `POST /api/v1/projects/example` | The bundled example project for a fresh installation: created on the first call (201), returned on every further call (200) — with any example workflow it lacks added. | Kern |
| `POST /api/v1/projects/import` | Imports a bundle into a new or existing project in one transaction (201); schedules arrive disabled. | Kern |
| `POST /api/v1/projects/import/preview` | Validates a bundle and shows what an import would create, including its shell commands; writes nothing. | Kern |
| `GET /api/v1/projects/{id}` | Returns one project; an archived project is a 404. | Kern |
| `PUT /api/v1/projects/{id}` | Updates the fields sent and keeps the others; a new `path` is checked like on create. | Kern |
| `DELETE /api/v1/projects/{id}` | Archives the project (204) and stops its container if it was running; its workflows stay. | Kern |
| `GET /api/v1/projects/{id}/export` | Downloads the project as a bundle file: master data, workflows, chains and schedules, with local paths as tokens. | Kern |
| `POST /api/v1/projects/{id}/generate-context` | Generates an AI context text from the project directory with the default AI provider and stores it as `aiContext`. | Kern |
| `POST /api/v1/projects/{id}/onboarding/disable` | Switches onboarding off and deletes the onboarding file if MySuricates wrote it. | `onboarding-profiles` |
| `POST /api/v1/projects/{id}/onboarding/enable` | Switches onboarding on and writes the onboarding file from the chosen profiles into the project directory. | `onboarding-profiles` |
| `GET /api/v1/projects/{id}/onboarding/preview` | Returns the onboarding file text as it would be written, as `{"content": …}`, without writing it. | `onboarding-profiles` |
| `POST /api/v1/projects/{id}/onboarding/refresh` | Writes the onboarding file again from the current profiles; does nothing while onboarding is off. | `onboarding-profiles` |
| `GET /api/v1/projects/{id}/onboarding/status` | Returns the onboarding state: on or off, format, profiles, language, file path and whether the file exists or is foreign. | `onboarding-profiles` |
| `POST /api/v1/projects/{id}/restore` | Restores an archived project. | Kern |
| `POST /api/v1/projects/{id}/start` | Creates and starts the project's Docker container and returns the project with status `RUNNING`, or `ERROR` if it failed. | Kern |
| `POST /api/v1/projects/{id}/stop` | Stops the project's Docker container and returns the project with status `STOPPED`. | Kern |
| `GET /api/v1/projects/{id}/workflows` | Lists the workflows of a project with their run counts. | Kern |

## /api/v1/reports

Quelle: `ReportsController`

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/reports` |  | `audit` |
| `GET /api/v1/reports/{name}` |  | `audit` |

## /api/v1/schedules

Quelle: `ScheduleController`

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/schedules` |  | Kern |
| `POST /api/v1/schedules` |  | Kern |
| `GET /api/v1/schedules/calendar` | Upcoming = schedules whose next real run lies in the period (behind a skip-until pause). | Kern |
| `GET /api/v1/schedules/catch-up` | Sequential catch-up queue: the missed occurrence currently running and the ones waiting. | Kern |
| `GET /api/v1/schedules/chain/{chainId}` |  | Kern |
| `GET /api/v1/schedules/maintenance` | Both pauses: the maintenance switch and an active skip-until window from the configuration. | Kern |
| `POST /api/v1/schedules/maintenance` |  | Kern |
| `POST /api/v1/schedules/preview` |  | Kern |
| `GET /api/v1/schedules/remaining-this-week` |  | Kern |
| `GET /api/v1/schedules/remaining-today` | `projectId` (optional): only the schedules of that project's workflows — the dashboard filter. | Kern |
| `GET /api/v1/schedules/workflow/{workflowId}` |  | Kern |
| `GET /api/v1/schedules/{id}` |  | Kern |
| `PUT /api/v1/schedules/{id}` |  | Kern |
| `DELETE /api/v1/schedules/{id}` |  | Kern |
| `POST /api/v1/schedules/{id}/disable` |  | Kern |
| `POST /api/v1/schedules/{id}/enable` |  | Kern |
| `GET /api/v1/schedules/{id}/runs` | Die neuesten Runs eines Zeitplans als Listenzeilen: Anzahl und Vorschau des letzten Schritts statt aller Schritte — die Detail-Route eines Runs liefert den Volltext. | Kern |

## /api/v1/search

Quelle: `SearchController` – Aggregates search across workflows (and later runs/skills/integrations).

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/search` |  | Kern |
| `POST /api/v1/search/workflows/semantic` |  | Kern |

## /api/v1/skills

Quelle: `SkillController`

| Endpunkt | Zweck | Modul |
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

Quellen:

- `StagedSyncController` – Definition sync of the staged setup: Dev → Test / Prod.
- `StagingController`

| Endpunkt | Zweck | Modul |
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

Quellen:

- `SystemAccessController` – Zugriffs-Oberfläche unter /api/v1/system: Remote Access (Tailscale) und lokales Dateisystem.
- `SystemAiProviderController` – AI-Provider- und Capability-Auskunft unter /api/v1/system.
- `SystemController` – Kern-Auskunft unter /api/v1/system: Health, Plattform-Info, Auth-Status, Heap-Belegung und Client-Fehler-Meldungen.
- `SystemMaintenanceController` – Wartung unter /api/v1/system: Auto-Repair, VACUUM, Datenbank-Backups.
- `SystemModulesController` – Runtime module switches for the settings UI.
- `SystemRequestMetricsController` – Request costs per route: the live window from memory and the stored daily history.
- `SystemSettingsController` – Laufzeit-Settings unter /api/v1/system: App-Settings und Execution-Config.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/system/ai-providers` |  | Kern |
| `GET /api/v1/system/ai-providers/PI/catalog` | Returns the pi runtime catalog read from `~/.pi/agent`: which backend providers are authenticated, which are custom-defined in models.json, and what pi's own default-provider/-model is set to. | Kern |
| `GET /api/v1/system/ai-providers/status` | Per provider: program on the PATH, login found (file or environment variable), and the commands for what is missing — for the start page and the settings. | Kern |
| `POST /api/v1/system/ai-providers/status/refresh` | Looks at the machine again now, e.g. right after logging in. | Kern |
| `GET /api/v1/system/ai-providers/{id}/models` | Models the provider offers on this host, discovered from the CLI / its local caches and served from a TTL cache. | Kern |
| `POST /api/v1/system/ai-providers/{id}/models/refresh` | Bypasses the cache and probes the host now. | Kern |
| `GET /api/v1/system/auth-status` |  | Kern |
| `GET /api/v1/system/backup` |  | Kern |
| `POST /api/v1/system/backup` |  | Kern |
| `GET /api/v1/system/backups` |  | Kern |
| `GET /api/v1/system/backups/{filename}` |  | Kern |
| `GET /api/v1/system/browse` |  | Kern |
| `GET /api/v1/system/capabilities` |  | Kern |
| `POST /api/v1/system/client-errors` |  | Kern |
| `GET /api/v1/system/execution-config` |  | Kern |
| `PUT /api/v1/system/execution-config` |  | Kern |
| `GET /api/v1/system/health` |  | Kern |
| `GET /api/v1/system/info` |  | Kern |
| `GET /api/v1/system/integration-snippet` |  | `onboarding-profiles` |
| `GET /api/v1/system/jvm-memory` | Heap-Belegung als genau drei Zahlen, ohne API-Key: der Performance-Audit misst damit den Speicher, ohne dass die ganze Metrik-Oberfläche unter `/actuator/metrics` frei wird — die verrät Routen und Lastprofil und bleibt hinter dem Key. | Kern |
| `PUT /api/v1/system/modules/{name}` |  | Kern |
| `DELETE /api/v1/system/modules/{name}/override` |  | Kern |
| `POST /api/v1/system/open-path` |  | Kern |
| `POST /api/v1/system/remote-access/disable` |  | `remote-access` |
| `POST /api/v1/system/remote-access/enable` |  | `remote-access` |
| `GET /api/v1/system/remote-access/status` |  | `remote-access` |
| `POST /api/v1/system/repair-failed` |  | `factory` |
| `GET /api/v1/system/request-metrics` | Routes measured since the last daily snapshot, most expensive (p95) first. | Kern |
| `GET /api/v1/system/request-metrics/daily` | Stored daily rows for one route, oldest first — the baseline a drift check compares against. | Kern |
| `GET /api/v1/system/settings/{key}` | `value` is the stored value or empty; `default`, only for a setting whose default comes from the startup configuration (`backup-retain-count`), is what applies while nothing is stored. | Kern |
| `PUT /api/v1/system/settings/{key}` |  | Kern |
| `GET /api/v1/system/tray-config` |  | Kern |
| `PUT /api/v1/system/tray-config` |  | Kern |
| `POST /api/v1/system/vacuum` |  | Kern |

## /api/v1/testing

Quelle: `TestingController`

| Endpunkt | Zweck | Modul |
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

Quelle: `TodoTriggerController`

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/todo-triggers` |  | Kern |
| `POST /api/v1/todo-triggers` |  | Kern |
| `GET /api/v1/todo-triggers/workflow/{workflowId}` |  | Kern |
| `GET /api/v1/todo-triggers/{id}` |  | Kern |
| `DELETE /api/v1/todo-triggers/{id}` |  | Kern |
| `POST /api/v1/todo-triggers/{id}/disable` |  | Kern |
| `POST /api/v1/todo-triggers/{id}/enable` |  | Kern |

## /api/v1/todos

Quelle: `TodoController`

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/todos` |  | Kern |
| `POST /api/v1/todos` |  | Kern |
| `POST /api/v1/todos/archive-stale` | Archive stale auto-todos now (cockpit "Verstaubtes archivieren"). | Kern |
| `POST /api/v1/todos/bulk-status` | Bulk status change (e.g. archive selected todos). | Kern |
| `GET /api/v1/todos/count` |  | Kern |
| `GET /api/v1/todos/overview` | Cross-project cockpit overview: per-project rollups + a global "Braucht dich" lane. | Kern |
| `GET /api/v1/todos/similar` | Check for similar TODOs before creating a new one. | Kern |
| `GET /api/v1/todos/{id}` |  | Kern |
| `PUT /api/v1/todos/{id}` |  | Kern |
| `DELETE /api/v1/todos/{id}` |  | Kern |
| `POST /api/v1/todos/{id}/export` |  | `integrations` |
| `PATCH /api/v1/todos/{id}/planning` | Ziel, Rang und Einschaetzung (Komplexitaet, Effort, Begruendung, offene Frage). | `goals` |
| `POST /api/v1/todos/{id}/resolve` |  | Kern |
| `PATCH /api/v1/todos/{id}/status` |  | Kern |

## /api/v1/webhook

Quelle: `WebhookController`

| Endpunkt | Zweck | Modul |
|---|---|---|
| `POST /api/v1/webhook/trigger/{token}` |  | `webhooks` |

## /api/v1/webhooks

Quelle: `WebhookController`

| Endpunkt | Zweck | Modul |
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

Quelle: `WishController`

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/wishes` |  | `wishes` |
| `POST /api/v1/wishes` |  | `wishes` |
| `GET /api/v1/wishes/{id}` |  | `wishes` |
| `DELETE /api/v1/wishes/{id}` |  | `wishes` |
| `POST /api/v1/wishes/{id}/cancel` |  | `wishes` |
| `POST /api/v1/wishes/{id}/crystallize` |  | `wishes` |
| `POST /api/v1/wishes/{id}/respond` |  | `wishes` |

## /api/v1/workflow-templates

Quelle: `WorkflowTemplateController`

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/workflow-templates` |  | Kern |
| `POST /api/v1/workflow-templates` |  | Kern |
| `POST /api/v1/workflow-templates/from-workflow/{workflowId}` |  | Kern |
| `GET /api/v1/workflow-templates/{id}` |  | Kern |
| `PUT /api/v1/workflow-templates/{id}` |  | Kern |
| `DELETE /api/v1/workflow-templates/{id}` |  | Kern |
| `POST /api/v1/workflow-templates/{id}/install` | Creates a workflow from the template in a project, plus the blueprints its sub-workflow blocks call, wired to their new ids. | Kern |

## /api/v1/workflows

Quellen:

- `WorkflowAnalyzerController` – Deterministische Baufehler-Prüfung von Workflow-Definitionen — auf Abruf, nie blockierend.
- `WorkflowAssistController` – Hilfen rund um einen bestehenden Workflow: Optimierungsvorschlag, ähnliche Workflows, Reparatur nach fehlgeschlagenen Runs.
- `WorkflowController`
- `WorkflowImportController` – Import externer Prozessformate als NEUER Workflow (Roundtrip zum Export in `WorkflowController`).
- `WorkflowMemoryController`
- `WorkflowSealController` – Seal / unseal a workflow.
- `WorkflowSealStatusController` – Read-only seal status for the UI.
- `WorkflowVersionController` – Versionsgeschichte eines Workflows: auflisten, einzeln abrufen, wiederherstellen.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/workflows` |  | Kern |
| `POST /api/v1/workflows` |  | Kern |
| `GET /api/v1/workflows/analyze` | Bestandsprüfung über alle Definitionen in aktiven Projekten. | Kern |
| `GET /api/v1/workflows/export/{id}` |  | Kern |
| `POST /api/v1/workflows/import` |  | Kern |
| `POST /api/v1/workflows/import/bpmn` | Importiert einen zuvor exportierten BPMN-Prozess (mit `mysuricates:blockConfig`) als neuen Workflow im Projekt. | `bpmn` |
| `GET /api/v1/workflows/seal-status` | Every sealed workflow plus every workflow with refused starts in the last seven days. | Kern |
| `GET /api/v1/workflows/{id}` |  | Kern |
| `PUT /api/v1/workflows/{id}` |  | Kern |
| `DELETE /api/v1/workflows/{id}` |  | Kern |
| `GET /api/v1/workflows/{id}/analyze` | Befunde einer Definition. | Kern |
| `POST /api/v1/workflows/{id}/execute` |  | Kern |
| `GET /api/v1/workflows/{id}/export/bpmn` | Exportiert den Workflow als BPMN 2.0 XML (Spec: docs/plans/2026-07-09-bpmn-integration-design.md). | `bpmn` |
| `POST /api/v1/workflows/{id}/optimize` |  | Kern |
| `POST /api/v1/workflows/{id}/repair` |  | `factory` |
| `GET /api/v1/workflows/{id}/runs` |  | Kern |
| `GET /api/v1/workflows/{id}/runs/{runId}` |  | Kern |
| `POST /api/v1/workflows/{id}/seal` |  | `sealing` |
| `GET /api/v1/workflows/{id}/seal-events` | Sealing and unsealing of one workflow, newest first — kept beyond the activity log's retention. | Kern |
| `GET /api/v1/workflows/{id}/similar` |  | Kern |
| `POST /api/v1/workflows/{id}/skills` |  | `skills` |
| `DELETE /api/v1/workflows/{id}/skills/{skillId}` |  | `skills` |
| `POST /api/v1/workflows/{id}/unseal` |  | `sealing` |
| `GET /api/v1/workflows/{id}/versions` |  | Kern |
| `GET /api/v1/workflows/{id}/versions/{versionId}` |  | Kern |
| `POST /api/v1/workflows/{id}/versions/{versionId}/restore` |  | Kern |
| `GET /api/v1/workflows/{workflowId}/memory` |  | `memory` |
| `POST /api/v1/workflows/{workflowId}/memory/extract` |  | `memory` |
| `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}` |  | `memory` |
| `DELETE /api/v1/workflows/{workflowId}/memory/{memoryId}` |  | `memory` |
| `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}/activate` |  | `memory` |

## /api/v1/ws-ticket

Quelle: `WebSocketTicketController` – Hands out short-lived, single-use tickets for the WebSocket handshake.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `POST /api/v1/ws-ticket` |  | Kern |
