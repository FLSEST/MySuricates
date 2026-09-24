<!-- Generated from docs/workflow-memory-diagrams.md and docs/i18n/workflow-memory-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# MySuricates — Workflow Memory

Architecture, data flow & file structure

## System Overview — *High-Level Architecture*

Workflow Memory extracts knowledge from past runs and provides it as context to later executions. It is an optional module (`memory`, in the presets `research` and `full`).

```mermaid
graph TB
    subgraph Browser["Browser (React SPA)"]
        WMP["WorkflowMemoryPanel.tsx"]
    end

    subgraph Backend["Backend (Spring Boot :9090)"]
        CTRL["WorkflowMemoryController"]
        AML["AutoMemoryExtractionListener"]
        SVC["WorkflowMemoryService"]
        PREP["ConversationPreprocessor"]
        CS["ClaudeSummarizer"]
        CXS["CodexSummarizer"]
    end

    subgraph Host["Host (CLI Tools)"]
        HCLAUDE["HostClaudeService"]
        HCODEX["HostCodexService"]
        CLAUDE_CLI["claude -p"]
        CODEX_CLI["codex exec"]
    end

    subgraph Storage["Persistence"]
        DB[("SQLite<br/>workflow_memory<br/>workflow_runs<br/>step_results")]
        FS["Filesystem<br/>~/.autotrigger/summarization/"]
    end

    WMP -->|"POST /extract"| CTRL
    CTRL --> SVC
    AML -->|"after successful runs"| SVC
    SVC --> PREP
    SVC -->|"provider=CLAUDE"| CS
    SVC -->|"provider=CODEX"| CXS
    CS --> HCLAUDE
    CXS --> HCODEX
    HCLAUDE --> CLAUDE_CLI
    HCODEX --> CODEX_CLI
    SVC -->|"load runs / save memory"| DB
    CLAUDE_CLI -->|"reads CLAUDE.md"| FS
    CODEX_CLI -->|"reads AGENTS.md"| FS

    style Browser fill:#1a1f2e,stroke:#30363d,color:#e6edf3
    style Backend fill:#1a1f2e,stroke:#30363d,color:#e6edf3
    style Host fill:#1a2e1a,stroke:#30363d,color:#e6edf3
    style Storage fill:#2e1a1a,stroke:#30363d,color:#e6edf3
```

## Data Flow: Extracting Knowledge — *POST /api/v1/workflows/{id}/memory/extract*

Step by step: read runs, condense, summarize, store. The AI call runs outside any transaction — between a short read and a short write, because the database has only one connection.

```mermaid
sequenceDiagram
    participant B as Browser
    participant C as Controller
    participant S as MemoryService
    participant P as Preprocessor
    participant SM as Summarizer<br/>(Claude/Codex)
    participant CLI as CLI on the host<br/>(claude/codex)
    participant DB as SQLite

    B->>C: POST /extract<br/>{provider, runIds?}
    C->>S: extractKnowledge()
    S->>DB: Load runs<br/>(last 10 or specific IDs)
    DB-->>S: List<WorkflowRun>
    S->>S: Collect StepResults
    S->>P: preprocess(steps)
    Note over P: Replace code blocks over 30 lines<br/>Replace data dumps over 20 lines<br/>Remove meta comments<br/>Merge repeated lines<br/>Condense agentic iterations
    P-->>S: preprocessed String
    S->>SM: summarize(preprocessed)
    SM->>CLI: ProcessBuilder.start()<br/>claude -p / codex exec
    Note over CLI: The CLI reads instructions<br/>from CLAUDE.md / AGENTS.md<br/>in the working directory
    CLI-->>SM: TITLE + TAGS + Markdown
    SM->>SM: parseResponse()
    SM-->>S: SummarizationResult
    S->>DB: Save WorkflowMemory (not active yet)
    S-->>C: WorkflowMemory
    C-->>B: 201 Created + JSON

    Note over C: Error handling:<br/>400 = unknown provider<br/>404 = unknown run ID<br/>422 = no runs/steps<br/>502 = CLI error/timeout
```

