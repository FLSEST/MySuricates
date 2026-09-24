<!-- Generated from docs/workflow-control-diagrams.md and docs/i18n/workflow-control-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# AutoPilot — Workflow Control & States

State machines, pause/resume/cancel, approval & error paths

## WorkflowRun State Machine — *RunStatus*

```mermaid
stateDiagram-v2
    [*] --> RUNNING : execute()
    RUNNING --> PAUSED : pauseExecution(runId)
    PAUSED --> RUNNING : resumeExecution(runId)
    RUNNING --> FAILED : step failed + on_failure=stop / exception
    RUNNING --> CANCELLED : cancelExecution(runId)
    RUNNING --> COMPLETED : all steps ok
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

Set by the BlockExecutor: shell/Claude exit code -> COMPLETED/FAILED; conditional without a branch -> SKIPPED.

## Pause / Cancel — *ExecutionContext*

```mermaid
sequenceDiagram
    participant UI
    participant CTRL as ExecutionController (n/a)
    participant WE as WorkflowExecutor
    participant CTX as ExecutionContext

    UI->>WE: pauseExecution(runId)
    WE->>CTX: paused=true
    Note over CTX: Block loop polls paused<br/>and waits 500ms
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
    BE-->>WE: StepResult (COMPLETED/FAILED/SKIPPED depending on on_no)
```

## Error Paths & Stop-on-Failure — *BlockDefinition.on_failure*

### Default Behavior

`on_failure` missing → the run stops at a FAILED step.

Can be set to `"continue"` if the following steps should run anyway.

### Agentic Specifics

Stop reasons: ITERATION_TIMEOUT, TOTAL_TIMEOUT, TOKEN_BUDGET_EXCEEDED, REPEATED_ACTIONS, GOAL_STATUS=IMPOSSIBLE.

Status: FAILED if the goal is not reached.
