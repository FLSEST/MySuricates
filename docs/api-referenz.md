<!-- Erzeugt von scripts/docs-reference.py – nicht von Hand ändern -->

# API-Referenz

> **Scope:** Die REST-API von MySuricates, ab „Fehlercodes“ erzeugt aus dem Code
> (`python3 scripts/docs-reference.py --write`, siehe [Entwicklung → Erzeugte Referenzen](entwicklung.md#erzeugte-referenzen)).
> Die kuratierte Kurzreferenz für externe Agents mit Beispielen ist [AGENTS.md](../AGENTS.md).

Je Bereich (erstes Pfadsegment nach `/api/v1`) eine Tabelle: Methode und Pfad, als Zweck der erste Satz
des Javadoc der Handler-Methode – ohne Javadoc bleibt die Zelle leer – und das Modul aus `@RequiresModule`
(„Kern“ = immer an). Was das Javadoc danach sagt – Statuscodes, Grenzen, Nebenwirkungen –, steht unter der
Tabelle als „Hinweise“. Die Javadocs sind meist englisch. Request- und Response-Felder stehen in den DTOs unter
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

Hinweise:

- `GET /api/v1/activity-log` — `level` and `category` ignore case; an unknown value is rejected with 400.
- `POST /api/v1/activity-log` — `message` is required; `level` defaults to `INFO`, `source` to `plugin`.

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

Hinweise:

- `POST /api/v1/assistant/chat` — With `sessionId` the exchange is appended to that stored session.
- `POST /api/v1/assistant/pending-tools/{id}/confirm` — Returns 410 Gone if the tool has already been consumed or expired — the frontend treats that as "already handled" and doesn't retry.
- `POST /api/v1/assistant/pending-tools/{id}/reject` — Returns 410 Gone if the tool has already been handled or expired.
- `GET /api/v1/assistant/suggestions` — Kept per language: `locale` other than `en` counts as German.

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

Hinweise:

- `GET /api/v1/audit-findings` — Optional filters: `projectId`, `auditKey`, `status`, `severity`.
- `PATCH /api/v1/audit-findings/{id}` — A terminal status sets the resolution time, and the managed TODO is reconciled with the new state.

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

Hinweise:

- `GET /api/v1/audits` — Optional filters: `projectId`, `category`, `gateStatus`.
- `GET /api/v1/audits/{auditKey}` — `projectId` is required; 404 if the audit has no valid report.
- `GET /api/v1/audits/{auditKey}/reports` — Heads only: `markdownContent` is `null`, the text comes from `GET /api/v1/audit-reports/{id}`.

## /api/v1/chain-templates

Quelle: `ChainTemplateController` – Built-in chain templates that can be installed into a project as a chain with its workflows.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/chain-templates` | Lists the built-in chain templates with their entries. | `chains` |
| `GET /api/v1/chain-templates/{id}` | Returns one built-in chain template. | `chains` |
| `POST /api/v1/chain-templates/{id}/install` | Installs a chain template into a project: creates one workflow per entry plus the chain, answers 201. | `chains` |

Hinweise:

- `POST /api/v1/chain-templates/{id}/install` — `projectId` is required; `locale` picks the language of names, labels and bilingual steps (German if omitted).

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

Hinweise:

- `GET /api/v1/chains/runs` — `status` ignores case (unknown value: 400); `search` matches the chain name.
- `POST /api/v1/chains/runs/{runId}/cancel` — Answers 200 even if the run is not active.
- `DELETE /api/v1/chains/{id}` — Refused with 409 while the chain has an active run (`PENDING`, `RUNNING` or `PAUSED`), which would otherwise go on starting the chain's workflows; cancel the run first.
- `POST /api/v1/chains/{id}/execute` — The optional body is the input context of the run.

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

Hinweise:

- `POST /api/v1/completion-triggers` — Both workflows must exist (404); a trigger that would start its own source or close a cycle is rejected with 400.

## /api/v1/container-images

Quelle: `ContainerImageController` – Container images workflows can run in — shipped agent images and any image a workflow names, with tools, build state and usage.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/container-images` | Lists the shipped agent images and every image a workflow names, with tools, build state, allowlist verdict and usage. | Kern |

Hinweise:

- `GET /api/v1/container-images` — Status per image: CURRENT/OUTDATED compare the label `org.mysuricates.dockerfile-sha256` with the catalog, UNLABELED means built without `scripts/build-agent-images.sh`, MISSING not present, UNKNOWN not checkable (Docker unreachable or a placeholder reference). Read-only; rebuilding stays a command on the host.

## /api/v1/conversions

Quelle: `ConversionController` – Turns the successful agentic steps of a run into a reusable workflow.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `POST /api/v1/conversions/runs/{runId}/create-workflow` | Creates a workflow from a run's suggestion in the given project. | `conversion` |
| `GET /api/v1/conversions/runs/{runId}/suggest` | Suggests a workflow built from the agentic steps of a run that reached their goal. | `conversion` |

Hinweise:

- `POST /api/v1/conversions/runs/{runId}/create-workflow` — With `blocks` (the reviewed suggestion) exactly these are saved without a new AI call, and `name` is required; without them the suggestion is generated again. An unknown run is 404, a run that is not COMPLETED or has nothing to convert is 409; the answer is 200 with the new workflow.
- `GET /api/v1/conversions/runs/{runId}/suggest` — Tries an AI conversion first and falls back to rule-based; without such steps the suggestion is marked unavailable.

## /api/v1/dashboard

Quellen:

- `DashboardLayoutController` – Version history of the dashboard layout.
- `DashboardReportAssistController` – The editor's AI proposal for a `dashboard-report` block.
- `DashboardWorkflowController` – Data of the dashboard's workflow widgets (spec E3).

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/dashboard/layouts` | Lists saved dashboard layout versions page by page, newest first, without their content; `size` is capped at 100. | Kern |
| `POST /api/v1/dashboard/layouts` | Saves the dashboard layout as a new version (`201`), or answers `200` when it equals the newest one. | Kern |
| `GET /api/v1/dashboard/layouts/{id}` | Returns one dashboard layout version including its layout content. | Kern |
| `PATCH /api/v1/dashboard/layouts/{id}` | Sets or removes (`null`) the name of a layout version. | Kern |
| `POST /api/v1/dashboard/report-assist` | Has an AI propose the fields of a `dashboard-report` block for `workflowId` from one of its runs. | `dashboard-report` |
| `GET /api/v1/dashboard/workflow-results` | Returns, per requested workflow, its latest run and its validated dashboard result, never step output. | Kern |

Hinweise:

- `POST /api/v1/dashboard/layouts` — The comparison ignores `activeViewId` (each device shows its own view), which is still stored and must name a view. Without `force: true` a `baseVersionId` other than the newest version is refused with `409 STATE_CONFLICT`: another device saved in between. The body is validated field by field (unknown fields → 400) with the limits of the frontend's `LAYOUT_LIMITS` (at most 20 views, 100 widgets per view); over `automation.dashboard.layout.max-request-bytes` (default 256 KiB) it is 413. Each save trims the unnamed versions to the newest `dashboard-layout-retention-count` (default 100); named ones are never trimmed.
- `PATCH /api/v1/dashboard/layouts/{id}` — A name has 1–80 characters; a named version is never deleted automatically.
- `POST /api/v1/dashboard/report-assist` — One tool-less call to the `CLAUDE` CLI over the tail of the latest run, checked against the `dashboard_widget` contract; neither the raw answer nor the run output leaves the server. 400 for a missing id, 404 for an unknown workflow, 409 (with a fixed message) when the execution setting `dashboard-report-assist-enabled` is off, the Claude CLI is missing on this host, there is no usable run, or the answer did not match the contract.
- `GET /api/v1/dashboard/workflow-results` — `workflowIds` (repeated or comma-separated) takes at most 50 ids of the form `^[a-z0-9][a-z0-9-]{0,79}$`, otherwise 400; without ids the list is empty. One entry per distinct id in request order; an unknown or soft-deleted workflow has `available: false` and `resultState: MISSING`, and `result` is only set when `resultState` is `VALID`.

## /api/v1/debug

Quelle: `WorkflowDebugController` – Workflow-Debug endpoints — back the /debug "what is currently broken" page.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/debug/failed-workflows` | Workflows whose most recent run failed (FAILED / COMPLETED_WITH_ERRORS), latest first. | `debug` |

Hinweise:

- `GET /api/v1/debug/failed-workflows` — Restart a workflow by retrying its run via `POST /api/v1/executions/{runId}/retry`.

## /api/v1/dialog

Quelle: `DialogController` – Dialog import: turns recorded Claude Code sessions (JSONL files under `~/.claude/projects`) into workflows.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/dialog/projects` | Returns all known project paths (for the filter dropdown). | `dialog-import` |
| `GET /api/v1/dialog/sessions` | Lists sessions with their metadata. | `dialog-import` |
| `POST /api/v1/dialog/sessions/analyze` | Analyzes a session with AI and returns a workflow suggestion. | `dialog-import` |
| `GET /api/v1/dialog/sessions/count` | Counts the sessions per project directory (fast, without reading the files). | `dialog-import` |
| `POST /api/v1/dialog/sessions/create-workflow` | Creates a workflow from a suggestion (with optional user adjustments). | `dialog-import` |

Hinweise:

- `GET /api/v1/dialog/sessions` — `limit` (default 50) is capped at 2000 with `projectPath` (one project, all sessions visible) and at 500 without it (for "Other Sessions").
- `POST /api/v1/dialog/sessions/analyze` — `filePath` must be a `.jsonl` file under `~/.claude/projects`; any other path is 400.
- `POST /api/v1/dialog/sessions/create-workflow` — `projectId` is required; the answer is 200 with the new workflow.

## /api/v1/dlq

Quelle: `DeadLetterController` – Dead letter queue: failed workflow runs, kept for retry or discard.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/dlq` | Lists dead letter entries page by page, most recent failure first; optional `status` filter. | Kern |
| `GET /api/v1/dlq/{id}` | Returns one dead letter entry. | Kern |
| `DELETE /api/v1/dlq/{id}` | Discards a dead letter entry: it is marked `DISCARDED`, not deleted; answers 204. | Kern |
| `POST /api/v1/dlq/{id}/retry` | Starts the workflow again with the input context of the failed run and marks the entry as retried. | Kern |

Hinweise:

- `POST /api/v1/dlq/{id}/retry` — If the workflow or the failed run no longer exists, nothing starts, but the entry is still marked retried.

## /api/v1/docs

Quelle: `DocsController` – Serves the Markdown documentation from the configured docs directory (`automation.docs.directory`) to the UI.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/docs` | Lists the Markdown documents of the docs directory, German and English, each with name, label and language. | Kern |
| `GET /api/v1/docs/{name}` | Returns the Markdown content of one document; `lang` is `de` (default) or `en`. | Kern |

Hinweise:

- `GET /api/v1/docs/{name}` — Answers 400 for names with path parts or without `.md`, 404 if the file does not exist.

## /api/v1/executions

Quelle: `ExecutionController` – Workflow runs: list and inspect them, control running ones and answer the questions they wait on.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/executions` | Lists workflow runs page by page, newest first, without their steps (fetch one run for those). | Kern |
| `GET /api/v1/executions/pending-input` | Lists all runs currently waiting for an answer — approvals and human input, oldest first. | Kern |
| `GET /api/v1/executions/{id}` | Returns one workflow run including all its steps. | Kern |
| `DELETE /api/v1/executions/{id}` | Deletes a run with its steps and the loose references to it. | Kern |
| `GET /api/v1/executions/{id}/approval` | Returns the approval or human input the run is waiting for, or `{"pending": false}`. | Kern |
| `POST /api/v1/executions/{id}/approve` | Answers the run's open question. | Kern |
| `POST /api/v1/executions/{id}/cancel` | Cancels a running or queued run. | Kern |
| `POST /api/v1/executions/{id}/command` | Sends an ad-hoc command to the Claude session in the container of a running run and returns its output. | Kern |
| `GET /api/v1/executions/{id}/live-log` | Returns a run's live lines for the replay, from memory while the run is fresh and from the stored log afterwards (if the workflow keeps it). | Kern |
| `PATCH /api/v1/executions/{id}/notes` | Stores a free-text note on the run; a blank `notes` removes it. | Kern |
| `POST /api/v1/executions/{id}/pause` | Pauses a running run before its next block; answers 200 even if the run is not active. | Kern |
| `POST /api/v1/executions/{id}/resume` | Resumes a paused run; answers 200 even if the run is not active. | Kern |
| `POST /api/v1/executions/{id}/resume-from-step` | Continues an `INTERRUPTED` run after its last completed step; without completed steps it starts over. | Kern |
| `POST /api/v1/executions/{id}/retry` | Starts the run's workflow again as a new run with the same input context. | Kern |
| `POST /api/v1/executions/{id}/todos/approve` | Creates (`approved: true`) or discards the TODOs a run proposed for approval. | Kern |

Hinweise:

- `GET /api/v1/executions` — Filters: `status` (case-insensitive, unknown value: 400), `workflowId`, `projectId`, `search` (workflow name).
- `DELETE /api/v1/executions/{id}` — 409 while the run is still active or an audit report belongs to it. TODOs and audit data are never deleted with it — see `RunDeletionService`. 403 `RUN_DELETION_DISABLED` when `automation.executions.allow-delete=false` (`RunDeletionPolicy`) — checked before any lookup, so it does not reveal whether the run exists.
- `GET /api/v1/executions/{id}/approval` — Includes `options` and `rejectOptions`; human input adds its input fields.
- `POST /api/v1/executions/{id}/approve` — 400 when an approval does not offer the answer as an option.
- `POST /api/v1/executions/{id}/cancel` — A run still marked `RUNNING` without an active execution is set to `CANCELLED` directly.
- `GET /api/v1/executions/{id}/live-log` — `available=false` instead of 404 when there is none.
- `POST /api/v1/executions/{id}/resume-from-step` — Other statuses are refused.

## /api/v1/factory

Quelle: `WorkflowFactoryController` – Workflow factory: has an AI generate workflows from a topic, verify them on the test instance and promote them to templates.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `POST /api/v1/factory/generate` | Generate a workflow from a topic (preview only, no testing). | `factory` |
| `POST /api/v1/factory/promote/{id}` | Promote a verified workflow as a template. | `factory` |
| `POST /api/v1/factory/run` | Full factory pipeline: generate → deploy to test → verify → optionally promote. | `factory` |
| `GET /api/v1/factory/topics` | Get available topic categories for the factory. | `factory` |
| `POST /api/v1/factory/verify/{id}` | Verify an existing workflow on the test instance. | `factory` |

Hinweise:

- `POST /api/v1/factory/generate` — `topic` is required; the answer is 201 with the new workflow.
- `POST /api/v1/factory/run` — `maxRetries` (default 2) bounds the repair attempts made with `autoFix`.

## /api/v1/goals

Quellen:

- `GoalController` – Goals: collect TODOs across projects towards one outcome, order them and show the progress.
- `GoalWorkflowController` – Workflows on a goal or one of its work packages: list, attach, change role, detach.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/goals` | Lists goals, newest first; without `status` the active and done ones. | `goals` |
| `POST /api/v1/goals` | Creates a goal (201); `title` is required, optional `projectIds` are attached in that order. | `goals` |
| `GET /api/v1/goals/{goalId}/workflows` | Lists the workflows attached to the goal: the guard first (if switched on), then those on the goal, then those on work packages. | `goals` |
| `POST /api/v1/goals/{goalId}/workflows` | Attaches a workflow to the goal or one of its work packages (201); `role` is `WORKER` (default) or `STATUS`. | `goals` |
| `PATCH /api/v1/goals/{goalId}/workflows/{attachmentId}` | Changes the role of an attachment (`WORKER` or `STATUS`); the `guard` entry cannot be changed (400). | `goals` |
| `DELETE /api/v1/goals/{goalId}/workflows/{attachmentId}` | Detaches the workflow, which itself stays; `guard` → 400 (the guard has its own switch). | `goals` |
| `GET /api/v1/goals/{id}` | Returns the goal with its TODOs, progress per project, projects, work packages and milestones. | `goals` |
| `PUT /api/v1/goals/{id}` | Partially updates a goal: `null` keeps a field, an empty `description` or `targetDate` clears it. | `goals` |
| `DELETE /api/v1/goals/{id}` | Deletes the goal (204); its TODOs stay and only lose the assignment, a guard is paused. | `goals` |
| `GET /api/v1/goals/{id}/candidates` | Lists open TODOs without a goal that could belong to it, by default only from the goal's projects (`allProjects=true`: from all). | `goals` |
| `POST /api/v1/goals/{id}/guard` | Switches the goal guard on or off. | `goals` |
| `GET /api/v1/goals/{id}/milestones` | Lists the goal's milestones by date, then title, with state and package counters. | `goals` |
| `POST /api/v1/goals/{id}/milestones` | Creates a milestone (201); `dueDate` is required, `workPackageIds` must belong to the same goal (else 400). | `goals` |
| `PUT /api/v1/goals/{id}/milestones/{milestoneId}` | Updates a milestone; `workPackageIds` replaces its links, `reached` only without linked packages (else 400). | `goals` |
| `DELETE /api/v1/goals/{id}/milestones/{milestoneId}` | Deletes the milestone (204); the linked work packages stay. | `goals` |
| `PUT /api/v1/goals/{id}/order` | Sets the order: the TODOs named get rank 1..n, all other TODOs of the goal none. | `goals` |
| `POST /api/v1/goals/{id}/projects` | Appends a project to the goal's projects; one already assigned stays unchanged (idempotent). | `goals` |
| `PUT /api/v1/goals/{id}/projects/order` | Reorders the goal's projects: those named first, the others behind them in their previous order. | `goals` |
| `DELETE /api/v1/goals/{id}/projects/{projectId}` | Detaches a project from the goal (204). | `goals` |
| `GET /api/v1/goals/{id}/relations` | Returns the goal's relation map: per TODO its origin, its handling (fix workflow, matching TODO triggers) and its result. | `goals` |
| `POST /api/v1/goals/{id}/todos` | Assigns TODOs to the goal without a rank – they come after the ranked ones. | `goals` |
| `GET /api/v1/goals/{id}/work-packages` | Lists the goal's work packages by project position and position in the project, with state and counters. | `goals` |
| `POST /api/v1/goals/{id}/work-packages` | Adds a work package at the end of its project (201); without `projectId` only if the goal has exactly one project. | `goals` |
| `PUT /api/v1/goals/{id}/work-packages/order` | Reorders the work packages of ONE project; the answer lists all work packages of the goal. | `goals` |
| `PUT /api/v1/goals/{id}/work-packages/{workPackageId}` | Partially updates a work package; `""` clears a date, the result needs both dates or none (else 400). | `goals` |
| `DELETE /api/v1/goals/{id}/work-packages/{workPackageId}` | Deletes the work package (204); its TODOs stay in the goal, milestones lose the link, its workflow attachments go. | `goals` |

Hinweise:

- `GET /api/v1/goals` — With `projectId` only the goals this project is assigned to.
- `POST /api/v1/goals` — An unknown project id is a 404.
- `GET /api/v1/goals/{goalId}/workflows` — Soft-deleted workflows are left out.
- `POST /api/v1/goals/{goalId}/workflows` — A work package of another goal is a 400, an unknown workflow a 404, the same attachment twice a 409.
- `GET /api/v1/goals/{id}` — Reading records the moment a work package or milestone is first found complete or reached.
- `DELETE /api/v1/goals/{id}` — Work packages, milestones and workflow attachments go with it; the guard's workflow stays.
- `GET /api/v1/goals/{id}/candidates` — Paged like `GET /api/v1/todos`.
- `POST /api/v1/goals/{id}/guard` — The first switch-on creates a workflow from the template `goal-guard` plus a daily schedule; after that only the schedule is enabled or disabled. An invalid `cron` or no project to put the workflow in is 400.
- `PUT /api/v1/goals/{id}/order` — Every id must already belong to the goal (else 400).
- `PUT /api/v1/goals/{id}/projects/order` — A project not in the goal is 400.
- `DELETE /api/v1/goals/{id}/projects/{projectId}` — While work packages or TODOs of the goal still belong to it, the answer is 409 – with `cascade=true` the work packages are deleted and the TODOs detached.
- `GET /api/v1/goals/{id}/relations` — `include` adds up to 20 TODOs not yet assigned as candidates (`candidate: true`); ids of TODOs that already belong to a goal are skipped.
- `POST /api/v1/goals/{id}/todos` — A TODO of another goal moves here and loses its work package.
- `GET /api/v1/goals/{id}/work-packages` — The state is OPEN/DUE_SOON/OVERDUE/REACHED based on the end; without a period only OPEN/REACHED.
- `POST /api/v1/goals/{id}/work-packages` — A named project not yet in the goal is added to it; `startDate` and `endDate` come together or not at all. The title has at most 200 characters.
- `PUT /api/v1/goals/{id}/work-packages/order` — That project's packages not named follow behind; packages of different projects are 400, one of another goal 404.
- `PUT /api/v1/goals/{id}/work-packages/{workPackageId}` — `projectId` can change only while no TODOs are assigned to the package; the package then moves to the end of the new project.

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

Hinweise:

- `GET /api/v1/integrations/{providerId}/callback` — Exchanges authorization code for tokens, then returns an HTML page that notifies the parent window via postMessage. The connection name comes from the server-side state, never from this request — the callback URL is the same for all connections.
- `GET /api/v1/integrations/{providerId}/connect` — `frontendOrigin` must be the API's own origin or one CORS allows (else 400); no client credentials is a 409.
- `POST /api/v1/integrations/{providerId}/credentials` — Both are required unless the provider needs no credentials; a provider without OAuth flow checks them right away.

## /api/v1/learning

Quelle: `LearningController` – Run statistics: dashboard figures, platform counts, per-workflow and per-run analytics, and run ratings.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/learning/dashboard` | Returns the dashboard figures: all-time run counts and success rate, plus cost, tokens, time series and model stats of the last `days` days. | Kern |
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

Hinweise:

- `GET /api/v1/learning/dashboard` — `projectId` restricts every value, the overall counts included, to that project's workflows and runs. `recentRuns` holds at most the ten newest runs of the window, without steps.

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

Hinweise:

- `POST /api/v1/notifications` — Without `workflowId` it fires for every workflow; `messageTemplate` may use placeholders like `{{status}}`. `triggerOnSuccess` covers COMPLETED and COMPLETED_WITH_ERRORS, `triggerOnFailure` FAILED and INTERRUPTED, `triggerAlways` any status. Placeholders, written without spaces: `{{workflow_name}}`, `{{workflow_id}}`, `{{run_id}}`, `{{status}}`, `{{error_message}}`; without a template a default message is sent. A `WEBHOOK` config without `webhookUrl` is accepted but sends nothing.

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

Hinweise:

- `POST /api/v1/onboarding-profiles` — `content` is Markdown per language and needs at least one filled language; a taken `key` is a 400.
- `PUT /api/v1/onboarding-profiles/{id}` — A content change keeps the old text in the history and rewrites the onboarding files of the projects using it.
- `POST /api/v1/onboarding-profiles/{id}/reset` — A custom profile is a 400. Afterwards `customized` is `false`, and a changed text rewrites the onboarding files of the projects using the profile.
- `GET /api/v1/onboarding-profiles/{id}/shipped` — A custom profile has no shipped text: 404.

## /api/v1/pool

Quelle: `WorkflowPoolController` – Workflow pool: list and install the shared workflow files of the pool directory, and export a workflow into it.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/pool` | Lists the workflows of the pool directory, sorted by English name; empty when the pool is switched off. | Kern |
| `POST /api/v1/pool/export/{workflowId}` | Writes the workflow as a new pool file and answers `201`; instance bindings such as ids are removed and listed in `sanitizedPaths`. | Kern |
| `GET /api/v1/pool/{id}` | Returns one pool workflow; `locale` as for the list. | Kern |
| `POST /api/v1/pool/{id}/install` | Installs a pool workflow into the project `projectId`, or updates the copy an earlier install left there. | Kern |

Hinweise:

- `POST /api/v1/pool/export/{workflowId}` — Id keys (`projectId`, `workflowId`, ...) with a concrete value and UUID values are dropped, placeholders like `{{ project_id }}` stay, and a UUID inside a text becomes `REDACTED-UUID`. An unknown `scope` is 400.
- `POST /api/v1/pool/{id}/install` — The copy is also found under its name in the other language; `forceCreate` always creates a new one. A pool file whose checksum does not match is refused with `400`; the answer's `integrity` says `VERIFIED` or `MISSING`.

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

Hinweise:

- `POST /api/v1/projects` — `path` must be an existing directory (under the workspace root if one is set), otherwise 400.
- `POST /api/v1/projects/example` — Its files are copied to `automation.examples.directory`; an existing copy is never overwritten. An example whose blocks need a switched-off module is left out until a later call with the module on; `locale` (`de`/`en`, default `en`) picks the language of names and workflows.
- `POST /api/v1/projects/import` — Every path token needs an entry in `pathMappings`, otherwise 400 and nothing is created.
- `DELETE /api/v1/projects/{id}` — `POST /{id}/restore` brings it back.
- `GET /api/v1/projects/{id}/export` — An unknown or archived project is a 404.
- `POST /api/v1/projects/{id}/generate-context` — Returns `{"context": …}`; the project path must exist (else 400).
- `POST /api/v1/projects/{id}/onboarding/enable` — `format` is `CLAUDE_RULES` (default) or `AGENTS_MD`; a file without the MySuricates marker is never overwritten.

## /api/v1/reports

Quelle: `ReportsController` – Lists and reads the Markdown reports in the configured reports directory (`automation.reports.directory`).

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/reports` | Lists the `.md` files of the reports directory as `name` and `label`, newest date in the name first. | `audit` |
| `GET /api/v1/reports/{name}` | Returns the content of one report as `content`. | `audit` |

Hinweise:

- `GET /api/v1/reports` — An unreadable directory yields an empty list.
- `GET /api/v1/reports/{name}` — Only plain `.md` file names are accepted (400 otherwise); a missing file is 404.

## /api/v1/schedules

Quelle: `ScheduleController` – Time-based starts of workflows and chains: cron, one-off and delayed schedules, calendar and the scheduler pauses.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/schedules` | Lists all schedules. | Kern |
| `POST /api/v1/schedules` | Creates a schedule for a workflow (`workflowId`) or a chain (`targetType: CHAIN`, `chainId`) and answers 201. | Kern |
| `GET /api/v1/schedules/calendar` | Returns the calendar data between `from` and `to`: upcoming schedules and past runs. | Kern |
| `GET /api/v1/schedules/catch-up` | Sequential catch-up queue: the missed occurrence currently running and the ones waiting. | Kern |
| `GET /api/v1/schedules/chain/{chainId}` | Lists the schedules of one chain. | Kern |
| `GET /api/v1/schedules/maintenance` | Both pauses: the maintenance switch and an active skip-until window from the configuration. | Kern |
| `POST /api/v1/schedules/maintenance` | Switches the scheduler maintenance mode on or off with `enabled`; while it is on, no schedule starts. | Kern |
| `POST /api/v1/schedules/preview` | Previews the next run times of the cron expression in `cronExpression`. | Kern |
| `GET /api/v1/schedules/remaining-this-week` | Lists the runs still due this week; `projectId` (optional) filters like `remaining-today`. | Kern |
| `GET /api/v1/schedules/remaining-today` | Lists the runs still due today. | Kern |
| `GET /api/v1/schedules/workflow/{workflowId}` | Lists the schedules of one workflow. | Kern |
| `GET /api/v1/schedules/{id}` | Returns one schedule; unknown id is 404. | Kern |
| `PUT /api/v1/schedules/{id}` | Updates a schedule; only the fields sent are changed. | Kern |
| `DELETE /api/v1/schedules/{id}` | Deletes a schedule (204). | Kern |
| `POST /api/v1/schedules/{id}/disable` | Disables a schedule and clears its next run. | Kern |
| `POST /api/v1/schedules/{id}/enable` | Enables a schedule and computes its next run. | Kern |
| `GET /api/v1/schedules/{id}/runs` | Lists a schedule's newest workflow and chain runs as list rows, with step count and last-step preview. | Kern |

Hinweise:

- `GET /api/v1/schedules` — `nextRunAt` is the next run that will actually start; during a skip-until pause `pausedUntil` names its end, and while the maintenance switch is on `nextRunAt` is null and `maintenance` true.
- `POST /api/v1/schedules` — Unknown enum values, an invalid cron expression or a schedule without any run left are 400. `targetType`, `scheduleType` and `conflictBehavior` are trimmed and ignore case; the defaults are `WORKFLOW`, `CRON` and `SKIP`.
- `GET /api/v1/schedules/calendar` — Upcoming = schedules whose next real run lies in the period (behind a skip-until pause). Past runs: the newest `limit` of the period plus their total. `from` and `to` are required; `limit` defaults to 1000 and is clamped to 1–5000.
- `POST /api/v1/schedules/maintenance` — The switch is stored and survives a restart.
- `POST /api/v1/schedules/preview` — It returns 5 times. A missing or invalid expression is 400; during a skip-until pause the times start after it and `pausedUntil` is set.
- `GET /api/v1/schedules/remaining-today` — `projectId` (optional): only the schedules of that project's workflows — the dashboard filter; chains belong to no project and are left out then.
- `PUT /api/v1/schedules/{id}` — If an enabled cron schedule is then left without a run before `endDate` or `maxRuns`, the answer is 400.
- `POST /api/v1/schedules/{id}/enable` — An unparsable cron expression, a passed `endDate` or used-up `maxRuns` is 400.
- `GET /api/v1/schedules/{id}/runs` — The detail route of a run has the full text. At most `limit` per kind (default 50, clamped to 1–500), plus the total of each.

## /api/v1/search

Quelle: `SearchController` – Aggregates search across workflows (and later runs/skills/integrations).

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/search` | Searches workflows by name, description, tags and category for `q` and returns the best matches. | Kern |
| `POST /api/v1/search/workflows/semantic` | Ranks workflows against a natural-language `query` with the AI suggestions model, each with a match reason. | Kern |

Hinweise:

- `GET /api/v1/search` — An empty `q` yields an empty list; `limit` (default 20) is capped at 50.
- `POST /api/v1/search/workflows/semantic` — Answers 204 when semantic search is switched off (`automation.discovery.semantic-enabled`).

## /api/v1/skills

Quelle: `SkillController` – Skills and their capabilities: list, create, edit, delete, version history and JSON import/export.

| Endpunkt | Zweck | Modul |
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

Hinweise:

- `PUT /api/v1/skills/{id}` — Custom skills only (built-in → 409); a skill used by a sealed workflow → 409 SKILL_PINNED_BY_SEAL. Only name, description, icon, category and `aiContext` change; `configSchema`, the capabilities and the version label stay as they are.
- `DELETE /api/v1/skills/{id}` — A built-in skill or one used by a sealed workflow is refused with 409.

## /api/v1/staging

Quellen:

- `StagedSyncController` – Definition sync of the staged setup: Dev → Test / Prod.
- `StagingController` – The local Stable environment (Docker container): status, start/stop, promote and definition sync from this instance.

| Endpunkt | Zweck | Modul |
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

Hinweise:

- `POST /api/v1/staging/promote` — While a promote is running another one is 409; progress is in `GET /api/v1/staging/status`.
- `POST /api/v1/staging/start` — Refused with 409 when no Stable frontend has been built yet; run a promote first.
- `POST /api/v1/staging/sync-definitions` — Refused with 409 while Stable is not healthy.
- `GET /api/v1/staging/targets` — Targets that are not configured are left out.
- `POST /api/v1/staging/targets/{target}/sync-definitions` — For `prod` the selection is narrowed to sealed definitions; errors as for the preview.
- `GET /api/v1/staging/targets/{target}/sync-preview` — A target that is not configured, has no key file or is unreachable is 409.

## /api/v1/system

Quellen:

- `SystemAccessController` – Access endpoints under /api/v1/system: remote access (Tailscale) and the local file system.
- `SystemAiProviderController` – AI providers and capabilities under /api/v1/system.
- `SystemController` – Core information under /api/v1/system: health, platform info, auth status, heap usage and client error reports.
- `SystemMaintenanceController` – Maintenance under /api/v1/system: auto-repair, VACUUM, database backups.
- `SystemModulesController` – Runtime module switches for the settings UI.
- `SystemRequestMetricsController` – Request costs per route: the live window from memory and the stored daily history.
- `SystemSettingsController` – Runtime settings under /api/v1/system: application settings and execution config.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/system/ai-providers` | Lists the registered AI providers, the default provider and each provider's declared capabilities. | Kern |
| `GET /api/v1/system/ai-providers/PI/catalog` | Returns the pi runtime catalog read from `~/.pi/agent`: which backend providers are authenticated, which are custom-defined in models.json, and what pi's own default-provider/-model is set to. | Kern |
| `GET /api/v1/system/ai-providers/status` | Per provider: program on the PATH, login found (file or environment variable), and the commands for what is missing — for the start page and the settings. | Kern |
| `POST /api/v1/system/ai-providers/status/refresh` | Looks at the machine again now, e.g. right after logging in. | Kern |
| `GET /api/v1/system/ai-providers/{id}/models` | Models the provider offers on this host, discovered from the CLI / its local caches and served from a TTL cache. | Kern |
| `POST /api/v1/system/ai-providers/{id}/models/refresh` | Bypasses the cache and probes the host now. | Kern |
| `GET /api/v1/system/auth-status` | Tells whether API-key security is switched on (`securityEnabled`); reachable without a key. | Kern |
| `GET /api/v1/system/backup` | Creates a backup of the database now and returns it as a file download. | Kern |
| `POST /api/v1/system/backup` | Creates a backup of the database and returns its file name, size and time. | Kern |
| `GET /api/v1/system/backups` | Lists the backup files with name, size and creation time, newest first. | Kern |
| `GET /api/v1/system/backups/{filename}` | Downloads one backup file; an unknown name or one with a path in it is 404. | Kern |
| `GET /api/v1/system/browse` | Lists the subdirectories of `path` (default: the home directory) without hidden ones, plus the parent path. | Kern |
| `GET /api/v1/system/capabilities` | Describes what this instance offers: API and app version, all API routes, the block types usable now and the module state. | Kern |
| `POST /api/v1/system/client-errors` | Accepts an error report from the browser client and writes it to the backend log (202). | Kern |
| `GET /api/v1/system/execution-config` | Returns the current execution settings (concurrency, timeouts, rate limit and more) plus the read-only `activeRuns`. | Kern |
| `PUT /api/v1/system/execution-config` | Changes execution settings with immediate effect and returns the full current set. | Kern |
| `GET /api/v1/system/health` | Health check without a key: `UP`, `DEGRADED` (Docker expected but not answering) or `DOWN` (database unreachable). | Kern |
| `GET /api/v1/system/info` | Returns name, version, build, Java version, runtime mode, environment and the rate-limit figures; reachable without a key. | Kern |
| `GET /api/v1/system/integration-snippet` | Renders the base onboarding text as a Markdown snippet to paste into a project, e.g. into an `AGENTS.md`. | `onboarding-profiles` |
| `GET /api/v1/system/jvm-memory` | Returns the heap usage as exactly three numbers, without an API key. | Kern |
| `PUT /api/v1/system/modules/{name}` | Switches a feature module on or off (`enabled`) with immediate effect and returns the state of all modules. | Kern |
| `DELETE /api/v1/system/modules/{name}/override` | Drops the settings override of a module so it follows the startup configuration again; returns the state of all modules. | Kern |
| `POST /api/v1/system/open-path` | Opens a file or directory (`path`) in the desktop's file manager on the host. | Kern |
| `POST /api/v1/system/remote-access/disable` | Takes Tailscale down on the host; a failing command or a container instance is 503. | `remote-access` |
| `POST /api/v1/system/remote-access/enable` | Brings Tailscale up on the host and returns the Tailscale IP and hostname. | `remote-access` |
| `GET /api/v1/system/remote-access/status` | Returns whether Tailscale is up, with IP and hostname, and whether this instance runs in a container. | `remote-access` |
| `POST /api/v1/system/repair-failed` | Queues a repair for every workflow with a failed run and answers 202 with the number queued. | `factory` |
| `GET /api/v1/system/request-metrics` | Routes measured since the last daily snapshot, most expensive (p95) first. | Kern |
| `GET /api/v1/system/request-metrics/daily` | Stored daily rows for one route, oldest first — the baseline a drift check compares against. | Kern |
| `GET /api/v1/system/settings/{key}` | Returns one application setting; a key that is not allowed answers `400`. | Kern |
| `PUT /api/v1/system/settings/{key}` | Stores an application setting from `value` in the body. | Kern |
| `GET /api/v1/system/tray-config` | Returns the settings of the host tray icon: popups, which events notify, and the public URL the browser opens. | Kern |
| `PUT /api/v1/system/tray-config` | Updates the tray settings; only the fields sent change, and the answer is the full state. | Kern |
| `POST /api/v1/system/vacuum` | Runs a full SQLite VACUUM and reports its duration; a failed VACUUM is 500. | Kern |

Hinweise:

- `GET /api/v1/system/ai-providers/status` — Cached, see `AiProviderStatusService`; never a token or file content. `READY` means program and login were found, not that the login is still valid.
- `GET /api/v1/system/ai-providers/{id}/models` — A failed probe (CLI missing, timeout, unreadable output) never fails the request: `available` is then `false`, `reason` says why and `models` is empty. An unknown provider id is 404.
- `POST /api/v1/system/backup` — An optional `reason` goes into the activity log; old backups beyond the retain count are deleted.
- `GET /api/v1/system/browse` — The root and system directories are refused with 400, as is a path that is no readable directory.
- `GET /api/v1/system/capabilities` — Block types whose module is switched off are left out.
- `PUT /api/v1/system/execution-config` — All values are checked before any is applied, so an invalid one (400) leaves every setting unchanged. Keys are the kebab-case names or their camelCase aliases; an unknown key or a value outside its bounds (`ExecutionSettingKeys.BOUNDS`) is 400. Stored values are applied again after a restart.
- `GET /api/v1/system/health` — The components list database response time and Docker state.
- `GET /api/v1/system/integration-snippet` — With a `projectPath` of a known project it uses that project's id and lists its workflows; `locale` picks the language (default `en`).
- `GET /api/v1/system/jvm-memory` — The performance audit measures memory with it without opening all of `/actuator/metrics`, which reveals routes and load profile and stays behind the key. The source is the MemoryMXBean, not Micrometer: `jvm.memory.max` sums over pools whose maximum is undefined (`-1`). Without a known heap maximum, maximum and percentage are `null` rather than guessed; the percentage has one decimal.
- `PUT /api/v1/system/modules/{name}` — An unknown module name is 404; the change is recorded as a SECURITY entry in the activity log.
- `POST /api/v1/system/open-path` — An empty, missing or system path (`/etc`, `/proc`, ...) is 400.
- `POST /api/v1/system/remote-access/enable` — A failing Tailscale command, or an instance running in a container, is 503.
- `POST /api/v1/system/repair-failed` — Without `force=true` only workflows with auto-repair enabled; sealed workflows are always skipped.
- `GET /api/v1/system/request-metrics/daily` — Without `route` today's stored rows of all routes are returned instead, most expensive first; `days` (default 7, at most 90) only applies with `route`.
- `GET /api/v1/system/settings/{key}` — `value` is the stored value or empty; `default`, only for a setting whose default comes from the startup configuration (`backup-retain-count`), is what applies while nothing is stored.
- `PUT /api/v1/system/settings/{key}` — Keys outside the allowed list, or a missing `value`, are 400. `backup-retain-count` takes 1–365, checked before anything is stored, and applies from the next backup rotation on, also after a restart.
- `PUT /api/v1/system/tray-config` — `publicUrl` must use HTTPS, plain HTTP only for localhost (400 otherwise).

## /api/v1/testing

Quelle: `TestingController` – Controls the Test instance container: start, stop, reset, push definitions to it and pull changes back.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `POST /api/v1/testing/pull-changes` | Takes instructions and model of the Test instance's workflows into the workflows with the same name and project here. | `staging` |
| `POST /api/v1/testing/report-result` | Records a test result as test status of the workflow with this name and project name. | `staging` |
| `POST /api/v1/testing/reset` | Wipes the Test database volume, starts the container fresh and pushes all definitions to it. | `staging` |
| `POST /api/v1/testing/start` | Starts the Test container with this instance's API key and waits for its health check. | `staging` |
| `GET /api/v1/testing/status` | Reports whether the Test container is running and healthy, plus its version and build timestamp when healthy. | `staging` |
| `POST /api/v1/testing/stop` | Stops the Test container. | `staging` |
| `POST /api/v1/testing/sync-definitions` | Copies definitions from this instance to the Test instance, after a backup there. | `staging` |
| `GET /api/v1/testing/sync-preview` | Lists which projects, workflows, skills and chains a sync would create or update on the Test instance. | `staging` |

Hinweise:

- `POST /api/v1/testing/pull-changes` — Returns 409 while the Test instance is not healthy.
- `POST /api/v1/testing/report-result` — `workflowName` and `status` are required (else 400); an unknown workflow is only logged.
- `POST /api/v1/testing/reset` — Everything that existed only on the Test instance is lost.
- `POST /api/v1/testing/start` — Returns 409 while no `stable-frontend/` build exists (promote first).
- `POST /api/v1/testing/sync-definitions` — Without a body everything is synced; `projectIds`, `workflowIds`, `skillIds` and `chainIds` narrow it down. Returns 409 while the Test instance is not healthy.

## /api/v1/todo-triggers

Quelle: `TodoTriggerController` – TODO triggers: start a fix workflow automatically when an open TODO matches tags, project, source and priority.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/todo-triggers` | Lists all TODO triggers. | Kern |
| `POST /api/v1/todo-triggers` | Creates a TODO trigger and returns it with 201; `tagPattern` and `targetWorkflowId` are required. | Kern |
| `GET /api/v1/todo-triggers/workflow/{workflowId}` | Lists the TODO triggers that start the given workflow. | Kern |
| `GET /api/v1/todo-triggers/{id}` | Returns one TODO trigger. | Kern |
| `DELETE /api/v1/todo-triggers/{id}` | Deletes a TODO trigger. | Kern |
| `POST /api/v1/todo-triggers/{id}/disable` | Disables a TODO trigger; it then ignores new and existing TODOs. | Kern |
| `POST /api/v1/todo-triggers/{id}/enable` | Enables a TODO trigger. | Kern |

Hinweise:

- `POST /api/v1/todo-triggers` — `sourceFilter` and `minPriority` ignore case, other values return 400; with `processBacklog` (default on) the periodic poll also picks up existing matching TODOs.

## /api/v1/todos

Quelle: `TodoController` – TODOs: list, create, edit and close them, start their fix workflow, export them to a tracker, find duplicates.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/todos` | Lists TODOs page by page, most urgent first, filtered by project, status, priority, source, tag, text or goal. | Kern |
| `POST /api/v1/todos` | Creates a TODO and returns it with 201; only `title` is required. | Kern |
| `POST /api/v1/todos/archive-stale` | Archive stale auto-todos now (cockpit "Verstaubtes archivieren"). | Kern |
| `POST /api/v1/todos/bulk-status` | Bulk status change (e.g. archive selected todos). | Kern |
| `GET /api/v1/todos/count` | Counts TODOs per status, optionally only those of `projectId`. | Kern |
| `GET /api/v1/todos/overview` | Cross-project cockpit overview: per-project rollups + a global "Braucht dich" lane. | Kern |
| `GET /api/v1/todos/similar` | Check for similar TODOs before creating a new one. | Kern |
| `GET /api/v1/todos/{id}` | Returns one TODO. | Kern |
| `PUT /api/v1/todos/{id}` | Updates the fields sent in the body and leaves the others unchanged. | Kern |
| `DELETE /api/v1/todos/{id}` | Deletes a TODO permanently. | Kern |
| `POST /api/v1/todos/{id}/export` | Creates a task for the TODO in an external tracker and returns its reference; a TODO already exported returns the existing link. | `integrations` |
| `PATCH /api/v1/todos/{id}/planning` | Sets a TODO's goal, work package, rank, assessment (complexity, effort, reasoning, open question) and decision. | `goals` |
| `POST /api/v1/todos/{id}/resolve` | Starts the TODO's linked fix workflow and sets the TODO to IN_PROGRESS. | Kern |
| `PATCH /api/v1/todos/{id}/status` | Sets the status of a TODO (`status` required), optionally recording the `sessionId` that changed it. | Kern |

Hinweise:

- `GET /api/v1/todos` — ARCHIVED TODOs only appear when `status` names them; status, priority and source ignore case, an unknown value returns 400, and `goalId=none` selects TODOs without a goal.
- `POST /api/v1/todos` — Defaults are OPEN, MEDIUM and source USER; an unknown `projectId` or `linkedWorkflowId` returns 404, and a new OPEN TODO can start matching TODO triggers. `agentSessionRef` keeps only the conversation UUID found in the value (a whole `claude --resume` command is accepted); a value without a UUID is dropped.
- `POST /api/v1/todos/archive-stale` — Optional `projectId` scopes it to one project; omit to archive across all. Returns `{"archived": n}`.
- `POST /api/v1/todos/bulk-status` — Returns `{"updated": n}`.
- `GET /api/v1/todos/similar` — `projectId` and `title` are required. Frontend calls this to offer merge/link options to the user.
- `PUT /api/v1/todos/{id}` — An empty `dueDate` or `linkedWorkflowId` clears it; an unknown project or workflow id returns 404.
- `POST /api/v1/todos/{id}/export` — Provider and target come from the body or the project's `taskExport` setting.
- `PATCH /api/v1/todos/{id}/planning` — A `goalId` in the same request is applied first; an unknown goal or work package is 404. `goalRank` of 0 or less removes the rank, a rank without a goal is 400. Sending `complexity`, `suggestedEffort` or `ratingNote` stamps `ratedAt`, with `ratedBy` defaulting to `USER`; other values than S/M/L/XL or low/medium/high/xhigh are 400.
- `POST /api/v1/todos/{id}/resolve` — Returns 400 without a linked workflow and 409 when the TODO is DONE or its last fix run is still running.

## /api/v1/webhook

Quelle: `WebhookController` – Webhooks: manage the tokens that start a workflow from outside, and the public trigger endpoint itself.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `POST /api/v1/webhook/trigger/{token}` | Starts the webhook's workflow without API key; the token in the path authorizes the call. | `webhooks` |

Hinweise:

- `POST /api/v1/webhook/trigger/{token}` — An optional JSON object body is merged over `defaultContext` (keys starting with `__` return 400); with a signing secret the call needs a valid `X-Signature` header, else 401. A body that is no JSON object is 400, an unknown token 404, a disabled webhook 409.

## /api/v1/webhooks

Quelle: `WebhookController` – Webhooks: manage the tokens that start a workflow from outside, and the public trigger endpoint itself.

| Endpunkt | Zweck | Modul |
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
| `DELETE /api/v1/webhooks/{id}/secret` | Removes the signing secret, so trigger calls no longer need a signature; logged as `SECURITY`. | `webhook-signatures` |

Hinweise:

- `GET /api/v1/webhooks` — Masked means the first 8 characters plus `…`; only create and regenerate-token show the full token.
- `POST /api/v1/webhooks` — Only this response shows the full token and URL (`tokenVisible: true`) – store them right away.
- `POST /api/v1/webhooks/{id}/regenerate-token` — Like creating a webhook, the response is the only place the new token is ever revealed in full — every other management endpoint masks it. The old token stops working at once; `lastTriggeredAt` and `triggerCount` stay, and a `SECURITY` entry is written to the activity log.
- `POST /api/v1/webhooks/{id}/secret` — From then on every trigger call needs a valid signature; a `SECURITY` entry is logged.

## /api/v1/wishes

Quelle: `WishController` – Wishes: a request in plain words that an AI loop decomposes into workflows, runs, evaluates and refines.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/wishes` | Lists all wishes, newest first. | `wishes` |
| `POST /api/v1/wishes` | Creates a wish and starts fulfilling it in the background; returns it with 201. | `wishes` |
| `GET /api/v1/wishes/{id}` | Returns one wish with its status, iterations and generated workflows. | `wishes` |
| `DELETE /api/v1/wishes/{id}` | Deletes a wish; only COMPLETED, FAILED or CANCELLED wishes can be deleted (else 409). | `wishes` |
| `POST /api/v1/wishes/{id}/cancel` | Stops the fulfillment loop and marks a wish that is not finished yet as CANCELLED. | `wishes` |
| `POST /api/v1/wishes/{id}/crystallize` | Saves the first generated workflow of a completed wish as a template and returns `templateId`. | `wishes` |
| `POST /api/v1/wishes/{id}/respond` | Responds to a wish with `action` ACCEPT, REFINE, CANCEL, CLARIFY or RETRY plus optional `feedback`. | `wishes` |

Hinweise:

- `POST /api/v1/wishes` — `text` and `projectId` are required, an unknown project returns 404.
- `POST /api/v1/wishes/{id}/respond` — Each action is only allowed in its matching state (else 409); RETRY restarts a FAILED or CANCELLED wish.

## /api/v1/workflow-templates

Quelle: `WorkflowTemplateController` – Workflow templates and blueprints: list, maintain, create from a workflow, and install into a project.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/workflow-templates` | Lists all templates and blueprints by `sortOrder`, each with the projects it was verified in. | Kern |
| `POST /api/v1/workflow-templates` | Creates a user template (`builtIn` is always `false`) and answers `201`. | Kern |
| `POST /api/v1/workflow-templates/from-workflow/{workflowId}` | Saves a workflow's name, description, blocks, instructions and model as a new user template (`201`). | Kern |
| `GET /api/v1/workflow-templates/{id}` | Returns one template; `locale` as for the list. | Kern |
| `PUT /api/v1/workflow-templates/{id}` | Changes the given fields of a template; fields left out stay as they are. | Kern |
| `DELETE /api/v1/workflow-templates/{id}` | Deletes a user template (`204`); a built-in template is refused with `409`. | Kern |
| `POST /api/v1/workflow-templates/{id}/install` | Creates a workflow from the template in a project, plus the blueprints its sub-workflow blocks call, wired to their new ids. | Kern |

Hinweise:

- `POST /api/v1/workflow-templates/{id}/install` — An unknown referenced blueprint is a 404 and nothing is created. A blank `name` or `description` takes the template's in `locale` (default English); `metadata` goes to the new workflow only, not to the blueprints it calls.

## /api/v1/workflows

Quellen:

- `WorkflowAnalyzerController` – Deterministic check of workflow definitions for construction errors — on demand, never blocking.
- `WorkflowAssistController` – Help with an existing workflow: optimization suggestion, similar workflows, repair after failed runs.
- `WorkflowController` – Workflows: create, read, change and delete them, start runs, list their runs and move them as JSON.
- `WorkflowImportController` – Imports external process formats as a NEW workflow (round trip to the export in `WorkflowController`).
- `WorkflowMemoryController` – Workflow memory: knowledge distilled from a workflow's runs, of which one entry can be active per workflow.
- `WorkflowSealController` – Seal / unseal a workflow.
- `WorkflowSealStatusController` – Read-only seal status for the UI.
- `WorkflowVersionController` – Version history of a workflow: list, fetch one, restore.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `GET /api/v1/workflows` | Lists all workflows of projects that are not archived, each with its run count. | Kern |
| `POST /api/v1/workflows` | Creates a workflow in the project named by `projectId` and answers `201`. | Kern |
| `GET /api/v1/workflows/analyze` | Checks all definitions in active projects. | Kern |
| `GET /api/v1/workflows/export/{id}` | Exports the workflow as portable JSON without ids or project, with an `integrity` checksum that `POST /api/v1/workflows/import` verifies. | Kern |
| `POST /api/v1/workflows/import` | Imports a workflow from its JSON export and answers `201`. | Kern |
| `POST /api/v1/workflows/import/bpmn` | Imports a previously exported BPMN process (with `mysuricates:blockConfig`) as a new workflow in the project. | `bpmn` |
| `GET /api/v1/workflows/seal-status` | Every sealed workflow plus every workflow with refused starts in the last seven days. | Kern |
| `GET /api/v1/workflows/{id}` | Returns one workflow with its blocks and activated skills. | Kern |
| `PUT /api/v1/workflows/{id}` | Changes the given fields of a workflow; fields left out stay as they are. | Kern |
| `DELETE /api/v1/workflows/{id}` | Soft-deletes a workflow (`204`); a sealed workflow is refused with `409 WORKFLOW_SEALED`. | Kern |
| `GET /api/v1/workflows/{id}/analyze` | Returns the findings of one workflow definition. | Kern |
| `POST /api/v1/workflows/{id}/execute` | Starts a manual run and returns it right away with status `RUNNING`; the blocks run in the background. | Kern |
| `GET /api/v1/workflows/{id}/export/bpmn` | Exports the workflow as BPMN 2.0 XML for download. | `bpmn` |
| `POST /api/v1/workflows/{id}/optimize` | Asks the workflow's AI provider for an optimization suggestion and returns it as JSON. | Kern |
| `POST /api/v1/workflows/{id}/repair` | Starts an AI repair of a workflow with failed runs in the background and answers `202`. | `factory` |
| `GET /api/v1/workflows/{id}/runs` | Lists the workflow's runs, newest first, paginated (`page`, `size`) and without their steps. | Kern |
| `GET /api/v1/workflows/{id}/runs/{runId}` | Returns one run with its steps; the run is looked up by `runId` alone. | Kern |
| `POST /api/v1/workflows/{id}/seal` | Seals the workflow: records and signs the hash of its definition and of its skills; idempotent. | `sealing` |
| `GET /api/v1/workflows/{id}/seal-events` | Sealing and unsealing of one workflow, newest first — kept beyond the activity log's retention. | Kern |
| `GET /api/v1/workflows/{id}/similar` | Lists other workflows whose block types overlap with this one, most similar first, at most `limit` entries. | Kern |
| `POST /api/v1/workflows/{id}/skills` | Activates the skills in `skillIds` for the workflow; already active ones are kept once. | `skills` |
| `DELETE /api/v1/workflows/{id}/skills/{skillId}` | Deactivates one skill for the workflow. | `skills` |
| `POST /api/v1/workflows/{id}/unseal` | Removes the seal and logs the previous hash as a `SECURITY` entry; idempotent. | `sealing` |
| `GET /api/v1/workflows/{id}/versions` | Lists the workflow's versions, newest first, paginated (`page`, `size`). | Kern |
| `GET /api/v1/workflows/{id}/versions/{versionId}` | Returns one version with its definition snapshot; a version of another workflow answers `404`. | Kern |
| `POST /api/v1/workflows/{id}/versions/{versionId}/restore` | Sets the workflow's definition back to this version; the change is recorded as a new version. | Kern |
| `GET /api/v1/workflows/{workflowId}/memory` | Lists the workflow's memory entries, newest first. | `memory` |
| `POST /api/v1/workflows/{workflowId}/memory/extract` | Has an AI summarize the steps of the given runs into a new, inactive memory entry and answers `201`. | `memory` |
| `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}` | Changes title and/or content of a memory entry; a field left out stays as it is. | `memory` |
| `DELETE /api/v1/workflows/{workflowId}/memory/{memoryId}` | Deletes a memory entry (`204`). | `memory` |
| `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}/activate` | Makes this memory entry the workflow's active one and deactivates all others (`204`). | `memory` |

Hinweise:

- `POST /api/v1/workflows` — The first version row is written with it; unknown block types are rejected.
- `GET /api/v1/workflows/analyze` — The answer is condensed on purpose: only counts per workflow, plus the distribution over the rules. The full findings come per workflow — a full output over hundreds of definitions would be a megabyte nobody reads.
- `POST /api/v1/workflows/import` — With `updateIfExists` a workflow of the same name in the target project is updated instead (`200`); without `projectId` it goes to the collection project. A checksum in `integrity` that does not match the content is rejected with `400` before anything is written. A blank `version` keeps the default label on a new workflow and the previous one on an update.
- `POST /api/v1/workflows/import/bpmn` — Foreign BPMN, broken XML or oversized input → 400; nothing is created when the import fails (parsing and validation run before `create`).
- `PUT /api/v1/workflows/{id}` — A definition change writes a new version and is refused on a sealed workflow (`409 WORKFLOW_SEALED`); name and `version` label are no definition change and stay editable. A blank `version` or one over 255 characters is 400 – leave the field out to keep the label.
- `POST /api/v1/workflows/{id}/execute` — The optional `context` is merged over the workflow's default context. A refused start creates no run.
- `POST /api/v1/workflows/{id}/optimize` — Changes nothing; a failed AI call comes back as `200` with `error: true` and a `message`.
- `POST /api/v1/workflows/{id}/repair` — Without a failed run `400`; a sealed workflow is refused with `409 WORKFLOW_SEALED`.
- `POST /api/v1/workflows/{id}/seal` — Afterwards definition changes and deletion are refused and a changed definition no longer starts.
- `GET /api/v1/workflows/{id}/similar` — `limit` defaults to 5 and is capped at 20.
- `POST /api/v1/workflows/{id}/unseal` — Refused with `403 SEAL_IMMUTABLE` while `automation.hardening.seal-immutable` is on.
- `POST /api/v1/workflows/{id}/versions/{versionId}/restore` — A sealed workflow is refused with `409 WORKFLOW_SEALED`.
- `POST /api/v1/workflows/{workflowId}/memory/extract` — Without `runIds` the workflow's latest runs are used; `provider` defaults to `CLAUDE`. No runs or no steps: `422`; a failed AI call: `502`.
- `PUT /api/v1/workflows/{workflowId}/memory/{memoryId}/activate` — The active entry is written into the project before each run so the executing agent can read it.

## /api/v1/ws-ticket

Quelle: `WebSocketTicketController` – Hands out short-lived, single-use tickets for the WebSocket handshake.

| Endpunkt | Zweck | Modul |
|---|---|---|
| `POST /api/v1/ws-ticket` | Issues a single-use ticket for the WebSocket handshake URL; it expires after `expiresInSeconds`. | Kern |
