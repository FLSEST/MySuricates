# API Reference

> **Scope:** The MySuricates REST API, generated from the code from “Error codes” on
> (`python3 scripts/docs-reference.py --write`, see [Development → Generated references](entwicklung.md#generated-references)).
> The curated short reference for external agents, with examples, is [AGENTS.md](../../AGENTS.md).

One table per area (first path segment after `/api/v1`): method and path, as purpose the first sentence
of the handler method's Javadoc – without Javadoc the cell stays empty – and the module from
`@RequiresModule` (“core” = always on). Whatever the Javadoc says after that – status codes, limits, side
effects – is listed below the table as “Notes”. Request and response fields are in the DTOs under
`ai-automation-backend/src/main/java/com/automation/dto/`, the behaviour in the controller named in the
“Source” line.

## General

- **Base URL:** `http://localhost:9090/api/v1`, content type `application/json`
- **Authentication:** header `X-API-Key: <key>` (when `automation.security.enabled=true`) – the key
  may do everything. Only the run token is restricted (`X-Run-Token`, valid only during a run whose
  workflow sets `metadata.permissions: ["read"]`): only `GET` on a fixed list, see
  [WORKFLOW-GUIDE → Read platform data with the run token](WORKFLOW-GUIDE.md#read-platform-data-with-the-run-token).
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
