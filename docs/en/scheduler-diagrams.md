<!-- Generated from docs/scheduler-diagrams.md and docs/i18n/scheduler-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# MySuricates — Scheduler & Cron

Schedules, InputContext, triggering and execution pipeline

## System Overview — *Scheduler Loop*

A one-minute tick checks for due schedules and starts workflows with their stored `inputContext`.

```mermaid
flowchart LR
    subgraph DB["SQLite"]
        SW["scheduled_workflows"]
    end
    subgraph Backend["Backend"]
        SCH["SchedulerService @Scheduled 60s"]
        EXE["ScheduledWorkflowExecutor"]
        WE["WorkflowExecutor"]
    end
    SCH -->|findDueSchedules| SW
    SCH --> EXE
    EXE --> WE
    WE --> SW

    style DB fill:#2e1a1a,stroke:#30363d,color:#e6edf3
```

## Creating Schedules — *API Flow*

```mermaid
sequenceDiagram
    participant UI as Frontend
    participant CTR as ScheduleController
    participant SVC as SchedulerService
    participant DB as scheduled_workflows

    UI->>CTR: POST /api/v1/schedules {workflowId, cronExpression|scheduledAt, inputContext}
    CTR->>SVC: create(...)
    SVC->>DB: INSERT scheduled_workflows (input_context JSON)
    DB-->>SVC: saved schedule
    SVC-->>CTR: ScheduledWorkflowResponse
    CTR-->>UI: 201 Created
```

### Types

`scheduleType`: `CRON` (default) or `ONCE`

CRON needs `cronExpression`; ONCE needs `scheduledAt`

### InputContext

Any JSON; passed on 1:1 to `workflowExecutor.execute(..., context)`.

This way, schedules run deterministically with the stored variables.

## Execution — *Due Schedules*

```mermaid
sequenceDiagram
    participant CRON as @Scheduled (60s)
    participant DB as scheduled_workflows
    participant EX as ScheduledWorkflowExecutor
    participant WE as WorkflowExecutor

    CRON->>DB: findDueSchedules(now)
    DB-->>CRON: List<ScheduledWorkflow>
    loop for each due
        CRON->>EX: executeSingleSchedule(id)
        EX->>WE: execute(workflow, inputContext)
        WE-->>EX: WorkflowRun (async)
        EX->>DB: update lastRunAt, nextRunAt / disable if ONCE
    end
```

## Data Model — *scheduled_workflows*

| Column | Description |
|---|---|
| `workflow_id` | FK → workflow |
| `cron_expression` | CRON string (empty for ONCE) |
| `schedule_type` | `CRON` \| `ONCE` |
| `scheduled_at` | Execution timestamp for ONCE |
| `input_context` | JSON map (parameters for the run) |
| `enabled` | Toggle |
| `last_run_at`, `next_run_at` | Tracking |

## Config & Behavior — *Key Points*

### Validation

CRON is validated with `CronExpression.isValidExpression`.

ONCE requires `scheduledAt`.

### Tick & NextRun

Check: every minute (`@Scheduled(fixedRate=60_000)`).

After execution: CRON → nextRun recalculated, ONCE → disabled.
