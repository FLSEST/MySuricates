# AutoPilot — Workflow Engine

Kernlogik, Ausführungsmodi (mit/ohne KI) und Steuerflüsse

## Systemuebersicht — *High-Level*

Alle Workflows laufen ueber den WorkflowExecutor. Er orchestriert BlockExecutors, Container-Start, Skill-Kontext und WebSocket-Logs.

```mermaid
flowchart LR
    subgraph Browser["Browser (React SPA)"]
        UI["Workflow Pages + Live Logs"]
    end

    subgraph Backend["Backend (Spring Boot)"]
        WE["WorkflowExecutor"]
        BE["BlockExecutors"]
        CES["CommandExecutorService"]
        SCS["ShellCommandService"]
        HCL["HostClaudeService"]
        WS["/topic/executions/{runId}/logs"]
    end

    subgraph Runtime["Runtime Target"]
        CON["Docker Container (Projekt)"]
        HOST["Host (Fallback)"]
    end

    UI -->|"POST /workflows/{id}/execute"| WE
    WE -->|"blocks"| BE
    BE -->|"claude"| CES
    BE -->|"shell"| SCS
    CES -->|"CLI"| CON
    SCS --> CON
    CES -.fallback.-> HOST
    SCS -.fallback.-> HOST
    WE --> WS
    WS --> UI

    style Browser fill:#1a1f2e,stroke:#30363d,color:#e6edf3
    style Backend fill:#1a1f2e,stroke:#30363d,color:#e6edf3
    style Runtime fill:#1a2e1a,stroke:#3fb950,color:#e6edf3
```

## Ausfuehrung (Standard, KI-gestuetzt) — *Reason/Act*

Default: Befehle gehen an Claude-CLI (Host/Container). Instruction-Prefix + Model kommen aus dem Workflow.

```mermaid
sequenceDiagram
    participant UI as Frontend
    participant WE as WorkflowExecutor
    participant BE as BlockExecutor
    participant CES as CommandExecutorService
    participant CLI as Claude CLI
    participant WS as WebSocket

    UI->>WE: POST /workflows/{id}/execute
    WE->>WE: create WorkflowRun (RUNNING)
    WE->>WE: ensureContainerRunning()
    WE->>BE: for block in blocks
    BE->>CES: executeStepOnHost/Container(command, model, instructions)
    CES->>CLI: claude -p "<command>"
    CLI-->>CES: output + exitCode + usage
    CES-->>BE: StepResult (status by exitCode)
    BE-->>WE: add StepResult, set previous_output
    WE->>WS: broadcast step log
    alt step failed & on_failure=stop
        WE->>WE: mark RUN FAILED
    end
    WE->>WE: mark RUN COMPLETED/FAILED
    WE->>WS: final status event
```

## Ausfuehrung (ohne KI) — *Shell-Pfade*

Shell-Mode nutzt keine Modelle, keine Instruktions-Prefixe, bewertet nur Exit-Code.

```mermaid
sequenceDiagram
    participant BE as BlockExecutor
    participant SCS as ShellCommandService
    participant SH as Shell
    participant WE as WorkflowExecutor

    BE->>SCS: execute(command, workDir, timeout)
    SCS->>SH: run command
    SH-->>SCS: output + exitCode
    SCS-->>BE: StepResult (status by exitCode)
    BE-->>WE: add StepResult
```

### Wann Shell?

- Blocktyp `shell`
- `execution_mode=shell` bei foreach/while/retry

### Unterschiede

- Kein Model / kein Prompt-Limit
- Deterministisch durch Exit-Code
- Kein Token-Metadata

## Agentic Loop — *Reason–Act–Observe*

Mehrstufige KI-Planung mit Token-/Zeit-Budgets, Wiederholungs-Stopp und optionalem Approval.

