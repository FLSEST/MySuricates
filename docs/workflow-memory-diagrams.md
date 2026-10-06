# MySuricates — Workflow Memory

Architektur, Datenfluss & Dateistruktur

## Systemübersicht — *High-Level Architektur*

Workflow Memory extrahiert Wissen aus vergangenen Runs und stellt es späteren Ausführungen als Kontext bereit. Es ist ein optionales Modul (`memory`, in den Presets `research` und `full`).

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

    subgraph Storage["Persistenz"]
        DB[("SQLite<br/>workflow_memory<br/>workflow_runs<br/>step_results")]
        FS["Filesystem<br/>~/.mysuricates/summarization/"]
    end

    WMP -->|"POST /extract"| CTRL
    CTRL --> SVC
    AML -->|"nach erfolgreichen Runs"| SVC
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

Schritt für Schritt: Runs lesen, verdichten, zusammenfassen, speichern. Der KI-Aufruf läuft außerhalb jeder Transaktion — zwischen einem kurzen Lese- und einem kurzen Schreibvorgang, weil die Datenbank nur eine Verbindung hat.

```mermaid
sequenceDiagram
    participant B as Browser
    participant C as Controller
    participant S as WorkflowMemoryService
    participant P as Preprocessor
    participant SM as Summarizer<br/>(Claude/Codex)
    participant CLI as CLI auf Host<br/>(claude/codex)
    participant DB as SQLite

    B->>C: POST /extract<br/>{provider, runIds?}
    C->>S: extractKnowledge()
    S->>DB: Runs laden<br/>(letzte 10 oder spezifische IDs)
    DB-->>S: List<WorkflowRun>
    S->>S: Schritte (StepResult) sammeln
    S->>P: preprocess(steps)
    Note over P: Code-Blöcke über 30 Zeilen ersetzen<br/>Data-Dumps über 20 Zeilen ersetzen<br/>Meta-Kommentare entfernen<br/>Wiederholte Zeilen zusammenfassen<br/>Agentic-Iterationen verdichten
    P-->>S: preprocessed String
    S->>SM: summarize(preprocessed)
    SM->>CLI: ProcessBuilder.start()<br/>claude -p / codex exec
    Note over CLI: CLI liest Instruktionen<br/>aus CLAUDE.md / AGENTS.md<br/>im Working Directory
    CLI-->>SM: TITLE + TAGS + Markdown
    SM->>SM: parseResponse()
    SM-->>S: SummarizationResult
    S->>DB: WorkflowMemory speichern (noch inaktiv)
    S-->>C: WorkflowMemory
    C-->>B: 201 Created + JSON

    Note over C: Error Handling:<br/>400 = Unbekannter Provider<br/>404 = Unbekannte Run-ID<br/>422 = Keine Runs/Steps<br/>502 = CLI Fehler/Timeout
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

### Provider-Vergleich

| Eigenschaft | ClaudeSummarizer | CodexSummarizer |
|---|---|---|
| CLI-Befehl | `claude -p` | `codex exec` (Prompt über stdin) |
| Max Turns | `1` (kein Tool-Use) | Default (exec mode) |
| Output | Stream-JSON parsen | `-o` in eine temporäre Datei |
| Instruktionen | `CLAUDE.md` | `AGENTS.md` |
| Timeout | `automation.host-claude.timeout-seconds` | `automation.summarization.timeout-seconds` |
| Auth | `~/.claude/` | `~/.codex/` |
| API Keys nötig? | Nein (CLI Auth) | Nein (CLI Auth) |

## Datenfluss: Memory bei Workflow-Ausführung — *Feedback-Loop*

Die aktive Memory eines Workflows schreibt der Run vor dem ersten Block in das Projektverzeichnis.

```mermaid
sequenceDiagram
    participant WE as WorkflowExecutor<br/>(ExecutionContextFactory)
    participant MS as WorkflowMemoryService
    participant DB as SQLite
    participant FS as Filesystem
    participant CLI as KI-CLI<br/>(Block Execution)

    WE->>MS: writeActiveMemoryToProject(wfId, projectPath)
    MS->>DB: findByWorkflowIdAndActiveTrue()
    alt Memory vorhanden
        DB-->>MS: WorkflowMemory
        MS->>FS: Schreibe .claude/rules/project-memory.md
    else Keine aktive Memory
        DB-->>MS: empty
        MS->>FS: Lösche alte project-memory.md
    end

    WE->>CLI: Block-Execution starten
    Note over CLI: Claude CLI liest automatisch<br/>alle .claude/rules/*.md<br/>inkl. project-memory.md,<br/>Codex und Pi bekommen sie<br/>an den System-Prompt gehängt

    CLI-->>WE: Ergebnis
    WE->>DB: WorkflowRun + Schritte (StepResult) speichern

    Note over DB: Gespeicherte Runs können<br/>später für neue<br/>Memory-Extraktion genutzt werden
```

```mermaid
graph LR
    A["Workflow<br/>ausführen"] --> B["Runs + Steps<br/>in DB speichern"]
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

Automatisch: Mit `autoMemoryExtraction` in den Workflow-Metadaten (optional mit `interval`, Standard 5) extrahiert der `AutoMemoryExtractionListener` nach einem Run mit `COMPLETED`, sobald seit der letzten Memory so viele Runs erfolgreich waren, per Claude eine neue Memory, aktiviert sie und schreibt sie ins Projekt.

## Datenmodell — *SQLite Tabellen*

Entity-Relationship-Diagramm der beteiligten Tabellen.

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

`provider`: CLAUDE oder CODEX

`active`: Maximal 1 pro Workflow aktiv

`source_run_ids`: JSON-Array der Run-IDs, die zur Extraktion genutzt wurden

`content`: Markdown-formatiertes Wissen

### Beziehungen

Ein Workflow hat viele Runs und viele Memories.

Eine Memory verweist über `source_run_ids` auf die Runs, aus denen sie extrahiert wurde.

Beim Aktivieren wird `active=false` für alle anderen Memories desselben Workflows gesetzt.

## Dateistruktur — *Projekt + Runtime*

Die wichtigsten Dateien des Workflow-Memory-Features. Tests liegen unter `ai-automation-backend/src/test/java/com/automation/`, meist im Paket der getesteten Klasse.

```text
ai-automation-backend/src/main/java/com/automation/
├── config/
│   ├── SummarizationConfig.java          ← base-dir + Timeout (automation.summarization)
│   └── HostClaudeConfig.java             ← Timeout der Claude-CLI
│
├── controller/
│   └── WorkflowMemoryController.java     ← REST-API, Fehler 400/422/502
│
├── dto/
│   ├── request/
│   │   ├── ExtractMemoryRequest.java     ← {provider, runIds?}
│   │   └── UpdateMemoryRequest.java      ← {title, content}
│   └── response/
│       └── WorkflowMemoryResponse.java
│
├── entity/
│   └── WorkflowMemory.java               ← JPA-Entity
│
├── mapper/
│   └── WorkflowMemoryMapper.java         ← MapStruct Entity ↔ DTO
│
├── repository/
│   └── WorkflowMemoryRepository.java     ← Spring Data JPA
│
├── service/ai/
│   ├── HostClaudeService.java            ← reasonStructured(…, workDir, maxTurns)
│   ├── HostCodexService.java             ← Hülle um codex exec
│   └── RunContextFiles.java              ← .claude/rules/project-memory.md, für Codex/Pi angehängt
│
└── service/memory/
    ├── KnowledgeSummarizer.java          ← Interface + SummarizationResult
    ├── ClaudeSummarizer.java             ← claude -p, --max-turns 1, Arbeitsverzeichnis
    ├── CodexSummarizer.java              ← codex exec, Arbeitsverzeichnis
    ├── ConversationPreprocessor.java     ← regelbasierte Verdichtung
    ├── SummarizationSetupService.java    ← Arbeitsverzeichnisse + CLAUDE.md/AGENTS.md beim Start
    ├── AutoMemoryExtractionListener.java ← automatische Extraktion nach erfolgreichen Runs
    └── WorkflowMemoryService.java        ← Ablaufsteuerung, getSummarizer(), writeActiveMemoryToProject()

ai-automation-backend/src/main/resources/
├── agent-instructions.md                 ← Basis-Anweisungen (Block-Ausführung)
├── application.yml                       ← automation.summarization.base-dir / timeout-seconds
├── db/migration/
│   └── V16__create_workflow_memory_table.sql
└── summarization/
    ├── claude-summarizer.md              ← Vorlage → CLAUDE.md
    └── codex-summarizer.md               ← Vorlage → AGENTS.md

ai-automation-frontend/src/
├── components/workflow/
│   └── WorkflowMemoryPanel.tsx           ← Anbieter CLAUDE oder CODEX
├── hooks/
│   └── useWorkflowMemory.ts              ← TanStack-Query-Hooks
└── types/
    └── index.ts                          ← WorkflowMemory, ExtractMemoryRequest

~/.mysuricates/summarization/             ← Laufzeit (beim Backend-Start angelegt)
├── claude/
│   └── CLAUDE.md                         ← Kopie von claude-summarizer.md
└── codex/
    └── AGENTS.md                         ← Kopie von codex-summarizer.md
```

## API-Endpunkte — */api/v1/workflows/{id}/memory*

