# MySuricates — Workflow Control & States

Status-Maschinen, Pause/Resume/Cancel, Approval & Fehlerpfade

## WorkflowRun State Machine — *RunStatus*

```mermaid
stateDiagram-v2
    [*] --> RUNNING : execute()
    RUNNING --> PAUSED : pauseExecution(runId)
    PAUSED --> RUNNING : resumeExecution(runId)
    RUNNING --> FAILED : step failed + on_failure=stop / exception
    RUNNING --> CANCELLED : cancelExecution(runId)
    RUNNING --> COMPLETED : alle Schritte ok
    PAUSED --> CANCELLED : cancelExecution(runId)
    FAILED --> [*]
    CANCELLED --> [*]
    COMPLETED --> [*]
```

## StepResult Status — *StepStatus*

```mermaid
flowchart LR
    PENDING --> RUNNING
    RUNNING --> COMPLETED
    RUNNING --> FAILED
    RUNNING --> SKIPPED
```

Setzen durch BlockExecutor: Shell/Claude Exit-Code -> COMPLETED/FAILED; Conditional ohne Branch -> SKIPPED.

## Pause / Cancel — *ExecutionContext*

```mermaid
sequenceDiagram
    participant UI
    participant CTRL as ExecutionController (n/a)
    participant WE as WorkflowExecutor
    participant CTX as ExecutionContext

    UI->>WE: pauseExecution(runId)
    WE->>CTX: paused=true
    Note over CTX: Block-Loop pollt paused<br/>und wartet 500ms
    UI->>WE: resumeExecution(runId)
    WE->>CTX: paused=false
    UI->>WE: cancelExecution(runId)
    WE->>CTX: cancelled=true + cancel pending approvals
```

## Approval Flow — *ApprovalBlock & Agentic*

```mermaid
sequenceDiagram
    participant BE as ApprovalBlockExecutor
    participant CTX as ExecutionContext
    participant WS as WebSocket /topic/...
    participant USER as User

    BE->>CTX: requestApproval(question, options)
    BE->>WS: broadcast approval_required
    USER-->>WS: answer via UI
    WE->>CTX: resolveApproval(answer)
    CTX-->>BE: future completes
    alt answer == "Yes always"
        CTX->>CTX: rememberApproval(question, answer)
    end
    BE-->>WE: StepResult (COMPLETED/FAILED/SKIPPED je nach on_no)
```

## Fehlerpfade & Stop-on-Failure — *BlockDefinition.on_failure*

### Default Verhalten

`on_failure` fehlt → stoppt Run bei FAILED-Step.

Setzbar auf `"continue"` falls Folge-Schritte trotzdem laufen sollen.

### Agentic Speziell

Stop-Gründe: ITERATION_TIMEOUT, TOTAL_TIMEOUT, TOKEN_BUDGET_EXCEEDED, REPEATED_ACTIONS, GOAL_STATUS=IMPOSSIBLE.

Status: FAILED wenn Ziel nicht erreicht.
