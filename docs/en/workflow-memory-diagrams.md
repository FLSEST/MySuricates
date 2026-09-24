<!-- Generated from docs/workflow-memory-diagrams.md and docs/i18n/workflow-memory-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# AutoPilot — Workflow Memory

Architecture, data flow & file structure

## System Overview — *High-Level Architecture*

The Workflow Memory feature extracts knowledge from past runs and provides it as context for future executions.

```mermaid
graph TB
    subgraph Browser["Browser (React SPA)"]
        WMP["WorkflowMemoryPanel.tsx"]
    end

    subgraph Backend["Backend (Spring Boot :9090)"]
        CTRL["WorkflowMemoryController"]
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
        DB[("SQLite<br/>workflow_memory<br/>workflow_run")]
        FS["Filesystem<br/>~/.autotrigger/summarization/"]
    end

    WMP -->|"POST /extract"| CTRL
    CTRL --> SVC
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

Step-by-step flow of extracting knowledge from workflow runs.

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
    Note over P: Compress code blocks<br/>Compress data dumps<br/>Remove meta comments<br/>Merge repeated errors<br/>Reduction: 50-70%
    P-->>S: preprocessed String
    S->>SM: summarize(preprocessed)
    SM->>CLI: ProcessBuilder.start()<br/>claude -p / codex exec
    Note over CLI: The CLI reads instructions<br/>from CLAUDE.md / AGENTS.md<br/>in the working directory
    CLI-->>SM: TITLE + TAGS + Markdown
    SM->>SM: parseResponse()
    SM-->>S: SummarizationResult
    S->>DB: Save WorkflowMemory
    S-->>C: WorkflowMemory
    C-->>B: 201 Created + JSON

    Note over C: Error handling:<br/>400 = unknown provider<br/>422 = no runs/steps<br/>502 = CLI error/timeout
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
        +parseResponse(response) SummarizationResult
    }
    class CodexSummarizer {
        -hostCodexService: HostCodexService
        -setupService: SummarizationSetupService
        +summarize(preprocessed) SummarizationResult
        +parseResponse(response) SummarizationResult
    }
    class HostClaudeService {
        +reason(prompt) String
        +reasonStructured(prompt, systemPrompt, workDir, maxTurns) ReasoningResult
    }
    class HostCodexService {
        +execute(prompt, workDir) String
        +buildCommand(prompt, workDir, outputFile) List~String~
    }
    class SummarizationSetupService {
        +init() void
        +getClaudeWorkDir() Path
        +getCodexWorkDir() Path
    }
    class WorkflowMemoryService {
        +extractKnowledge(workflowId, runIds, provider) WorkflowMemory
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
| CLI command | `claude -p` | `codex exec` |
| Max turns | `1` (no tool use) | Default (exec mode) |
| Output | Parse stream JSON | `-o result.txt` |
| Instructions | `CLAUDE.md` | `AGENTS.md` |
| Auth | `~/.claude/` | `~/.codex/` |
| API keys needed? | No (CLI auth) | No (CLI auth) |

## Data Flow: Memory During Workflow Execution — *Feedback Loop*

Activated memories are written into the project directory automatically before a workflow runs.

```mermaid
sequenceDiagram
    participant WE as WorkflowExecutor
    participant MS as MemoryService
    participant DB as SQLite
    participant FS as Filesystem
    participant CLI as Claude CLI<br/>(Block Execution)

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
    Note over CLI: Claude CLI automatically reads<br/>all .claude/rules/*.md<br/>incl. project-memory.md<br/>as additional context

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

## Data Model — *SQLite Tables*

Entity-relationship diagram of the tables involved.

```mermaid
erDiagram
    WORKFLOW ||--o{ WORKFLOW_RUN : "has many"
    WORKFLOW ||--o{ WORKFLOW_MEMORY : "has many"
    WORKFLOW_RUN ||--o{ STEP_RESULT : "contains"
    WORKFLOW_MEMORY }o--o{ WORKFLOW_RUN : "extracted from"

    WORKFLOW {
        text id PK
        text name
        text description
        text instructions
        text model
    }

    WORKFLOW_RUN {
        text id PK
        text workflow_id FK
        text status
        timestamp started_at
        timestamp finished_at
    }

    STEP_RESULT {
        int step_index
        text input
        text output
        text status
        json metadata
    }

    WORKFLOW_MEMORY {
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

All files that belong to the Workflow Memory feature. `[NEU]` (new) = created in this refactoring, `[ENTFERNT]` (removed) = deleted.

```text
ai-automation-backend/src/main/java/com/automation/
├── config/
│   ├── SummarizationConfig.java          ← Base-Dir + Timeout Config [NEU]
│   ├── HostClaudeConfig.java             ← Timeout fuer Claude CLI (besteht)
│   └── KnowledgeConfig.java              ← OpenAI API-Key Config [ENTFERNT]
│
├── controller/
│   └── WorkflowMemoryController.java     ← REST API, Error Handling 400/422/502
│
├── dto/
│   ├── request/
│   │   └── ExtractMemoryRequest.java     ← {provider, runIds?}
│   └── response/
│       └── WorkflowMemoryResponse.java   ← DTO fuer Frontend
│
├── entity/
│   └── WorkflowMemory.java               ← JPA Entity
│
├── mapper/
│   └── WorkflowMemoryMapper.java         ← MapStruct Entity ↔ DTO
│
├── repository/
│   └── WorkflowMemoryRepository.java     ← Spring Data JPA
│
├── service/ai/
│   ├── HostClaudeService.java            ← +reasonStructured(workDir, maxTurns)
│   └── HostCodexService.java             ← codex exec Wrapper [NEU]
│
└── service/memory/
    ├── KnowledgeSummarizer.java          ← Interface + SummarizationResult
    ├── ClaudeSummarizer.java             ← claude -p, --max-turns 1, Work Dir
    ├── CodexSummarizer.java              ← codex exec, Work Dir [NEU]
    ├── OpenAiSummarizer.java             ← REST + API-Key [ENTFERNT]
    ├── ConversationPreprocessor.java     ← Regelbasierte Kompression
    ├── SummarizationSetupService.java    ← Work Dirs + .md Setup bei Start [NEU]
    └── WorkflowMemoryService.java        ← Orchestrierung, getSummarizer()

ai-automation-backend/src/main/resources/
├── agent-instructions.md                 ← Base Instructions (Block-Execution)
├── application.yml                       ← +summarization.base-dir/timeout
├── db/migration/
│   └── V16__create_workflow_memory_table.sql
└── summarization/                        [NEU]
    ├── claude-summarizer.md              ← Template → CLAUDE.md
    └── codex-summarizer.md               ← Template → AGENTS.md

ai-automation-backend/src/test/java/com/automation/
├── service/ai/
│   ├── HostClaudeServiceTest.java        ← +customMaxTurns Test
│   └── HostCodexServiceTest.java         [NEU]
└── service/memory/
    ├── ClaudeSummarizerTest.java         ← Angepasst an WorkDir
    ├── CodexSummarizerTest.java          [NEU]
    ├── ConversationPreprocessorTest.java
    └── WorkflowMemoryServiceTest.java    ← CodexSummarizer statt OpenAi

ai-automation-frontend/src/
├── components/workflow/
│   └── WorkflowMemoryPanel.tsx           ← Provider: OPENAI → CODEX
├── hooks/
│   └── useWorkflowMemory.ts              ← TanStack Query Hooks
└── types/
    └── index.ts                          ← WorkflowMemory, ExtractMemoryRequest

~/.autotrigger/summarization/             ← Runtime (erstellt bei Backend-Start)
├── claude/
│   └── CLAUDE.md                         ← Kopie von claude-summarizer.md
└── codex/
    └── AGENTS.md                         ← Kopie von codex-summarizer.md

docs/
└── workflow-memory-architecture.md       [NEU]
```

## API Endpoints — */api/v1/workflows/{id}/memory*

| Method | Path | Description | Response |
|---|---|---|---|
| `GET` | `/memory` | All memories of a workflow | `200` List<WorkflowMemoryResponse> |
| `POST` | `/memory/extract` | Extract knowledge (~30-60s) | `201` / `400` / `422` / `502` |
| `PUT` | `/memory/{memId}` | Update title/content | `200` WorkflowMemoryResponse |
| `PUT` | `/memory/{memId}/activate` | Activate memory (deactivates the others) | `200` |
| `DELETE` | `/memory/{memId}` | Delete memory | `204` |

### Error Responses (POST /extract)

| Status | Cause | Body |
|---|---|---|
| `400` | Unknown provider | `{"error": "Unknown provider: X"}` |
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

### Prerequisites

- `claude` CLI installed and authenticated (`~/.claude/`)
- `codex` CLI installed and authenticated (`~/.codex/`)
- No API keys needed in the app configuration

### Startup

- `SummarizationSetupService.init()` runs at backend start
- Creates the working directories if they do not exist
- Copies the instruction templates from the resources into the directories
- Overwrites them on every start (latest bundled version)