## Summarizer Architecture — *Provider Abstraction*

Both providers implement the same interface but use different CLIs following the same pattern.

```mermaid
classDiagram
    class KnowledgeSummarizer {
        <<interface>>
        +summarize(preprocessed: String) SummarizationResult
    }
    class SummarizationResult {
        +title: String
        +content: String
        +tags: List~String~
    }
    class ClaudeSummarizer {
        -hostClaudeService: HostClaudeService
        -setupService: SummarizationSetupService
        +summarize(preprocessed) SummarizationResult
        ~parseResponse(response) SummarizationResult
    }
    class CodexSummarizer {
        -hostCodexService: HostCodexService
        -setupService: SummarizationSetupService
        +summarize(preprocessed) SummarizationResult
        ~parseResponse(response) SummarizationResult
    }
    class HostClaudeService {
        +reason(prompt) String
        +reasonStructured(prompt, systemPrompt, workDir, maxTurns) ReasoningResult
    }
    class HostCodexService {
        +execute(prompt, workDir) String
        ~buildCommand(workDir, outputFile, readOnly, model) List~String~
    }
    class SummarizationSetupService {
        +init() void
        +getClaudeWorkDir() Path
        +getCodexWorkDir() Path
    }
    class WorkflowMemoryService {
        +extractKnowledge(workflowId, runIds, provider) WorkflowMemory
        +extractAndActivate(workflowId, provider) WorkflowMemory
        +writeActiveMemoryToProject(workflowId, projectPath) void
        -getSummarizer(provider) KnowledgeSummarizer
    }

    KnowledgeSummarizer <|.. ClaudeSummarizer
    KnowledgeSummarizer <|.. CodexSummarizer
    KnowledgeSummarizer --o SummarizationResult
    ClaudeSummarizer --> HostClaudeService
    ClaudeSummarizer --> SummarizationSetupService
    CodexSummarizer --> HostCodexService
    CodexSummarizer --> SummarizationSetupService
    WorkflowMemoryService --> ClaudeSummarizer
    WorkflowMemoryService --> CodexSummarizer
```

### Provider Comparison

| Property | ClaudeSummarizer | CodexSummarizer |
|---|---|---|
| CLI command | `claude -p` | `codex exec` (prompt via stdin) |
| Max turns | `1` (no tool use) | Default (exec mode) |
| Output | Parse stream JSON | `-o` into a temporary file |
| Instructions | `CLAUDE.md` | `AGENTS.md` |
| Timeout | `automation.host-claude.timeout-seconds` | `automation.summarization.timeout-seconds` |
| Auth | `~/.claude/` | `~/.codex/` |
| API keys needed? | No (CLI auth) | No (CLI auth) |

## Data Flow: Memory During Workflow Execution — *Feedback Loop*

Before the first block, the run writes the workflow's active memory into the project directory.

```mermaid
sequenceDiagram
    participant WE as WorkflowExecutor<br/>(ExecutionContextFactory)
    participant MS as MemoryService
    participant DB as SQLite
    participant FS as Filesystem
    participant CLI as AI CLI<br/>(Block Execution)

    WE->>MS: writeActiveMemoryToProject(wfId, projectPath)
    MS->>DB: findByWorkflowIdAndActiveTrue()
    alt memory exists
        DB-->>MS: WorkflowMemory
        MS->>FS: Write .claude/rules/project-memory.md
    else no active memory
        DB-->>MS: empty
        MS->>FS: Delete old project-memory.md
    end

    WE->>CLI: Start block execution
    Note over CLI: Claude CLI automatically reads<br/>all .claude/rules/*.md<br/>incl. project-memory.md,<br/>Codex and Pi get them<br/>appended to the system prompt

    CLI-->>WE: Result
    WE->>DB: Save WorkflowRun + StepResults

    Note over DB: Stored runs can later<br/>be used for new<br/>memory extraction
```