| Methode | Pfad | Beschreibung | Response |
|---|---|---|---|
| `GET` | `/memory` | Alle Memories eines Workflows, neueste zuerst | `200` `List<WorkflowMemoryResponse>` |
| `POST` | `/memory/extract` | Wissen extrahieren (ohne `provider`: CLAUDE) | `201` / `400` / `404` / `422` / `502` |
| `PUT` | `/memory/{memId}` | Title/Content aktualisieren | `200` `WorkflowMemoryResponse` |
| `PUT` | `/memory/{memId}/activate` | Memory aktivieren (deaktiviert andere) | `204` |
| `DELETE` | `/memory/{memId}` | Memory löschen | `204` |

### Error Responses (POST /extract)

| Status | Ursache | Body |
|---|---|---|
| `400` | Unbekannter Provider | `{"error": "Unknown provider: X. Available: [CLAUDE, CODEX]"}` |
| `404` | Unbekannte Run-ID in `runIds` | `{"code": "RESOURCE_NOT_FOUND", ...}` |
| `422` | Keine Runs oder Steps | `{"error": "No runs found..."}` |
| `502` | CLI Timeout / Crash | `{"error": "Knowledge extraction failed: ..."}` |

## Konfiguration — *application.yml*

```mermaid
graph LR
    subgraph YAML["application.yml"]
        S["automation.summarization"]
        S --> BD["base-dir<br/><i>~/.mysuricates/summarization</i>"]
        S --> TO["timeout-seconds<br/><i>120</i>"]
    end
    subgraph ENV["Environment Variables"]
        E1["AUTOMATION_SUMMARIZATION_DIR"]
        E2["AUTOMATION_SUMMARIZATION_TIMEOUT"]
    end
    E1 -.->|"überschreibt"| BD
    E2 -.->|"überschreibt"| TO

    style YAML fill:#1a1f2e,stroke:#30363d,color:#e6edf3
    style ENV fill:#2e2a1a,stroke:#30363d,color:#e6edf3
```

`timeout-seconds` begrenzt den Codex-Aufruf und lässt sich zur Laufzeit in den Einstellungen ändern (`summarization-timeout-seconds`); der Claude-Aufruf folgt `automation.host-claude.timeout-seconds`.

### Voraussetzungen

- Für den Provider CLAUDE: `claude` CLI installiert und angemeldet (`~/.claude/`)
- Für den Provider CODEX: `codex` CLI installiert und angemeldet (`~/.codex/`)
- Keine API-Keys in der App-Konfiguration nötig

### Startup

- `SummarizationSetupService.init()` läuft bei Backend-Start
- Erstellt Working Directories falls nicht vorhanden
- Kopiert Instruktions-Templates aus Resources in die Directories
- Überschreibt bei jedem Start (latest bundled version)
