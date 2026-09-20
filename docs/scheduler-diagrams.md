# AutoPilot — Scheduler & Cron

Schedules, InputContext, Ausloesung und Ausfuehrungspipeline

## Systemuebersicht — *Scheduler Loop*

Ein Minutentakt prueft faellige Schedules und startet Workflows mit gespeichertem `inputContext`.

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

## Anlegen von Zeitplaenen — *API Flow*

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

### Typen

`scheduleType`: `CRON` (default) oder `ONCE`

CRON braucht `cronExpression`; ONCE braucht `scheduledAt`

### InputContext

Beliebiges JSON; wird 1:1 an `workflowExecutor.execute(..., context)` weitergegeben.

Damit laufen Zeitplaene deterministisch mit den gespeicherten Variablen.

## Ausfuehrung — *Due Schedules*

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

## Datenmodell — *scheduled_workflows*

| Spalte | Beschreibung |
|---|---|
| `workflow_id` | FK → workflow |
| `cron_expression` | CRON-String (leer bei ONCE) |
| `schedule_type` | `CRON` \| `ONCE` |
| `scheduled_at` | Execution-Timestamp fuer ONCE |
| `input_context` | JSON Map (Parameter fuer Run) |
| `enabled` | Toggle |
| `last_run_at`, `next_run_at` | Tracking |

## Konfig & Verhalten — *wichtige Punkte*

### Validierung

CRON wird mit `CronExpression.isValidExpression` geprüft.

ONCE erfordert `scheduledAt`.

### Takt & NextRun

Check: jede Minute (`@Scheduled(fixedRate=60_000)`).

Nach Ausführung: CRON → nextRun neu berechnet, ONCE → disabled.