```mermaid
graph LR
    A["Run<br/>workflow"] --> B["Save runs + steps<br/>in the DB"]
    B --> C["Extract<br/>knowledge"]
    C --> D["Activate<br/>memory"]
    D --> E["Provide memory<br/>as context"]
    E --> A

    style A fill:#1a2e1a,stroke:#3fb950,color:#e6edf3
    style B fill:#1a1f2e,stroke:#58a6ff,color:#e6edf3
    style C fill:#2e1a2e,stroke:#bc8cff,color:#e6edf3
    style D fill:#2e2a1a,stroke:#d29922,color:#e6edf3
    style E fill:#1a2e1a,stroke:#3fb950,color:#e6edf3
```

Automatically: with `autoMemoryExtraction` in the workflow metadata (optionally with `interval`, default 5), the `AutoMemoryExtractionListener` extracts a new memory via Claude after a run with `COMPLETED` once that many runs have succeeded since the last memory, activates it and writes it into the project.

## Data Model — *SQLite Tables*

Entity-relationship diagram of the tables involved.

```mermaid
erDiagram
    workflows ||--o{ workflow_runs : "has many"
    workflows ||--o{ workflow_memory : "has many"
    workflow_runs ||--o{ step_results : "contains"
    workflow_memory }o--o{ workflow_runs : "extracted from"

    workflows {
        text id PK
        text name
        text description
        text instructions
        text model
    }

    workflow_runs {
        text id PK
        text workflow_id FK
        text status
        timestamp started_at
        timestamp completed_at
    }

    step_results {
        text id PK
        text workflow_run_id FK
        int step_index
        text input
        text output
        text status
        json metadata
    }

    workflow_memory {
        text id PK
        text workflow_id FK
        text title
        text content
        json tags
        json source_run_ids
        text provider
        boolean active
        timestamp created_at
    }
```

### workflow_memory

`provider`: CLAUDE or CODEX

`active`: at most 1 active per workflow

`source_run_ids`: JSON array of the run IDs used for the extraction

`content`: knowledge formatted as Markdown

### Relationships

A workflow has many runs and many memories.

A memory points via `source_run_ids` to the runs it was extracted from.

Activating a memory sets `active=false` for all other memories of the same workflow.

## File Structure — *Project + Runtime*

All files that belong to the Workflow Memory feature.

```text
ai-automation-backend/src/main/java/com/automation/
├── config/
│   ├── SummarizationConfig.java          ← base-dir + timeout (automation.summarization)
│   └── HostClaudeConfig.java             ← timeout of the Claude CLI
│
├── controller/
│   └── WorkflowMemoryController.java     ← REST API, errors 400/422/502
│
├── dto/
│   ├── request/
│   │   ├── ExtractMemoryRequest.java     ← {provider, runIds?}
│   │   └── UpdateMemoryRequest.java      ← {title, content}
│   └── response/
│       └── WorkflowMemoryResponse.java
│
├── entity/
│   └── WorkflowMemory.java               ← JPA entity
│
├── mapper/
│   └── WorkflowMemoryMapper.java         ← MapStruct entity ↔ DTO
│
├── repository/
│   └── WorkflowMemoryRepository.java     ← Spring Data JPA
│
├── service/ai/
│   ├── HostClaudeService.java            ← reasonStructured(…, workDir, maxTurns)
│   ├── HostCodexService.java             ← codex exec wrapper
│   └── RunContextFiles.java              ← .claude/rules/project-memory.md, appended for Codex/Pi
│
└── service/memory/
    ├── KnowledgeSummarizer.java          ← interface + SummarizationResult
    ├── ClaudeSummarizer.java             ← claude -p, --max-turns 1, work dir
    ├── CodexSummarizer.java              ← codex exec, work dir
    ├── ConversationPreprocessor.java     ← rule-based compression
    ├── SummarizationSetupService.java    ← work dirs + CLAUDE.md/AGENTS.md at startup
    ├── AutoMemoryExtractionListener.java ← automatic extraction after successful runs
    └── WorkflowMemoryService.java        ← orchestration, getSummarizer(), writeActiveMemoryToProject()

ai-automation-backend/src/main/resources/
├── agent-instructions.md                 ← base instructions (block execution)
├── application.yml                       ← automation.summarization.base-dir / timeout-seconds
├── db/migration/
│   └── V16__create_workflow_memory_table.sql
└── summarization/
    ├── claude-summarizer.md              ← template → CLAUDE.md
    └── codex-summarizer.md               ← template → AGENTS.md

ai-automation-backend/src/test/java/com/automation/
├── service/ai/
│   ├── HostClaudeServiceTest.java
│   └── HostCodexServiceTest.java
└── service/memory/
    ├── AutoMemoryExtractionListenerTest.java
    ├── ClaudeSummarizerTest.java
    ├── CodexSummarizerTest.java
    ├── ConversationPreprocessorTest.java
    ├── KnowledgeSummarizerTest.java
    ├── SummarizationSetupServiceTest.java
    └── WorkflowMemoryServiceTest.java

ai-automation-frontend/src/
├── components/workflow/
│   └── WorkflowMemoryPanel.tsx           ← provider CLAUDE or CODEX
├── hooks/
│   └── useWorkflowMemory.ts              ← TanStack Query hooks
└── types/
    └── index.ts                          ← WorkflowMemory, ExtractMemoryRequest

~/.autotrigger/summarization/             ← runtime (created at backend start)
├── claude/
│   └── CLAUDE.md                         ← copy of claude-summarizer.md
└── codex/
    └── AGENTS.md                         ← copy of codex-summarizer.md

docs/
└── workflow-memory-architecture.md
```

