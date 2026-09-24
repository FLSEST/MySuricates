<!-- Generated from docs/workflow-engine-diagrams.md and docs/i18n/workflow-engine-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# MySuricates — Workflow Engine

Core logic, execution modes (with/without AI) and control flows

## System Overview — *High-Level*

All workflows run through the WorkflowExecutor. It orchestrates BlockExecutors, container start, skill context and WebSocket logs.

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
        CON["Docker Container (project)"]
        HOST["Host (fallback)"]
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

## Execution (Default, AI-Assisted) — *Reason/Act*

Default: commands go to the Claude CLI (host/container). Instruction prefix + model come from the workflow.

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

## Execution (Without AI) — *Shell Paths*

Shell mode uses no models and no instruction prefixes; it only evaluates the exit code.

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

### When Shell?

- Block type `shell`
- `execution_mode=shell` for foreach/while/retry

### Differences

- No model / no prompt limit
- Deterministic through the exit code
- No token metadata

## Agentic Loop — *Reason–Act–Observe*

Multi-step AI planning with token and time budgets, a repetition stop and optional approval.

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

Branching and user gates within a run.

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

## Provider & Execution Modes — *AI vs. No AI*

| Aspect | AI (Claude) | Without AI (Shell) |
|---|---|---|
| CLI | `claude -p` | System shell |
| Context | Instruction prefix, skill context, previous_output | Shell command string only |
| Determinism | Lower (LLM) | High (exit code) |
| Telemetry | Tokens/cost in StepResult.metadata | exitCode + duration |
| Fallback | Host, if the container does not start | Host, same path |

## Block Matrix — *Type → Exec Mode*

| Block | Default | Options | Stop on failure |
|---|---|---|---|
| `simple` | claude | `timeout_seconds` | Yes (default) |
| `shell` | shell | `timeout_seconds` | Yes |
| `retry` | claude | `execution_mode`=shell/ai, `max_retries`, `delay`, `exponential_backoff`, `timeout_seconds` | Yes (when all attempts fail) |
| `foreach` | claude | `execution_mode`=shell, `items_source`=list/command, `timeout_seconds` | Aggregated result |
| `while` | claude | `execution_mode`=shell, `condition_command`, `timeout_seconds` | Yes, on an inner failure |
| `conditional` | claude | `timeout_seconds` | Branch-specific |
| `approval` | - | `mode`=ask/auto, `on_no`=fail/skip/continue | on_no=fail |
| `skill-capability` | claude | `params`, `input`, `timeout_seconds` | Yes |
| `sub-workflow` | depends | Depth<=5, timeout 30 min | Yes, on timeout/cycle |
| `agentic` | claude | Budget, iterations, approval, `timeout_seconds` | Yes, on stop reasons |

## Configuration — *application.yml (relevant)*

### Execution Limits

`automation.execution.max-concurrent`: parallel runs

`automation.host-claude.timeout-seconds`: CLI timeout

`docker.container.idle-timeout-minutes`: container idle stop

### Security

`automation.security.enabled` / `api-key`

`rate-limit` defaults disabled

CORS: `automation.cors.allowed-origins`

## File Structure — *Core Classes*

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