```mermaid
sequenceDiagram
    participant AG as AgenticBlockExecutor
    participant HC as HostClaudeService
    participant CLI as claude -p (reasonStructured)
    participant CES as CommandExecutorService (ACT)
    participant CLI2 as claude -p (action)

    AG->>HC: buildConversationPrompt + reasonStructured()
    HC->>CLI: stream-json
    CLI-->>HC: structured reasoning
    HC-->>AG: RATIONALE + ACTION
    alt ACTION empty
        AG->>AG: ask for clearer action
    else ACTION present
        AG->>CES: executeCommandOnHost(action, model, sysPrompt)
        CES->>CLI2: run action
        CLI2-->>CES: output + exitCode + usage
        CES-->>AG: result
    end
    AG->>AG: record iteration, token budget, repetition check
    AG->>AG: stop if goal ACHIEVED/IMPOSSIBLE/timeout/repeat/token
```

## Control Flow — *Conditional & Approval*

Branching und User-Gates innerhalb eines Runs.

```mermaid
flowchart LR
    A["conditional block<br/>condition -> Claude TRUE/FALSE"] -->|then| T["run then_command (simple)"]
    A -->|else| E["run else_command (simple)"]
    subgraph Approval
        Q["approval block<br/>question + options"] --> MEM["remember 'Yes always' per run"]
        Q --> OUTC["answer stored as variable"]
        Q --> DEC{"on_no"}
        DEC -->|fail| FAIL["Step FAILED"]
        DEC -->|skip| SKIP["Step SKIPPED"]
        DEC -->|continue| CONT["Step COMPLETED"]
    end
```

## Provider & Ausfuehrungsmodi — *KI vs. Kein KI*

| Merkmal | KI (Claude) | Ohne KI (Shell) |
|---|---|---|
| CLI | `claude -p` | System-Shell |
| Kontext | Instruction-Prefix, Skill-Context, previous_output | Nur Shell-Kommandostring |
| Determinismus | Niedriger (LLM) | Hoch (Exit-Code) |
| Telemetry | Token/Cost in StepResult.metadata | exitCode + duration |
| Fallback | Host, falls Container nicht startet | Host gleicher Pfad |

## Block-Matrix — *Typ → Exec-Modus*

| Block | Default | Optionen | Stop on failure |
|---|---|---|---|
| `simple` | claude | `timeout_seconds` | Ja (default) |
| `shell` | shell | `timeout_seconds` | Ja |
| `retry` | claude | `execution_mode`=shell/ai, `max_retries`, `delay`, `exponential_backoff`, `timeout_seconds` | Ja (wenn alle Fehlversuche) |
| `foreach` | claude | `execution_mode`=shell, `items_source`=list/command, `timeout_seconds` | Aggregiertes Ergebnis |
| `while` | claude | `execution_mode`=shell, `condition_command`, `timeout_seconds` | Ja bei innerem Fail |
| `conditional` | claude | `timeout_seconds` | Branch-spezifisch |
| `approval` | - | `mode`=ask/auto, `on_no`=fail/skip/continue | on_no=fail |
| `skill-capability` | claude | `params`, `input`, `timeout_seconds` | Ja |
| `sub-workflow` | depends | Tiefe<=5, Timeout 30 min | Ja bei Timeout/Zyklus |
| `agentic` | claude | Budget, Iterationen, Approval, `timeout_seconds` | Ja bei Stoppgründen |

## Konfiguration — *application.yml (relevant)*

### Execution Limits

`automation.execution.max-concurrent`: parallele Runs

`automation.host-claude.timeout-seconds`: CLI Timeout

`docker.container.idle-timeout-minutes`: Container Idle Stop

### Security

`automation.security.enabled` / `api-key`

`rate-limit` defaults disabled

CORS: `automation.cors.allowed-origins`

## Dateistruktur — *Kernklassen*

```text
ai-automation-backend/src/main/java/com/automation/
├── executor/
│   ├── ExecutionContext.java
│   ├── BlockExecutor.java
│   ├── blocks/ (Simple, Shell, Retry, ForEach, While, Conditional, Approval, SkillCapability, SubWorkflow, Agentic)
├── service/workflow/
│   ├── WorkflowExecutor.java
│   └── AgentInstructionsService.java
├── service/cli/
│   ├── CommandExecutorService.java
│   ├── ShellCommandService.java
│   └── CliSessionManager.java
└── service/ai/
    └── HostClaudeService.java
```