## API Endpoints — */api/v1/workflows/{id}/memory*

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/memory` | All memories of a workflow, newest first | `200` `List<WorkflowMemoryResponse>` |
| `POST` | `/memory/extract` | Extract knowledge (without `provider`: CLAUDE) | `201` / `400` / `404` / `422` / `502` |
| `PUT` | `/memory/{memId}` | Update title/content | `200` `WorkflowMemoryResponse` |
| `PUT` | `/memory/{memId}/activate` | Activate memory (deactivates the others) | `204` |
| `DELETE` | `/memory/{memId}` | Delete memory | `204` |

### Error Responses (POST /extract)

| Status | Cause | Body |
|---|---|---|
| `400` | Unknown provider | `{"error": "Unknown provider: X. Available: [CLAUDE, CODEX]"}` |
| `404` | Unknown run ID in `runIds` | `{"code": "RESOURCE_NOT_FOUND", ...}` |
| `422` | No runs or steps | `{"error": "No runs found..."}` |
| `502` | CLI timeout / crash | `{"error": "Knowledge extraction failed: ..."}` |

## Configuration — *application.yml*

```mermaid
graph LR
    subgraph YAML["application.yml"]
        S["automation.summarization"]
        S --> BD["base-dir<br/><i>~/.autotrigger/summarization</i>"]
        S --> TO["timeout-seconds<br/><i>120</i>"]
    end
    subgraph ENV["Environment Variables"]
        E1["AUTOMATION_SUMMARIZATION_DIR"]
        E2["AUTOMATION_SUMMARIZATION_TIMEOUT"]
    end
    E1 -.->|"overrides"| BD
    E2 -.->|"overrides"| TO

    style YAML fill:#1a1f2e,stroke:#30363d,color:#e6edf3
    style ENV fill:#2e2a1a,stroke:#30363d,color:#e6edf3
```

`timeout-seconds` limits the Codex call and can be changed at runtime in the settings (`summarization-timeout-seconds`); the Claude call follows `automation.host-claude.timeout-seconds`.

### Prerequisites

- For the provider CLAUDE: `claude` CLI installed and logged in (`~/.claude/`)
- For the provider CODEX: `codex` CLI installed and logged in (`~/.codex/`)
- No API keys needed in the app configuration

### Startup

- `SummarizationSetupService.init()` runs at backend start
- Creates the working directories if they do not exist
- Copies the instruction templates from the resources into the directories
- Overwrites them on every start (latest bundled version)
