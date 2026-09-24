# MySuricates — Workflow Control & States

Status-Maschinen, Pause/Resume/Cancel, Approval & Fehlerpfade

## WorkflowRun State Machine — *RunStatus*

Ein Workflow-Run entsteht direkt als `RUNNING` (`PENDING` ist nur der Vorgabewert der Entität). Weist `RunAdmission` den Start ab — ein versiegelter Workflow passt nicht mehr zu seinem Siegel, oder ein unbeaufsichtigter Start trifft mit `require-sealed-for-unattended` auf einen unversiegelten Workflow —, entsteht gar kein Run.

```mermaid
stateDiagram-v2
    [*] --> RUNNING : execute()
    RUNNING --> PAUSED : pauseExecution(runId)
    PAUSED --> RUNNING : resumeExecution(runId)
    RUNNING --> FAILED : step failed + on_failure=stop / exception
    RUNNING --> CANCELLED : cancelExecution(runId)
    RUNNING --> COMPLETED : alle Schritte ok
    RUNNING --> COMPLETED_WITH_ERRORS : ein checked_command-Schritt schlug fehl, der Run lief weiter
    PAUSED --> CANCELLED : cancelExecution(runId)
    RUNNING --> INTERRUPTED : Neustart des Backends oder Standby
    PAUSED --> INTERRUPTED : Neustart des Backends
    INTERRUPTED --> RUNNING : resume-from-step
    FAILED --> [*]
    CANCELLED --> [*]
    COMPLETED --> [*]
    COMPLETED_WITH_ERRORS --> [*]
```

Beim Start räumt das Backend Runs auf, die noch als `RUNNING` oder `PAUSED` gespeichert sind, und setzt sie auf `INTERRUPTED`. Einen Run ohne Ausführungs-Thread setzt der Watchdog nach `automation.execution.ghost-watchdog-stale-minutes` auf `FAILED` — oder auf `INTERRUPTED`, wenn kurz zuvor ein Standby erkannt wurde. `resume-from-step` setzt hinter dem letzten abgeschlossenen Schritt an; ohne abgeschlossenen Schritt startet ein neuer Run.

## StepResult Status — *StepStatus*

```mermaid
flowchart LR
    PENDING --> RUNNING
    RUNNING --> COMPLETED
    RUNNING --> FAILED
    RUNNING --> SKIPPED
```

`BlockStepRunner` legt jeden Schritt gleich als `RUNNING` an (`PENDING` ist der Vorgabewert der Entität) und setzt dann das Ergebnis des BlockExecutors: Exit-Code der Shell bzw. der KI-CLI → COMPLETED/FAILED; Conditional ohne Befehl für den gewählten Zweig und abgelehntes Approval mit `on_no: skip` → SKIPPED.

## Pause / Cancel — *ExecutionContext*

```mermaid
sequenceDiagram
    participant UI
    participant CTRL as ExecutionController
    participant WE as WorkflowExecutor / WorkflowExecutionRegistry
    participant CTX as ExecutionContext

    UI->>CTRL: POST /api/v1/executions/{id}/pause
    CTRL->>WE: pauseExecution(runId)
    WE->>CTX: paused=true
    Note over CTX: Vor dem nächsten Block setzt der Loop<br/>den Run auf PAUSED und prüft alle 500 ms
    UI->>CTRL: POST /api/v1/executions/{id}/resume
    CTRL->>WE: resumeExecution(runId)
    WE->>CTX: paused=false
    UI->>CTRL: POST /api/v1/executions/{id}/cancel
    CTRL->>WE: cancelExecution(runId)
    alt Run hat einen Kontext (läuft oder wartet auf einen Slot)
        WE->>CTX: cancelled=true, laufenden Prozess beenden, offene Freigabe abbrechen
    else Ghost-Run ohne Thread
        WE->>WE: Run direkt auf CANCELLED setzen
    end
```

## Approval Flow — *ApprovalBlock & Agentic*

```mermaid
sequenceDiagram
    participant BE as ApprovalBlockExecutor
    participant CTX as ExecutionContext
    participant WS as WebSocket /topic/executions/{runId}/logs
    participant USER as User
    participant API as ExecutionController

    BE->>CTX: requestApproval(question, options)
    BE->>WS: broadcast approval_required
    USER->>API: POST /api/v1/executions/{id}/approve (Feld answer)
    API->>CTX: resolveApproval(answer), nur angebotene Optionen, sonst 400
    CTX-->>BE: future completes
    BE->>WS: broadcast approval_resolved
    alt answer == "Yes always"
        BE->>CTX: rememberApproval(question, answer)
    end
    alt Ablehnung (reject_options, Standard No/Nein)
        BE-->>BE: on_no: continue → COMPLETED, skip → SKIPPED, fail → FAILED
    else Zustimmung
        BE-->>BE: COMPLETED, Antwort in approval_answer
    end
```

Ohne Antwort nach `timeout_minutes` (Standard 30) oder beim Abbruch des Runs endet der Schritt `FAILED`. Wartende Freigaben listet `GET /api/v1/executions/pending-input` (Seite `/approvals`). Der Agentic-Block fragt mit `require_approval` über dieselbe Mechanik (`agentic_approval_required`); „No“ lässt dort die Iteration mit einem neuen Vorschlag wiederholen.

## Fehlerpfade & Stop-on-Failure — *BlockDefinition.on_failure*

### Default Verhalten

`on_failure` fehlt oder steht auf `"stop"` → ein FAILED-Schritt beendet den Run mit `FAILED`.

Mit `"continue"` laufen die Folge-Schritte trotzdem; scheitert dabei ein Schritt mit `checked_command`, endet der Run als `COMPLETED_WITH_ERRORS`.

### Agentic Speziell

Stop-Gründe: TOTAL_TIMEOUT, ITERATION_TIMEOUT, TOKEN_BUDGET_EXCEEDED, REPEATED_ACTIONS, TOKEN_TRACKING_FAILED; außerdem GOAL_STATUS=IMPOSSIBLE und das Erreichen von `max_iterations`.

Status: COMPLETED nur bei GOAL_STATUS=ACHIEVED, sonst FAILED.
