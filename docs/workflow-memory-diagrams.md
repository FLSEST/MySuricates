# MySuricates — Workflow Memory

Architektur, Datenfluss & Dateistruktur

## Systemuebersicht — *High-Level Architektur*

Das Workflow Memory Feature extrahiert Wissen aus vergangenen Runs und stellt es bei zukuenftigen Ausfuehrungen als Kontext bereit.

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

    subgraph Storage["Persistenz"]
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
    SVC -->|"Runs laden / Memory speichern"| DB
    CLAUDE_CLI -->|"liest CLAUDE.md"| FS
    CODEX_CLI -->|"liest AGENTS.md"| FS

    style Browser fill:#1a1f2e,stroke:#30363d,color:#e6edf3
    style Backend fill:#1a1f2e,stroke:#30363d,color:#e6edf3
    style Host fill:#1a2e1a,stroke:#30363d,color:#e6edf3
    style Storage fill:#2e1a1a,stroke:#30363d,color:#e6edf3
```

## Datenfluss: Wissen extrahieren — *POST /api/v1/workflows/{id}/memory/extract*

Schritt-fuer-Schritt Ablauf der Wissensextraktion aus Workflow-Runs.

```mermaid
sequenceDiagram
    participant B as Browser
    participant C as Controller
    participant S as MemoryService
    participant P as Preprocessor
    participant SM as Summarizer<br/>(Claude/Codex)
    participant CLI as CLI auf Host<br/>(claude/codex)
    participant DB as SQLite

    B->>C: POST /extract<br/>{provider, runIds?}
    C->>S: extractKnowledge()
    S->>DB: Runs laden<br/>(letzte 10 oder spezifische IDs)
    DB-->>S: List<WorkflowRun>
    S->>S: StepResults sammeln
    S->>P: preprocess(steps)
    Note over P: Code-Bloecke komprimieren<br/>Data-Dumps komprimieren<br/>Meta-Kommentare entfernen<br/>Wiederholte Fehler zusammenfassen<br/>Reduktion: 50-70%
    P-->>S: preprocessed String
    S->>SM: summarize(preprocessed)
    SM->>CLI: ProcessBuilder.start()<br/>claude -p / codex exec
    Note over CLI: CLI liest Instruktionen<br/>aus CLAUDE.md / AGENTS.md<br/>im Working Directory
    CLI-->>SM: TITLE + TAGS + Markdown
    SM->>SM: parseResponse()
    SM-->>S: SummarizationResult
    S->>DB: WorkflowMemory speichern
    S-->>C: WorkflowMemory
    C-->>B: 201 Created + JSON

    Note over C: Error Handling:<br/>400 = Unbekannter Provider<br/>422 = Keine Runs/Steps<br/>502 = CLI Fehler/Timeout
