# MySuricates — Logging & Monitoring

WebSocket-Logs, ActivityLog, Health/Info, RateLimit Header

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

Payloads: StepResult (input/output/duration/status), Status-Events, approval_required/approval_resolved, agentic_* Events.

## ActivityLogService — *LogCategory*

```mermaid
flowchart LR
    SVC["Services"] --> ALS["ActivityLogService"]
    ALS --> DB["activity_log (SQLite)"]
```

| Kategorie | Beispiele |
|---|---|
| `EXECUTION` | Workflow start/complete/fail |
| `SCHEDULE` | Schedule triggered/failed |
| `SECURITY` | auth/rate-limit events |

## Health & Info — */api/v1/system*

### Health

`GET /api/v1/system/health`: Status OK

Offen (kein API-Key)

### Info

`GET /api/v1/system/info`: Version, Build Timestamp

Offen (kein API-Key)

## Rate Limit Header — *Filter*

Bei aktivem RateLimitFilter werden verbleibende Requests im Header geliefert.

| Header | Bedeutung |
|---|---|
| `X-RateLimit-Remaining` | Requests im aktuellen 1-Minuten-Fenster |
