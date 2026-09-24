<!-- Generated from docs/logging-monitoring-diagrams.md and docs/i18n/logging-monitoring-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# MySuricates — Logging & Monitoring

WebSocket logs, ActivityLog, health/info, RateLimit header

## WebSocket Log Pipeline — */topic/executions/{runId}/logs*

```mermaid
sequenceDiagram
    participant BE as WorkflowExecutor/BlockExecutors
    participant SMT as SimpMessagingTemplate
    participant ST as STOMP Broker
    participant UI as React (useExecutionWebSocket)

    BE->>SMT: convertAndSend(topic, payload)
    SMT->>ST: dispatch
    ST-->>UI: message (step logs, status, approvals, agentic events)
    UI->>UI: update LogViewer / Status
```

Payloads: StepResult (input/output/duration/status), status events, approval_required/approval_resolved, agentic_* events.

## ActivityLogService — *LogCategory*

```mermaid
flowchart LR
    SVC["Services"] --> ALS["ActivityLogService"]
    ALS --> DB["activity_log (SQLite)"]
```

| Category | Examples |
|---|---|
| `EXECUTION` | Workflow start/complete/fail |
| `SCHEDULE` | Schedule triggered/failed |
| `SECURITY` | auth/rate-limit events |

## Health & Info — */api/v1/system*

### Health

`GET /api/v1/system/health`: status OK

Open (no API key)

### Info

`GET /api/v1/system/info`: version, build timestamp

Open (no API key)

## Rate Limit Header — *Filter*

With the RateLimitFilter active, the remaining requests are reported in a header.

| Header | Meaning |
|---|---|
| `X-RateLimit-Remaining` | Remaining requests in the current 1-minute window |