```

## Summarizer-Architektur — *Provider-Abstraktion*

Beide Provider implementieren dasselbe Interface, nutzen aber unterschiedliche CLIs mit dem gleichen Pattern.

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

### Provider-Vergleich

| Eigenschaft | ClaudeSummarizer | CodexSummarizer |
|---|---|---|
| CLI-Befehl | `claude -p` | `codex exec` |
| Max Turns | `1` (kein Tool-Use) | Default (exec mode) |
| Output | Stream-JSON parsen | `-o result.txt` |
| Instruktionen | `CLAUDE.md` | `AGENTS.md` |
| Auth | `~/.claude/` | `~/.codex/` |
| API Keys noetig? | Nein (CLI Auth) | Nein (CLI Auth) |

## Datenfluss: Memory bei Workflow-Ausfuehrung — *Feedback-Loop*

Aktivierte Memories werden automatisch in das Projektverzeichnis geschrieben, bevor ein Workflow ausgefuehrt wird.

```mermaid
sequenceDiagram
    participant WE as WorkflowExecutor
    participant MS as MemoryService
    participant DB as SQLite
    participant FS as Filesystem
    participant CLI as Claude CLI<br/>(Block Execution)

    WE->>MS: writeActiveMemoryToProject(wfId, projectPath)
    MS->>DB: findByWorkflowIdAndActiveTrue()
    alt Memory vorhanden
        DB-->>MS: WorkflowMemory
        MS->>FS: Schreibe .claude/rules/project-memory.md
    else Keine aktive Memory
        DB-->>MS: empty
        MS->>FS: Loesche alte project-memory.md
    end

    WE->>CLI: Block-Execution starten
    Note over CLI: Claude CLI liest automatisch<br/>alle .claude/rules/*.md<br/>inkl. project-memory.md<br/>als zusaetzlichen Kontext

    CLI-->>WE: Ergebnis
    WE->>DB: WorkflowRun + StepResults speichern

    Note over DB: Gespeicherte Runs koennen<br/>spaeter fuer neue<br/>Memory-Extraktion genutzt werden
```

```mermaid
graph LR
    A["Workflow<br/>ausfuehren"] --> B["Runs + Steps<br/>in DB speichern"]
    B --> C["Wissen<br/>extrahieren"]
    C --> D["Memory<br/>aktivieren"]
    D --> E["Memory als<br/>Kontext bereitstellen"]
    E --> A

    style A fill:#1a2e1a,stroke:#3fb950,color:#e6edf3
    style B fill:#1a1f2e,stroke:#58a6ff,color:#e6edf3
    style C fill:#2e1a2e,stroke:#bc8cff,color:#e6edf3
    style D fill:#2e2a1a,stroke:#d29922,color:#e6edf3
    style E fill:#1a2e1a,stroke:#3fb950,color:#e6edf3
```

## Datenmodell — *SQLite Tabellen*

Entity-Relationship-Diagramm der beteiligten Tabellen.

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

`provider`: CLAUDE oder CODEX

`active`: Maximal 1 pro Workflow aktiv

`source_run_ids`: JSON-Array der Run-IDs die zur Extraktion genutzt wurden

`content`: Markdown-formatiertes Wissen

### Beziehungen

Ein Workflow hat viele Runs und viele Memories.

Eine Memory verweist ueber `source_run_ids` auf die Runs, aus denen sie extrahiert wurde.

Beim Aktivieren wird `active=false` fuer alle anderen Memories desselben Workflows gesetzt.

## Dateistruktur — *Projekt + Runtime*

Alle Dateien die zum Workflow Memory Feature gehoeren. `[NEU]` = in diesem Refactoring erstellt, `[ENTFERNT]` = geloescht.

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

## API-Endpunkte — */api/v1/workflows/{id}/memory*

| Methode | Pfad | Beschreibung | Response |
|---|---|---|---|
| `GET` | `/memory` | Alle Memories eines Workflows | `200` List<WorkflowMemoryResponse> |
| `POST` | `/memory/extract` | Wissen extrahieren (~30-60s) | `201` / `400` / `422` / `502` |
| `PUT` | `/memory/{memId}` | Title/Content aktualisieren | `200` WorkflowMemoryResponse |
| `PUT` | `/memory/{memId}/activate` | Memory aktivieren (deaktiviert andere) | `200` |
| `DELETE` | `/memory/{memId}` | Memory loeschen | `204` |

### Error Responses (POST /extract)

| Status | Ursache | Body |
|---|---|---|
| `400` | Unbekannter Provider | `{"error": "Unknown provider: X"}` |
| `422` | Keine Runs oder Steps | `{"error": "No runs found..."}` |
| `502` | CLI Timeout / Crash | `{"error": "Knowledge extraction failed: ..."}` |

## Konfiguration — *application.yml*

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
    E1 -.->|"ueberschreibt"| BD
    E2 -.->|"ueberschreibt"| TO

    style YAML fill:#1a1f2e,stroke:#30363d,color:#e6edf3
    style ENV fill:#2e2a1a,stroke:#30363d,color:#e6edf3
```

### Voraussetzungen

- `claude` CLI installiert und authentifiziert (`~/.claude/`)
- `codex` CLI installiert und authentifiziert (`~/.codex/`)
- Keine API-Keys in der App-Konfiguration noetig

### Startup

- `SummarizationSetupService.init()` laeuft bei Backend-Start
- Erstellt Working Directories falls nicht vorhanden
- Kopiert Instruktions-Templates aus Resources in die Directories
- Ueberschreibt bei jedem Start (latest bundled version)
