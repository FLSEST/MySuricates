> *Zuletzt aktualisiert: [auto-generated]*

# Architektur

## Systemübersicht

```
┌─────────────────────────────────────────────────────────┐
│                      Browser                            │
│  React 19 PWA (Vite Dev-Server :3000 / statisch)        │
└──────────┬──────────────────────┬───────────────────────┘
           │ REST /api/v1/*       │ WebSocket /ws
           │ (Vite Proxy)         │ (STOMP/SockJS)
           ▼                      ▼
┌─────────────────────────────────────────────────────────┐
│         Spring Boot Backend Dev (:9090)                 │
│                                                         │
│  ┌─────────┐  ┌──────────┐  ┌────────────┐             │
│  │Controller│→ │ Service  │→ │ Repository │             │
│  └─────────┘  └──────────┘  └──────┬─────┘             │
│       │                            │                    │
│       │  ┌──────────────┐    ┌─────▼─────┐             │
│       └→ │ WebSocket    │    │  SQLite   │             │
│          │ (STOMP)      │    │ (WAL)     │             │
│          └──────────────┘    └───────────┘             │
│                                                         │
│  ┌──────────────────────────────────────┐               │
│  │         Docker Client API            │               │
│  └──────────────┬───────────────────────┘               │
└─────────────────┼───────────────────────────────────────┘
                  │ /var/run/docker.sock
                  ▼
┌─────────────────────────────────────────────────────────┐
│                  Docker Engine                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐              │
│  │agent-node│  │agent-py  │  │agent-go  │              │
│  │(Node 21) │  │(Python   │  │(Go 1.22) │              │
│  │          │  │ 3.12)    │  │          │              │
│  └──────────┘  └──────────┘  └──────────┘              │
└─────────────────────────────────────────────────────────┘

Stable (:9091) und Test (:8090) laufen als separate Docker-Container.
```

## Drei Umgebungen

| Umgebung | Port | Beschreibung |
|---|---|---|
| **Dev** | 3000 (Frontend) / 9090 (Backend) | Entwicklungsserver, aus Quellcode |
| **Stable** | 9091 | Docker-Container, Backend + eingebettetes Frontend |
| **Test** | 8090 | Docker-Container, dedizierte Test-Instanz |

Dev startet Stable und Test bei Boot automatisch, wenn ein Snapshot vorhanden ist (konfigurierbar via `automation.staging.auto-start` bzw. `automation.staging.test-auto-start`).

## Backend-Architektur

### Schichtenmodell

```
Controller (REST API, 34 Controller)
    ↓ DTO (Request/Response Records)
Service (Geschäftslogik)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (Datei: data/automation.db)
```

### Datenbank-Wartung (SQLite, pool-size=1)

- **Run-Retention** (`RunCleanupService.cleanupTerminalRuns`, täglich 03:15): löscht abgeschlossene Runs (COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED) samt `step_results`, die älter als `run-retention-days` sind — älteste zuerst, höchstens `run-retention-max-per-night` pro Nacht, jeder Run in einer eigenen kurzen Transaktion über `RunDeletionService` (räumt DLQ-, TODO- und Wish-Verweise mit). Runs mit Audit-Report bleiben (Query-Ausschluss plus Guard im Service). Runtime-Settings in `ExecutionSettingsService`: `run-retention-days` [0,730] (0 = aus, Default 90), `run-retention-max-per-night` [10,5000] (Default 200). INTERRUPTED-Runs haben ihre eigene, ältere Regel (`cleanupInterruptedRuns`, 03:00).
- **Backup** (`DatabaseBackupService`, täglich 02:00, `automation.backup.retain-count`): läuft bewusst vor der Retention.
- **VACUUM**: `PRAGMA auto_vacuum = INCREMENTAL` ist das **einzige** Statement im Hikari-Init-SQL — sqlite-jdbc führt von einem mehrteiligen Init-String nur das erste aus (`foreign_keys`/`busy_timeout` stehen deshalb in der JDBC-URL, Guard: `DataSourceConfigGuardTest`). Das Pragma greift nur bei leerer DB oder nach einem vollen `VACUUM`. Der Startup-Check `checkAutoVacuumMode` schreibt eine ActivityLog-WARNING, wenn die Datei noch auf NONE steht — dann einmal `POST /api/v1/system/vacuum` ausführen, danach gibt das nächtliche `incremental_vacuum` (03:00) Platz frei. Direkter `sqlite3`-Zugriff ist tabu (Hibernate-Cache, Single-Writer).

### Packages

```
com.automation
├── controller/          34 REST-Controller
├── service/
│   ├── project/         ProjectService, ContainerService (+ DockerContainerGateway, DockerOrphanReconciler), ProjectContextService, PathValidator, OnboardingProfileService, ProjectOnboardingService
│   ├── workflow/        WorkflowExecutor (+ ExecutionContextFactory, RunCompletionHandler, WorkflowContextDataService), WorkflowService, ExecutionBroadcaster, TemplateVerificationService, WorkflowOptimizationService, GhostRunWatchdogService, RunCleanupService, AgentInstructionsService, WorkflowTemplateService, BlockOutputValidator, WorkflowExecutionRegistry, WorkflowTaggingService, WorkflowVersionService, WorkflowContentChangedEvent
│   ├── skill/           SkillService, SkillImportExportService, SkillRegistry, CapabilityRegistry
│   ├── scheduler/       SchedulerService (+ SchedulerTick, SchedulerMaintenanceMode, UpcomingRunsCalculator, ScheduleSkipPolicy, ScheduleDefinitionParser), ScheduledWorkflowExecutor, SuspendState
│   ├── webhook/         WebhookService, GitWebhookPayloadParser
│   ├── learning/        LearningService, AgenticLearningService, ExecutionInsightsService, WorkflowStatsService, WorkflowSimilarityService, BlockTypesBackfillService, MetadataExtractor, PlatformStatsService
│   ├── system/          ApiKeyService, DatabaseBackupService, ExecutionSettingsService (+ ExecutionSettingKeys), SystemIdentityService, TailscaleService, FileBrowserService, IntegrationSnippetService, SystemSettingsService
│   ├── ai/              HostClaudeService, HostCodexService, HostPiService, AiProviderRegistry, AiProvider, AiProviderCapabilities, AiResponseMetadata, ClaudeAiProvider, CodexAiProvider, PiAiProvider, PiCatalogService
│   ├── cli/             CommandExecutorService, CliSessionManager, CliSession, ClaudeOutputParser, ClaudeJsonResponse, HostExecutionRequest, ShellCommandService, ContainerShellService, TaskCommandService
│   ├── chain/           ChainService, ChainExecutor (+ ChainRunWaiter, ChainZombieRunCleaner), ChainTemplateService, BuiltInChainTemplate
│   ├── memory/          WorkflowMemoryService, ClaudeSummarizer, CodexSummarizer, KnowledgeSummarizer, ConversationPreprocessor, SummarizationSetupService, AutoMemoryExtractionListener
│   ├── staging/         StagingService, TestingService, InstanceSyncHelper (+ InstanceHttpClient, SyncPreviewService), DockerContainerManager
│   ├── todo/            TodoService (+ TodoSpecifications, TodoTrackerExportService, TodoResolutionService, TodoResponseAssembler), AutoTodoService (+ AutoTodoTextBuilder, PendingTodoApprovalService, GranularTodoService), GateStatusResolver, GateStatusOutputParser, ProjectPathResolver, TodoGhostWatchdogService, TodoHygieneService, TodoOutputParser, TodoOverviewService, TodoResolutionListener, TodoSimilarityService
│   ├── conversion/      WorkflowConversionService, WorkflowSuggestion, DialogWorkflowSuggestion
│   ├── dialog/          DialogSessionScanner, DialogConversionService, ClaudeJsonlParser
│   ├── factory/         WorkflowFactoryService (+ FactoryPromptBuilder, FactoryResponseParser, FactoryTopicCatalog), ClaudeWorkflowGenerator, CodexWorkflowGenerator, WorkflowTextGenerator, FactorySetupService
│   ├── pool/            WorkflowPoolService
│   ├── wish/            WishService (+ WishFulfillmentLoop, WishEvaluationService), WishWatchdogService, WishDecomposeService, WishDecomposePromptBuilder, WishExecutionService, WishEvaluatePromptBuilder, WishPromptBuilder, WishPromptHelper, WishJsonUtil, WishStatusHelper, WishStringUtil
│   ├── notification/    NotificationService
│   ├── trigger/         CompletionTriggerService, TodoTriggerService, TodoFeedbackListener
│   ├── health/          HealthAutoTodoService
│   ├── audit/           AuditResultParser, AuditIngestionService, AuditIngestionListener, AuditCenterService
│   ├── integration/     IntegrationRegistry, IntegrationOAuthService, IntegrationEncryptionService, IntegrationProvider, OAuthConfig, TaskExportCapable
│   ├── integration/providers/ 7 Provider in Unterverzeichnissen (confluence/, github/, google/, jira/, taskwarrior/, trello/)
│   ├── assistant/       AssistantContextBuilder (+ PageContextRenderer, ProjectFileListing), AssistantChatBroadcaster, AssistantFetchService, AssistantToolExecutor (+ AssistantTodoTools, AssistantWorkflowTools, AssistantChainTools, AssistantToolPayloads), AssistantActionSuggestionService, AssistantErrorClassifier, AssistantFetchPolicy, AssistantMutationGate, AssistantActionMerger, AssistantResponseParser, PendingTool, PendingToolRegistry, ToolExecutionResult
│   ├── assistant/context/ AssistantContextSection, BlockTypesIndexSection, ChainsIndexSection, FailedWorkflowsContextSection, IntegrationsIndexSection, RunsContextSection, SchedulesContextSection, SkillsIndexSection, TodosContextSection, WishesContextSection
│   ├── execution/       ExecutionQueryService (Lesen + DTO-Mapping), ExecutionCommandService (Retry, Resume, Notizen), WorkflowDebugService
│   ├── dlq/             DeadLetterService, DeadLetterRetryService
│   ├── document/        DocumentExtractionService, DocumentExtractionException
│   ├── export/bpmn/     BpmnModelBuilder (Kette/Einzel-Task/Schleife) + BpmnNodeFactory (Ids, Platzierung, Kanten, Command-Tasks), BpmnGatewayBuilder (conditional/switch: Split/Rows/Merge), BpmnEdgeRouter (rechtwinkliges Waypoint-Routing), BpmnBlockText (Labels, Doku, Config-Helfer), BpmnSegment (Teil-Graph), BpmnXmlRenderer, BpmnExportAdapter, BpmnModel, BpmnNames
│   ├── search/          SemanticWorkflowSearchService
│   ├── watchdog/        ConnectionPoolWatchdogService
│   ├── repair/          AutoRepairService
│   ├── tray/            SystemTrayService
│   ├── activitylog/     ActivityLogService, ActivityLogLifecycleListener
│   ├── AssistantService.java        Chat-Assistent (Claude CLI)
│   ├── AssistantSessionService.java Session-Persistierung
│   └── PageSuggestionService.java   Kontext-sensitive UI-Vorschläge
├── entity/              JPA-Entities + Enums
├── dto/
│   ├── request/         Request-Records
│   └── response/        Response-Records
├── repository/          Spring Data Repositories
├── mapper/              MapStruct-Mapper (Entity ↔ DTO)
├── executor/            Workflow-Ausführungs-Engine
│   └── blocks/          14 Block-Executors + Agentic-Hilfsklassen (AgenticBlockConfig, AgenticPlan, AgenticSafetyGuard, AgenticTokenTracker, AgenticPromptBuilder, AgenticEventBroadcaster)
├── concurrency/         DynamicSemaphore
├── config/              Konfigurationsklassen, Seeder (Skill, Template, OnboardingProfile)
├── security/            ApiKeyAuthFilter, RateLimitFilter, ActivityLogSizeLimitFilter, WebhookRateLimiter, WebSocketAuthInterceptor
├── skills/handlers/     CapabilityHandler, LintHandler, SecurityScanHandler, FactCheckHandler, GrammarCheckHandler, OCRHandler
├── exception/           GlobalExceptionHandler, ErrorCode + Exception-Klassen
├── websocket/           LogWebSocketHandler, TerminalWebSocketHandler, TerminalSession, Message-DTOs
├── event/               WorkflowRunCompletedEvent, AssistantSessionUpdatedEvent, TodoCreatedEvent
└── util/                JPA-Converter (JSON, BlockDefinitions)
```

### Alle 34 REST-Controller

| Controller | Base-Path | Beschreibung |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | CRUD, Archivierung, Container-Lifecycle, Kontext-Generierung |
| `WorkflowController` | `/api/v1/workflows` | CRUD, Ausführung, Runs, Export/Import, Skills, Optimize, Similar, Repair, Versions |
| `ExecutionController` | `/api/v1/executions` | Liste, Detail, Pause, Resume, Resume-from-Step, Cancel, Retry, Approve, Approval-Check, Todos-Approve, Notes, Command |
| `SkillController` | `/api/v1/skills` | CRUD, Capabilities, Export/Import |
| `ScheduleController` | `/api/v1/schedules` | CRUD, Enable/Disable, Runs, Calendar, Preview, Remaining-Stats, Chain-Schedules, Maintenance |
| `WebhookController` | `/api/v1/webhooks` | CRUD, Enable/Disable; Trigger via `/api/v1/webhook/trigger/{token}` (öffentlich, nur Token-geschützt — Payload-Felder werden als `__untrusted_keys` markiert und in Shell-Blöcken immer escaped, siehe `ExecutionContext.resolveShellTemplate`) |
| `ChainController` | `/api/v1/chains` | CRUD, Ausführung, Runs, Cancel |
| `ChainTemplateController` | `/api/v1/chain-templates` | Liste, Abrufen, Installieren als Chain |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | CRUD, Aus-Workflow erstellen |
| `WorkflowMemoryController` | `/api/v1/workflows/{id}/memory` | CRUD, Extrahieren, Aktivieren |
| `WorkflowFactoryController` | `/api/v1/factory` | Generate, Run, Verify, Promote, Topics |
| `WorkflowPoolController` | `/api/v1/pool` | Liste, Abrufen, Installieren, Export |
| `ConversionController` | `/api/v1/conversions` | Suggest, Create-Workflow aus Run |
| `DialogController` | `/api/v1/dialog` | Sessions, Projekte, Claude-Session-Analyse, Workflow-Erstellung |
| `LearningController` | `/api/v1/learning` | Dashboard, Stats, Step-Analytics, Suggestions, Similar, Agentic-History, Agentic-Analytics, Tool-Patterns, Execution-Insights, Run-Insights, Rate |
| `TodoController` | `/api/v1/todos` | CRUD, Status-Patch, Resolve, Export, Count, Overview (Cockpit), Bulk-Status, Archive-Stale, Similar |
| `ActivityLogController` | `/api/v1/activity-log` | Liste, Erstellen |
| `AssistantController` | `/api/v1/assistant` | Chat (`POST /chat`), Sessions, Pending-Tool-Bestätigung/Ablehnung |
| `PageSuggestionController` | `/api/v1/assistant` | Suggestions per Page (Get/Refresh) |
| `DeadLetterController` | `/api/v1/dlq` | Dead-Letter-Queue: Liste, Retry, Löschen |
| `SearchController` | `/api/v1/search` | Aggregierte Suche über Workflows (Substring/Fuzzy + Semantic Ranking) |
| `WorkflowDebugController` | `/api/v1/debug` | Debug-Seite: Workflows mit fehlgeschlagenem letztem Run |
| `CompletionTriggerController` | `/api/v1/completion-triggers` | CRUD, Enable/Disable, Filter by Workflow |
| `TodoTriggerController` | `/api/v1/todo-triggers` | CRUD, Enable/Disable, Filter by Target-Workflow |
| `NotificationController` | `/api/v1/notifications` | CRUD, Enable/Disable |
| `WishController` | `/api/v1/wishes` | CRUD, Cancel, Respond, Crystallize |
| `StagingController` | `/api/v1/staging` | Status, Start, Stop, Promote, Promote-Cancel, Sync-Preview, Sync-Definitions |
| `TestingController` | `/api/v1/testing` | Status, Start, Stop, Reset, Sync-Preview, Sync-Definitions, Report-Result, Pull-Changes |
| `SystemController` | `/api/v1/system` | Health, Auth-Status, Info, Settings, Execution-Config, AI-Providers, AI-Provider-Katalog, Capabilities, Backup, Backups, Remote-Access, Vacuum, Browse, Open-Path, Repair, Client-Errors, Integration-Snippet |
| `IntegrationController` | `/api/v1/integrations` | OAuth-Flow, Credentials, Actions, Provider-Liste, Task-Export-Ziele |
| `OnboardingProfileController` | `/api/v1/onboarding-profiles` | Onboarding-Profile CRUD, Reset, History |
| `ReportsController` | `/api/v1/reports` | Report-Generierung und -Abruf |
| `AuditController` | `/api/v1/audits`, `/api/v1/audit-*` | Audit-Übersicht, Report-Historie, Findings und TODO-Verknüpfungen |
| `DocsController` | `/api/v1/docs` | Markdown-Dokumentation lesen |

### Workflow-Ausführung

Die Workflow-Engine führt Blöcke sequenziell aus. Claude CLI läuft **immer auf dem Host** (nicht in Containern), um Nested-Session-Erkennung zu vermeiden.

**Ablauf:**
1. `WorkflowExecutor` erstellt einen `WorkflowRun` (Status: PENDING → RUNNING)
2. Für jeden `BlockDefinition` wird der passende `BlockExecutor` ausgewählt
3. Claude-CLI-Aufrufe erfolgen auf dem Host via `HostClaudeService` (`ProcessBuilder`)
4. Shell-Befehle können optional via Docker Exec im Container ausgeführt werden
5. Ergebnisse werden als `StepResult` gespeichert
6. Live-Logs gehen über WebSocket (STOMP) an das Frontend
7. Nach Abschluss wertet `RunCompletionHandler` Gate-Status und TODO-Marker aus (alle Block-Outputs), verbucht Template-Verification, Activity-Log und DLQ
8. `WorkflowRunCompletedEvent` wird gefeuert → `AutoTodoService` reagiert

### Block-Typen (14 gesamt)

| Typ | Executor | Beschreibung |
|---|---|---|
| `simple` | `SimpleBlockExecutor` | Einzelner Claude-CLI-Aufruf mit Prompt |
| `shell` | `ShellBlockExecutor` | Shell-Skript, optional via Docker-Container |
| `retry` | `RetryBlockExecutor` | Prompt mit konfigurierbaren Wiederholungsversuchen |
| `conditional` | `ConditionalBlockExecutor` | If/Else-Verzweigung basierend auf Bedingung |
| `foreach` | `ForEachBlockExecutor` | Iteration über eine Liste von Elementen |
| `while` | `WhileBlockExecutor` | Wiederholung mit Abbruchbedingung und Max-Iterations |
| `switch` | `SwitchBlockExecutor` | Multi-Way-Branching per Expression mit Cases |
| `agentic` | `AgenticBlockExecutor` | Goal→Reason→Act→Observe Loop, bis zu 10 Iterationen |
| `approval` | `ApprovalBlockExecutor` | Hält Ausführung an, wartet auf Nutzer-Bestätigung |
| `sub-workflow` | `SubWorkflowBlockExecutor` | Führt einen anderen Workflow als Unterprozess aus |
| `skill-capability` | `SkillCapabilityBlockExecutor` | Ruft eine benannte Skill-Capability auf |
| `human-input` | `HumanInputBlockExecutor` | Sammelt Nutzereingabe via WebSocket, speichert als Kontext-Variable |
| `integration` | `IntegrationBlockExecutor` | Führt eine Aktion auf einem externen Integration-Provider aus |
| `document` | `DocumentBlockExecutor` | Extrahiert Text aus PDF/DOCX/XLSX/HTML/TXT via Apache Tika |

**Template-Variablen** (`{{ variable }}`, `${varName}`) werden zur Laufzeit im `ExecutionContext` aufgelöst. `{{ previous_output }}` enthält die Ausgabe des vorherigen Blocks.

### Agentic Block (Goal→Reason→Act→Observe)

Der Agentic-Block implementiert eine autonome Ausführungsschleife:

1. **REASON:** Claude analysiert Ziel und bisherige Beobachtungen → liefert strukturierte JSON-Aktion
2. **ACT:** Aktion wird ausgeführt (Shell oder Claude-CLI)
3. **OBSERVE:** Ausgabe wird gesammelt und der History hinzugefügt
4. Schleife läuft bis `done: true` oder Abbruchbedingungen

**Safety-Mechanismen:**

| Config | Default | Beschreibung |
|---|---|---|
| `max_iterations` | 10 | Maximale Iterations-Zahl |
| `iteration_timeout_seconds` | 300 | Wall-Clock-Timeout pro Iteration |
| `total_timeout_seconds` | 1800 | Gesamt-Block-Timeout (30 Min.) |
| `max_total_tokens` | 200000 | Token-Budget (Input+Output, alle Phasen) |
| `max_repeated_actions` | 3 | Abbruch nach N identischen Aktionen |
| `require_approval` | false | Approval-Gate vor jeder Aktion |

Zusätzlich: History-Kompression nach 7 Turns, Action-Kompression bei >100.000 Zeichen, Token-Tracking-Warnungen im Log. Alle 6 Safety-Felder werden im Frontend-"Block hinzufügen"-Dialog als Defaults vorbelegt. Hilfsklassen: `AgenticBlockConfig`, `AgenticSafetyGuard`, `AgenticTokenTracker`, `AgenticPlan`.

### Switch Block

Der Switch-Block evaluiert einen Ausdruck gegen mehrere Cases:
- Unterstützt `${varName}` und `{{ varName }}` für Variablen-Interpolation
- Cases werden in Reihenfolge geprüft — erster Treffer gewinnt
- Pipe-getrennte Alternativen: `"error|critical"` matched beide
- `default`-Case, wenn kein anderer Case zutrifft
- Jeder Case enthält eine Liste von Nested-Blocks (shell oder claude)
- Gibt `COMPLETED` mit `matchedCase: "none"` zurück, wenn kein Treffer und kein Default

### Sub-Workflow Block

- Führt einen anderen Workflow als verschachtelten Block aus
- Zirkuläre Referenzen werden via `__parent_workflow_ids` erkannt
- Maximale Verschachtelungstiefe: 5 Ebenen
- Polling-basierter Abschluss, 30-Minuten-Timeout
- `@Lazy`-Injection auf `WorkflowExecutor` nur dort, wo ein Aufrufer selbst Runs startet (Sub-Workflow, Chains, Trigger); reine Status-Abfragen (aktive Runs, Container-IDs, Approvals) gehen direkt an `WorkflowExecutionRegistry`

### Human-Input Block

- Pausiert die Workflow-Ausführung und wartet auf Nutzereingabe
- Sendet `human_input_required`-Event via WebSocket an `/topic/executions/{runId}/logs`
- Nutzerantwort wird als Kontext-Variable gespeichert (`variable_name`)
- Unterstützt Text- und Select-Eingaben, Validierungsmuster, Timeout mit optionalem Default
- Nutzt den `ApprovalRequest`-Mechanismus des Approval-Blocks

## Gate Status System

Workflow-Blöcke können Quality-Gate-Marker in ihre Ausgabe schreiben:

```
gate_status: GREEN|YELLOW|RED
gate_category: <kategorie>
gate_description: <beschreibung>
```

**Verarbeitung:**
- `GateStatusOutputParser` — parst Marker aus Block-Ausgaben (tolerant gegenüber Markdown-Formatierung)
- `RunCompletionHandler` scannt alle Block-Ausgaben — höchste Schwere gewinnt (GREEN < YELLOW < RED)
- Gate-Status wird in `WorkflowRun.metadata` gespeichert (`gate_status`, `gate_category`, `gate_description`)

**Gate → Run-Status-Mapping:**
- **YELLOW → `COMPLETED_WITH_ERRORS`:** Run wird mit diesem Status beendet statt `COMPLETED`
- **RED → `COMPLETED`:** RED-Gates ändern den Run-Status nicht — `AutoTodoService` erstellt stattdessen ein TODO
- `GateStatusResolver` — leitet Gate-Status aus Run-Status ab, wenn kein expliziter Marker existiert (`FAILED` → RED, `COMPLETED` → GREEN)

**AutoTodoService:**
- Reagiert auf `WorkflowRunCompletedEvent`
- RED-Gate → TODO mit `HIGH`-Priorität
- YELLOW-Gate → TODO mit `MEDIUM`-Priorität
- Source: `WORKFLOW`, verknüpft mit dem Run

## Workflow Chains

Chains gruppieren mehrere Workflows für sequenzielle Ausführung:

- **Projektübergreifend:** Eine Chain gehört keinem Projekt und kann Workflows aus verschiedenen Projekten referenzieren
- **Sequenziell:** `ChainExecutor` führt Entries in `sortOrder`-Reihenfolge aus (virtual threads, polling alle 2s, 180-Min.-Timeout)
- **Kontext-Weitergabe:** Jeder Entry bekommt Chain-Level-Kontext + Entry-Level-Kontext + `previous_chain_output` vom letzten abgeschlossenen Workflow
- **Stop-on-Failure:** Flag auf Chain- und Entry-Ebene — Chain stoppt nur wenn beide `true` sind
- **Cancellation:** Via `ConcurrentHashMap<String, AtomicBoolean>`
- **WebSocket:** Status-Broadcasts an `/topic/chains/{chainRunId}/status`

### Chain Templates

Vorgefertigte Chain-Definitionen, die sich aus bestehenden Workflow-Templates zusammensetzen:
- `ChainTemplateService` — verwaltet Built-in Chain Templates (`BuiltInChainTemplate` Records)
- `ChainTemplateController` — `/api/v1/chain-templates` mit List, Get, Install-Endpunkten
- Install: Erstellt eine neue Chain (ggf. mit zugehörigen Workflows) in einem Zielprojekt

## Completion Triggers (V39)

Automatische Verkettung von Workflows auf Basis von Abschluss-Ereignissen:
- `CompletionTriggerService` — verwaltet Trigger-Definitionen
- `CompletionTriggerController` — `/api/v1/completion-triggers`
- Trigger verknüpfen einen Source-Workflow (Auslöser) mit einem Target-Workflow (wird nach Abschluss gestartet)
- API: List, Get, Create, Enable/Disable, Delete; Filter by Source- oder Target-Workflow-ID

## Todo Triggers — Self-Improvement Loop (V51/V52)

Automatische Ausführung von Fix-Workflows wenn TODOs erstellt werden — schliesst den Self-Improvement-Loop:

```
Audit-Workflow → AuditIngestionService → Finding → TODO (OPEN)
                                                 ↓ TodoCreatedEvent
                             TodoTriggerService → Fix-Workflow
                                                     ↓ WorkflowRunCompletedEvent
                                             TodoFeedbackListener → TODO (DONE)
```

- `TodoTriggerService` — `@TransactionalEventListener` auf `TodoCreatedEvent`, matched TODOs gegen konfigurierte Trigger (Tag-Pattern AND-Semantik, Priority-Filter, Projekt-Filter), startet Fix-Workflow mit TODO-Daten als Context-Variablen
- `TodoFeedbackListener` — `@EventListener` auf `WorkflowRunCompletedEvent`, schliesst TODO bei GREEN Gate (wenn `autoClose=true`), loggt Eskalation bei erschöpften Fix-Versuchen
- `TodoTriggerController` — `/api/v1/todo-triggers`
- API: List, Get, Create, Enable/Disable, Delete
- **Loop-Prevention**: 3 Schichten — Metadata-Flag (`todoTriggerRun`), Max-Attempts-Zähler (`todo.fixAttempts`), Running-Check (`todo.lastFixRunId`)
- **Context-Variablen** für Fix-Workflow: `todo_id`, `todo_title`, `todo_description`, `todo_tags`, `todo_priority`, `todo_project_id`, `todo_trigger_id`
- **Pause** (`automation.todo-trigger.skip-until`, ISO-Instant, leer = aus): legt den gesamten Mechanismus bis zu diesem Zeitpunkt still — Backlog-Poll **und** Event-Pfad. Greift ab dem ersten Tick nach dem Start, braucht also kein laufendes Backend zum Setzen. Zusammen mit `automation.scheduler.skip-until` (+ `catch-up-on-resume: false`) ergibt das eine vollständige Trigger-Pause aus wenigen Config-Zeilen, z. B. in der gitignorierten `ai-automation-backend/config/application.yml`. Bewusst ein Datum statt eines Flags: es läuft von selbst aus, während ein vergessenes `enabled=false` auf den einzelnen Triggern den Loop stumm stillstehen lässt (kein Fehler, kein roter Status — die TODOs bleiben nur liegen). Unparsebarer Wert ⇒ fail-open (ERROR-Log, Trigger feuern normal). Eine aktive Pause wird beim Start als WARNING geloggt.

## Projekt-Cockpit & TODO-Hygiene

Da TODOs zu ~96 % maschinell erzeugt und über viele Projekte verstreut sind, gibt es eine projektübergreifende Übersicht plus einen Aufräum-Mechanismus.

- **Cockpit** (`GET /api/v1/todos/overview`, `TodoOverviewService`): liefert pro Projekt einen Rollup (Status-Counts, `staleCount`, `userActive` vs. `machineActive`, `needsYouCount`) plus eine globale „Braucht dich"-Liste (USER-Source ∨ CRITICAL ∨ überfällig). Alles aus schlanken GROUP-BY-Projektionen (keine TEXT-Blobs → pool-size=1-safe), Frontend-Seite `ProjectCockpit.tsx` (Route `/cockpit`).
- **ARCHIVED-Status**: Ablage neben BACKLOG für „aus den Augen". Aus der Default-Liste ausgeblendet (`TodoSpecifications.buildSpec` schließt ARCHIVED aus, wenn kein Status-Filter gesetzt ist), erreichbar über den Archiv-Tab / Edit-Modal.
- **Hygiene** (`TodoHygieneService`): `archiveStaleNow(projectId)` verschiebt verstaubte Auto-Todos (OPEN/BACKLOG, Source AI/WORKFLOW, Tag `auto:workflow`, `updatedAt` älter als Schwelle) nach ARCHIVED — per Bulk-UPDATE in kurzer Transaktion. Cockpit-Knopf „Verstaubtes archivieren" (`POST /todos/archive-stale?projectId=`) ist der primäre, human-in-the-loop Weg. Der `@Scheduled`-Job (täglich 03:30) ruft denselben Pfad, ist aber per Default **AUS** (`todo-hygiene-enabled`). Schwellen runtime-konfigurierbar via `ExecutionSettingsService`: `todo-hygiene-enabled` [0,1], `todo-stale-days` [7,365], `todo-hygiene-max-per-run` [10,2000].
- **Ghost-Watchdog** (`TodoGhostWatchdogService`): setzt verwaiste IN_PROGRESS-TODOs mit Maschinen-Source (AI/WORKFLOW) zurück auf OPEN, wenn die zugehörige CLI-Session ohne abschließenden Status-PATCH gestorben ist (`updatedAt` älter als Schwelle). Poll alle 5 min (`automation.todo-watchdog.poll-ms`), per Default **AN**; Human-TODOs (Source USER) werden nie angefasst. ActivityLog-WARNING pro Lauf. Runtime-Settings: `todo-watchdog-enabled` [0,1], `todo-watchdog-stale-hours` [1,720] (Default 24h).
- **Bulk-Status** (`POST /todos/bulk-status`): generischer Bulk-UPDATE für viele TODO-IDs auf einen Status.

## Notification System (V40)

Benachrichtigungs-Konfigurationen für Workflow-Ereignisse:
- `NotificationService` — CRUD für Benachrichtigungs-Konfigurationen
- `NotificationController` — `/api/v1/notifications`
- API: List, Get, Create, Enable/Disable, Delete

## Assistant System (V41+V42)

Kontext-sensitiver Chat-Assistent für die Plattform. Siehe auch: [assistant-system.md](assistant-system.md)

- `AssistantService` — Chat-Logik, Kontext-Aufbereitung, Claude-CLI-Aufruf
- `AssistantSessionService` — Session-Persistierung in DB (V41-Migration: `assistant_sessions`, V58: `preview`-Spalte). Endpunkte: `GET /sessions` (Top 50), `GET /sessions/{id}` (Detail), `DELETE /sessions/{id}`
- `AssistantContextBuilder` — injiziert letzte 5 Sessions (User-Nachricht + Antwort) und bis zu 8 aktive Workflow Memories in den System-Prompt — auf **allen** Seiten
- `PageSuggestionService` — generiert und cached kontextsensitive UI-Vorschläge pro Seite (V42-Migration: `page_suggestions`)
- `AssistantController` — `POST /api/v1/assistant/chat`, `GET /sessions`, `GET /sessions/{id}`, `DELETE /sessions/{id}`, `POST /pending-tools/{id}/confirm`, `POST /pending-tools/{id}/reject`
- `PageSuggestionController` — `GET /api/v1/assistant/suggestions?page=...`, `POST /api/v1/assistant/suggestions/refresh?page=...`
- Sessions werden nach jedem Chat-Aufruf persistiert; `AssistantSessionUpdatedEvent` triggert Page-Suggestion-Refresh
- Standard-Modell: `claude-sonnet-4-6`

## Workflow Memory

Workflows können über Runs hinweg Wissen akkumulieren:
- `WorkflowMemoryService` — extrahiert Wissen aus abgeschlossenen Runs
- `ClaudeSummarizer` / `CodexSummarizer` — AI-basierte Zusammenfassung
- `KnowledgeSummarizer` — Interface für die Summarization-Backends
- `HostCodexService` — führt OpenAI Codex CLI auf dem Host aus (analog zu `HostClaudeService`)
- `SummarizationSetupService` — Konfiguration und Setup der Summarization-Backends
- `ConversationPreprocessor` — Vorverarbeitung von Konversationsdaten für Summarization
- Aktive Memory-Einträge werden in nachfolgende Runs als Kontext injiziert
- `AutoMemoryExtractionListener` — automatische Extraktion nach erfolgreichen Runs, konfigurierbar per Workflow-Metadata (`autoMemoryExtraction.enabled`, `autoMemoryExtraction.interval`)
- `WorkflowMemory`-Entity (V16-Migration)

## Agent Instructions System

Zweistufige Instruction-Injektion via Claude CLI `--append-system-prompt` / `--system-prompt`:

1. **Basis-Instructions** (`src/main/resources/agent-instructions.md`) — geladen beim Start durch `AgentInstructionsService`, gelten für alle Workflows
2. **Workflow-spezifische Instructions** (`workflow.instructions`) — pro Workflow, im UI editierbar (Monaco-Editor)

Injektionspfade:
- **Block-Ausführung:** Instructions via `--append-system-prompt` auf Host Claude CLI (`HostClaudeService.executeAction()`)
- **Agentic REASON:** System-Prompt via `--system-prompt` an `HostClaudeService.reasonStructured()`, inkl. Workflow-Instructions in `## Workflow Instructions`

## Host Claude Service

Alle Claude-CLI-Aufrufe erfolgen auf dem Host (nicht im Container):
- `HostClaudeService.executeAction()` — ACT-Phase, nutzt `--append-system-prompt`, `--model`-Flag, Projekt-Arbeitsverzeichnis
- `HostClaudeService.reasonStructured()` — REASON-Phase, nutzt `--system-prompt`, `--max-turns 3`; `--model` wird gesetzt, wenn der Workflow ein Modell konfiguriert (`ReasoningRequest.model`)
- Prompt wird via **stdin** an den Prozess geschrieben (`writePromptToStdin()`) — vermeidet Linux ARG_MAX-Limit
- `CLAUDE_CODE` und `CLAUDECODE` Umgebungsvariablen werden aus dem Prozess-Environment entfernt (verhindert Nested-Session-Fehler)
- `CommandExecutorService` bietet `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `writeSkillContextToProject()` — alle Overloads nutzen die `HostExecutionRequest`-Builder-API
- Skill-Kontext wird dateibasiert injiziert: `writeSkillContextToProject()` schreibt `.claude/rules/skills-context.md` ins Projektverzeichnis (ersetzt die alte CLI-prozessbasierte Methode `injectSkillContextOnHost()`)

## Provider Routing

Alle Block-Executors (simple, retry, conditional, foreach, while, switch, skill-capability, integration) leiten `context.getProvider()` als `providerId` via `HostExecutionRequest.builder()` an den `CommandExecutorService` weiter. Das Setzen von `"provider": "CODEX"` in einem Workflow-JSON gilt damit für jeden nicht-Shell-Block-Typ.

`AiProviderRegistry.resolve()` fällt auf den konfigurierten Standard-Provider zurück (nicht hardcodiertes CLAUDE), wenn eine unbekannte Provider-ID übergeben wird.

## Per-Block Skills

Blöcke können über das `skillIds`-Feld eigene Skills mitbringen, die additiv zu den Workflow-Level-Skills gelten:

- `BlockDefinition.skillIds` — optionale Liste von Skill-IDs, die nur für diesen Block aktiviert werden
- `WorkflowExecutor` merged Workflow-Level-Skills + Block-Level-Skills vor jeder Block-Ausführung
- Nach der Block-Ausführung wird der Baseline-Skill-Zustand wiederhergestellt
- Skill-Kontext wird dateibasiert über `.claude/rules/skills-context.md` im Projektverzeichnis bereitgestellt (`CommandExecutorService.writeSkillContextToProject()`)
- Frontend: Skill-Picker-Toggles in den Add/Edit-Block-Modals, Zap-Icon-Indikator auf `SortableBlock`

## Model Selection

- `workflow.model`-Feld (z.B. `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5`)
- Wird via `--model`-Flag an die Claude CLI auf dem Host übergeben
- REASON und ACT eines Agentic-Laufs verwenden dasselbe Workflow-Modell (`ReasoningRequest.model` bzw. `ActionRequest.model`); ohne konfiguriertes Modell fällt der Provider auf seinen Default zurück

## Per-Call Token Limit

- `HostClaudeConfig.maxTokens` (Property `automation.host-claude.max-tokens`) — optionales Output-Token-Limit pro Aufruf
- Angewendet via `--max-tokens`-Flag in `reasonStructured()` und `executeAction()`
- Unterscheidet sich vom `max_total_tokens`-Budget des Agentic-Blocks (der kumulativen Input+Output über alle Iterationen trackt)

## Per-Block Claude CLI Timeout

- Alle Block-Typen unterstützen ein optionales `timeout_seconds`-Feld in der Block-Config
- Überschreibt den globalen `automation.host-claude.timeout-seconds`-Wert (Default: 300s) für diesen Block
- Ohne `timeout_seconds` in der Config → globaler Default gilt
- Für Dual-Mode-Blöcke (shell/claude) steuert `timeout_seconds` sowohl den Shell- als auch den Claude-CLI-Timeout
- Gilt für `reasonStructured()` und `executeAction()` in Agentic-Blöcken

## Workflow Conversion

Erfolgreiche Agentic-Runs können in deterministische Workflows konvertiert werden:
- `WorkflowConversionService` — analysiert den Run und schlägt einen strukturierten Workflow vor (regelbasiert + AI)
- `ConversionController` — API-Endpunkte für Vorschau und Erstellung
- Frontend: Konversions-Dialog im Execution-View

### Dialog-to-Workflow Conversion

Claude-Code-Sessions können ebenfalls als Vorlage für Workflows dienen:
- `DialogSessionScanner` — scannt `~/.claude/projects/` nach JSONL-Session-Dateien
- `DialogConversionService` — analysiert eine Session mit Claude und generiert eine Workflow-Suggestion
- `ClaudeJsonlParser` — parst JSONL-Session-Dateien
- `DialogController` — `/api/v1/dialog` mit Session-List, Analyse und Workflow-Erstellung
- Pfad-Traversal-Schutz: nur Pfade unter `~/.claude/projects/` mit `.jsonl`-Endung sind erlaubt

## Workflow Templates

- Templates werden in der `workflow_templates`-DB-Tabelle gespeichert (V19-Migration), nicht hardcodiert
- **66 Built-in Templates** werden beim Start durch `WorkflowTemplateSeeder` eingespielt (idempotent, fügt fehlende ein, aktualisiert bei Version-Bump)
- Template-Definitionen liegen als einzelne JSON-Dateien in `src/main/resources/templates/builtin/` (eine Datei pro Template, geladen durch `BuiltInTemplateLoader`)
- Name und Beschreibung sind i18n JSON-Maps: `{"de":"...","en":"..."}` — gespeichert via `JsonStringMapConverter`
- Built-in Templates (`builtIn=true`) können nicht gelöscht werden; nur `sortOrder` und `category` sind aktualisierbar
- Nutzer können eigene Templates via API oder durch Speichern eines bestehenden Workflows anlegen
- `workflow.templateId` — optionale Back-Referenz auf das Quell-Template
- Frontend löst lokalisierte Template-Namen via `resolveTemplateName()` in `utils/templateUtils.ts` auf
- CRUD-API unter `/api/v1/workflow-templates`

## Built-in Skills

- Skills werden in der `skills`-DB-Tabelle gespeichert, nicht in Frontend oder In-Memory-Beans hardcodiert
- **19 Built-in Skills** werden beim Start durch `SkillSeeder` eingespielt (idempotent)
- Skill-Definitionen liegen in `BuiltInSkills.java` (`config/`-Package)
- Built-in Skills haben stabile IDs (z.B. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` registriert alle Skills auch im In-Memory `SkillRegistry` für Capability-Lookups
- `SkillRegistry` hängt nicht mehr von `SkillProvider`-Beans ab — Seeder übernimmt die Registrierung
- Capability-Handler-Klassen (z.B. `LintHandler`, `OCRHandler`, `SecurityScanHandler`, `FactCheckHandler`, `GrammarCheckHandler`) in `skills/handlers/` (Unterverzeichnisse `code/`, `document/`)
- Nutzer können eigene Skills via Import-API anlegen (`POST /api/v1/skills/import`)
- `Skill.java`-Entity nutzt `@PrePersist ensureId()` (statt `@GeneratedValue`) für explizite IDs bei Built-ins

## Template Verification

- `TemplateVerification`-Entity (V22-Migration) — trackt den Qualitätsstatus von Workflow-Templates
- `TemplateVerificationService` — verwaltet den Verifizierungs-Status pro Template+Projekt-Paar
- Workflows, die aus Templates erstellt wurden, können test-gestartet werden, um die Template-Korrektheit zu prüfen
- Verifizierungs-Ergebnisse sind in Template-CRUD-API-Responses sichtbar

## Workflow Factory

Automatisches Generieren und Verifizieren von Workflows aus einem Topic:
- `WorkflowFactoryService` — orchestriert den gesamten Factory-Prozess
- `ClaudeWorkflowGenerator` / `CodexWorkflowGenerator` — AI-basierte Workflow-Generierung
- `WorkflowTextGenerator` — Textbasierte Workflow-Generierung
- `FactorySetupService` — Konfiguration und Setup der Factory-Umgebung
- `WorkflowFactoryController` — `/api/v1/factory` mit Endpunkten:
  - `POST /generate` — Workflow aus Topic generieren
  - `POST /run` — generierten Workflow auf Test-Instanz ausführen
  - `POST /verify/{id}` — Workflow verifizieren
  - `POST /promote/{id}` — Workflow in Produktion promoten
  - `GET /topics` — verfügbare Topics auflisten
- Nutzt die Test-Instanz (Port 8090) für sichere Ausführung

## Workflow Pool

Vorgefertigte Workflow-Definitionen aus dem `workflow-pool/`-Verzeichnis:
- `WorkflowPoolService` — liest JSON-Dateien aus dem konfigurierten Pool-Verzeichnis
- `WorkflowPoolController` — `/api/v1/pool` mit Endpunkten:
  - `GET /` — alle Pool-Workflows auflisten
  - `GET /{id}` — einzelnen Pool-Workflow abrufen
  - `POST /{id}/install` — Workflow aus Pool in Zielprojekt importieren
  - `POST /export/{workflowId}` — Workflow in den Pool exportieren

## Wishes (Wunsch-System)

Natürlichsprachige Wünsche werden automatisch in Workflows/Chains umgesetzt:
- `WishService` — erstellt Wish, verarbeitet Nutzer-Antworten (`respond`), kristallisiert zu Templates
- `WishFulfillmentLoop` — asynchroner Meta-Loop (decompose → execute → evaluate) inkl. Cancel-Flags und Gesamt-Timeout
- `WishEvaluationService` — Bewertungsphase (KI-Urteil → AWAITING_APPROVAL / REFINING / FAILED)
- `WishWatchdogService` — überwacht laufende Wishes auf Stale-/Timeout-Zustände
- Iterativer Prozess: Decomposition → Workflow-/Chain-Generierung → Ausführung → Feedback
- Unterstützt bis zu `max_iterations` Iterationen für Verbesserungen
- `crystallize()` — promotet erfolgreichen Wish als Template

## Integration System (V55/V56)

Plugin-Framework für externe Dienste:
- `IntegrationRegistry` — Auto-Discovery aller `IntegrationProvider`-Beans
- `IntegrationOAuthService` — OAuth2-Flow (Authorization Code Grant)
- `IntegrationEncryptionService` — Verschlüsselung von Credentials (AES-GCM)
- `IntegrationConnection`-Entity — Verbindungsstatus (CONNECTED, DISCONNECTED, EXPIRED)
- `IntegrationController` — `/api/v1/integrations` mit OAuth-Callback, Credentials, Actions
- 7 Provider in Unterverzeichnissen: Confluence, GitHub, GoogleCalendar, GoogleGmail, Jira, Taskwarrior, Trello
- `IntegrationBlockExecutor` — Führt Provider-Aktionen als Workflow-Block aus
- `TaskExportCapable` — Interface für Provider, die TODO-Export unterstützen

## Dead Letter Queue (V63)

Fehlgeschlagene asynchrone Operationen werden in einer Dead-Letter-Queue gespeichert:
- `DeadLetterService` — Verwaltung von Dead-Letter-Entries (CRUD, Retry, Discard)
- `DeadLetterController` — `/api/v1/dlq`
- `DeadLetterEntry`-Entity mit Status: OPEN, RETRIED, DISCARDED

## Workflow Search

Aggregierte Suche über Workflows mit mehreren Such-Strategien:
- `SemanticWorkflowSearchService` — Substring/Fuzzy-Suche + semantisches Ranking via AI
- `SearchController` — `/api/v1/search`

## Docs System

Backend liest Markdown-Dokumentationsdateien und stellt sie als API bereit:
- `DocsController` — `/api/v1/docs`, liest `.md`-Dateien aus konfiguriertem `docs/`-Verzeichnis
- Pfad-Traversal-Schutz eingebaut
- Frontend zeigt Docs in Split-Layout mit Markdown-Rendering

## Entities

Alle JPA-Entities und Enums im `com.automation.entity`-Package:

| Entity | Tabelle | Beschreibung |
|---|---|---|
| `ActivityLog` | `activity_log` | System-/Ausführungs-Logeinträge mit Level, Category, Metadata |
| `AssistantSession` | `assistant_sessions` | Persistierte Chat-Sessions des Assistenten |
| `BlockDefinition` | *(eingebettet)* | Block-Definition mit Typ, Config, Required-Capabilities (Value Object) |
| `Capability` | `capabilities` | Skill-Capabilities mit Schema und Handler-Referenz |
| `ChainEntry` | *(eingebettet)* | Eintrag in einer Chain mit Workflow-Referenz und Kontext (Value Object) |
| `ChainRun` | `chain_runs` | Ausführungs-Instanz einer Chain mit Entry-Runs |
| `ChainRunEntry` | *(eingebettet)* | Status eines einzelnen Chain-Eintrags während der Ausführung (Value Object) |
| `CompletionTrigger` | `completion_triggers` | Verknüpfung Source→Target-Workflow bei Abschluss |
| `DeadLetterEntry` | `dead_letter_entries` | Fehlgeschlagene Nachrichten in der Dead-Letter-Queue |
| `IntegrationConnection` | `integration_connections` | Verbindung zu externem Dienst (OAuth, Credentials) |
| `TodoTrigger` | `todo_triggers` | Automatische Fix-Workflow-Ausführung bei TODO-Erstellung (Self-Improvement-Loop) |
| `Container` | `containers` | Docker-Container pro Projekt mit Status und Mounts |
| `NotificationConfig` | `notification_configs` | Benachrichtigungs-Konfiguration (Webhook/Desktop) |
| `OnboardingProfile` | `onboarding_profiles` | Onboarding-Profil mit Typ (BASE/PROFILE) |
| `OnboardingProfileHistory` | `onboarding_profile_history` | Änderungshistorie eines Onboarding-Profils |
| `PageSuggestion` | `page_suggestions` | Gecachte UI-Vorschläge pro Seite |
| `Project` | `projects` | Projekt mit Typ, Pfad, Status, AI-Kontext |
| `ScheduledWorkflow` | `scheduled_workflows` | Cron-/Once-/Delayed-Schedule für Workflows und Chains |
| `Skill` | `skills` | Skill mit Kategorie, Capabilities, Konfigurationsschema |
| `StepResult` | `step_results` | Ergebnis eines einzelnen Workflow-Schritts mit Context-Delta |
| `TemplateVerification` | `template_verifications` | Qualitätsstatus eines Templates pro Projekt |
| `Todo` | `todos` | Aufgabe mit Status, Priorität, Source, Tags |
| `WebhookTrigger` | `webhook_triggers` | Webhook-Trigger für Workflows mit Secret-Token |
| `Wish` | `wishes` | Natürlichsprachiger Wunsch mit Decomposition und Iteration-History |
| `Workflow` | `workflows` | Workflow mit Blöcken, Instructions, Modell, Provider (Soft-Delete) |
| `WorkflowChain` | `workflow_chains` | Chain mit sortierten Entries und Stop-on-Failure |
| `WorkflowMemory` | `workflow_memory` | Extrahiertes Wissen aus Workflow-Runs |
| `WorkflowRun` | `workflow_runs` | Ausführungs-Instanz eines Workflows mit Steps und Metadata |
| `WorkflowTemplate` | `workflow_templates` | Template mit i18n Name/Description und Blöcken |
| `WorkflowVersion` | `workflow_versions` | Versionierte Workflow-Snapshots |

**Enums:**

| Enum | Werte |
|---|---|
| `ContainerStatus` | CREATED, RUNNING, STOPPED, ERROR |
| `GateStatus` | GREEN, YELLOW, RED |
| `ProjectStatus` | STOPPED, STARTING, RUNNING, ERROR |
| `ProjectType` | CODE, CONTENT, RESEARCH, BUSINESS, PERSONAL |
| `RunStatus` | PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED, INTERRUPTED |
| `SkillCategory` | DOCUMENT, CODE, DATA, CONTENT, RESEARCH, BUSINESS, PRODUCTIVITY |
| `StepStatus` | PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, WAITING_APPROVAL |
| `WishStatus` | RECEIVED, DECOMPOSING, AWAITING_DECOMPOSE_APPROVAL, EXECUTING, EVALUATING, REFINING, AWAITING_CLARIFICATION, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED |
| `Todo.Status` | OPEN, IN_PROGRESS, DONE, BACKLOG, ARCHIVED (ARCHIVED = „aus den Augen" — aus der Default-Liste ausgeblendet, eigener Archiv-Tab; Ziel der Hygiene/Bulk-Archivierung) |
| `Todo.Priority` | LOW, MEDIUM, HIGH, CRITICAL |
| `Todo.Source` | USER, AI, WORKFLOW |
| `ActivityLog.LogLevel` | DEBUG, INFO, WARN, ERROR |
| `ActivityLog.LogCategory` | SYSTEM, EXECUTION, SCHEDULE, WEBHOOK, SECURITY, CONTAINER, PLUGIN |
| `ScheduledWorkflow.ScheduleType` | CRON, ONCE, DELAYED |
| `ScheduledWorkflow.TargetType` | WORKFLOW, CHAIN |
| `ScheduledWorkflow.ConflictBehavior` | SKIP, OVERLAP |
| `NotificationConfig.ChannelType` | WEBHOOK, DESKTOP |
| `OnboardingProfileType` | BASE, PROFILE |
| `Todo.ResolutionState` | IDLE, RUNNING, FAILED, SUCCEEDED |
| `IntegrationConnection.ConnectionStatus` | CONNECTED, DISCONNECTED, EXPIRED |
| `DeadLetterEntry.Status` | OPEN, RETRIED, DISCARDED |

## Frontend-Architektur

### Komponentenstruktur

```
App.tsx (Router)
├── Layout
│   ├── Sidebar (Navigation, Collapse)
│   └── Header (Sprache, Theme)
│
├── Pages (28 Routen)
│   ├── Dashboard, Projects, ProjectDetail, ProjectCockpit
│   ├── Workflows, WorkflowDetail, WorkflowExecution, WorkflowDebug, WorkflowFactory
│   ├── Skills, Schedules, Calendar, Webhooks, Integrations, Settings
│   ├── Runs, ActivityLog, Todos, DeadLetterQueue
│   ├── Chains, ChainExecution
│   ├── Wishes, Docs, Reports, Insights, OnboardingProfiles, DialogImport
│   ├── Simple*-Varianten (SimpleDashboard, SimpleWorkflows, etc.)
│
├── Hooks (TanStack Query + Custom, 40+)
│   ├── useProjects, useWorkflows, useSkills, useSchedules, ...
│   ├── useChains, useChainTemplates, useTodos, useTodoTriggers, useTodoOverview
│   ├── useActivityLog, useWorkflowMemory, useWishes, useWorkflowFactory
│   ├── useStaging, useTesting, usePool, useDocs, useReports, useInsights
│   ├── useIntegrations, useDeadLetters, useCompletionTriggers, useWorkflowVersions
│   ├── useAssistant, useAssistantWebSocket, useCalendarAggregated
│   ├── useWebSocket, useToast
│   └── calendar-adapters/ (Google, Runs, Schedules, Taskwarrior, Trello)
│
├── Stores (Zustand)
│   ├── sidebarStore, themeStore
│   ├── settingsStore, toastStore, assistantStore
│
└── API Client
    └── /api/v1/* → Vite Proxy → Backend :9090
```

### Datenfluss

```
User-Interaktion
    ↓
React-Komponente
    ↓
Hook (useMutation / useQuery)
    ↓
API Client (fetch + X-API-Key Header)
    ↓
Vite Proxy (:3000 → :9090)
    ↓
Spring Boot Controller
    ↓
Service → Repository → SQLite
    ↓
Response (DTO) → React Query Cache → UI-Update
```

## WebSocket (Live-Logs + Status)

- **Protokoll:** STOMP über SockJS
- **Endpunkt:** `/ws` (SockJS Handshake)
- **Broker:** Simple Message Broker auf `/topic`
- **Topics:**
  - `/topic/executions/{runId}/logs` — Workflow-Ausführungs-Logs
  - `/topic/chains/{chainRunId}/status` — Chain-Ausführungs-Status
  - `/topic/activity-log` — Live-Activity-Log-Updates
  - `/topic/wishes/{wishId}/status` — Wish-Status-Updates
  - `/topic/assistant/{requestId}/status` — Assistenten-Chat-Status (pro Request)
  - `/topic/assistant/{sessionId}/events` — Assistenten-Push-Events (pro Session)
- **Nachrichtenformat:** `{ timestamp, level, message }`
- **Terminal-WebSocket:** `/ws/terminal/**` für Echtzeit-Container-Zugriff (Raw WebSocket via `RawWebSocketConfig`, Handler: `TerminalWebSocketHandler`)

## Sicherheit

### API-Key-Authentifizierung

- **Header:** `X-API-Key`
- **Filter:** `ApiKeyAuthFilter` (vor Spring Security)
- **Vergleich:** Constant-Time (`MessageDigest.isEqual`)
- **Auto-Generierung:** Key wird beim ersten Start in `app_settings` gespeichert
- **Deaktivierbar:** `automation.security.enabled=false` (Entwicklung)

### Öffentliche Endpunkte (kein API-Key nötig)

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `GET /api/v1/system/auth-status`
- `GET /api/v1/system/integration-snippet`
- `POST /api/v1/webhook/trigger/{token}`
- `/api/v1/integrations/*/callback` (OAuth2 Callbacks)
- `/ws/**` (WebSocket)
- `/actuator/health`

### Rate Limiting

- **Filter:** `RateLimitFilter` (liest von `RateLimitConfig` Bean)
- **Algorithmus:** Sliding Window (1 Minute)
- **Standard:** 120 Requests/Minute pro Client-IP
- **Response-Header:** `X-RateLimit-Remaining`
- **Runtime-konfigurierbar:** Settings → Execution → API-Ratenbegrenzung (Toggle + RPM-Wert)
- **Env-Override:** `AUTOMATION_RATE_LIMIT_ENABLED` / `AUTOMATION_RATE_LIMIT_RPM`
- **Webhook Rate Limiter:** `WebhookRateLimiter` — separater Per-IP-Limiter für `/api/v1/webhook/trigger/**`, immer aktiv

### Request-Size-Limit

- **Filter:** `ActivityLogSizeLimitFilter`
- **Max:** 64 KB (konfigurierbar via `automation.activity-log.max-request-bytes`)
- Nur für `/api/**`-Pfade

### WebSocket-Authentifizierung

- **Interceptor:** `WebSocketAuthInterceptor` — validiert API-Key bei WebSocket-Handshake

### CORS

- Origin-Patterns konfigurierbar via `automation.cors.allowed-origins` (Default: localhost:3000/5173 + Tailscale-Patterns)
- Alle HTTP-Methoden (GET, POST, PUT, PATCH, DELETE, OPTIONS)
- Credentials erlaubt

## Datenbankmigrationen (Flyway)

Die DB-Migrationen (`src/main/resources/db/migration/`) dokumentieren die Feature-Timeline:

| Migration | Feature |
|---|---|
| V1–V9 | Basistabellen: Projects, Containers, Skills, Capabilities, Workflows, Runs, Schedules, Webhooks, Timestamps |
| V10–V11 | Performance-Indexes, Container-Last-Activity |
| V12 | App-Settings |
| V13 | Workflow-Modell + Instructions |
| V14 | Schedule-Typen + Scheduled-At |
| V15 | Activity-Log-Tabelle |
| V16 | Workflow-Memory-Tabelle |
| V17 | Todos-Tabelle |
| V18 | Project-Subtitle + Description |
| V19 | Workflow-Templates-Tabelle |
| V20 | Input-Context in Workflow-Runs |
| V21 | Workflow-Chains |
| V22 | Template-Quality-Status |
| V23 | Default-Context in Workflows |
| V24 | Activity-Log Composite Index |
| V25 | Workflow-Test-Status |
| V26 | Block-Types-Column |
| V27 | Chain-Support für Schedules |
| V28–V29 | Wishes + Wish-Reference-Workflow-IDs |
| V30 | Project-AI-Context |
| V31 | Workflow-ID im Activity-Log |
| V32 | Workflow Soft-Delete (deleted_at) |
| V33 | Wish-Iteration-History |
| V34 | Workflow-Provider |
| V35 | Project-Archivierung (archived_at) |
| V36 | Todo-Session-ID |
| V37 | Delayed-Schedule-Type |
| V38 | Scheduled-Workflow-ID nullable |
| V39 | Completion-Triggers-Tabelle |
| V40 | Notification-Configs-Tabelle |
| V41 | Assistant-Sessions-Tabelle |
| V42 | Page-Suggestions-Tabelle |
| V43 | Triggered-By-Schedule-ID |
| V44 | Schedule-End-Conditions |
| V45 | Schedule-Conflict-Behavior |
| V46 | Stable-Synced-At |
| V47 | Context-Delta in Step-Results |
| V48 | Related-Todo-ID |
| V49 | Onboarding-Profiles |
| V50 | Workflow-Versions |
| V51 | Todo-Triggers-Tabelle |
| V52 | Fix-Attempts + Last-Fix-Run-ID auf TODOs |
| V53 | Process-Backlog-Flag auf Todo-Triggers |
| V54 | Composite-Indexes auf Todos |
| V55 | Integration-Connections-Tabelle |
| V56 | Client-Credentials auf Connections |
| V57 | Due-Date auf Todos |
| V58 | Assistant-Session-Preview-Spalte |
| V59 | Linked-Workflow auf Todos |
| V60 | Workflow-Name in Runs |
| V61 | Workflow-Tags und Category |
| V62 | Auto-Refine auf Wishes |
| V63 | Dead-Letter-Entries-Tabelle |
| V64 | Writes-Flag auf Capabilities |
| V65 | Catch-Up-On-Resume auf Schedules |
| V66 | Todo-Export-Link |
| V67 | Aufwandsschätzung auf Workflows |
| V68 | Audit-Reports, Findings, Occurrences und TODO-Verknüpfungen |

## Verzeichnisstruktur

```
autotrigger/
├── CLAUDE.md                          ← Projektanweisungen für Claude Code
├── VISION.md                          ← Produkt-Vision
├── docs/                              ← Diese Dokumentationsdateien
├── workflow-pool/                     ← Vorgefertigte Workflow-Definitionen (JSON)
├── scripts/
│   ├── release.sh                     ← Versions-Bump + Git-Tag
│   ├── staging.sh                     ← Stable/Test-Umgebung verwalten
│   └── start-autotrigger-dev.sh       ← Dev-Umgebung starten
│
├── ai-automation-backend/
│   ├── pom.xml
│   ├── docker-compose.yml
│   ├── docker-compose.stable.yml      ← Stable-Container
│   ├── docker-compose.test.yml        ← Test-Container
│   ├── docker/
│   │   ├── Dockerfile.backend
│   │   ├── Dockerfile.stable          ← Ubuntu-basiert (glibc für Claude CLI)
│   │   ├── Dockerfile.agent-node
│   │   ├── Dockerfile.agent-python
│   │   ├── Dockerfile.agent-go
│   │   ├── Dockerfile.agent-devops
│   │   └── Dockerfile.agent-java
│   ├── data/                          ← SQLite-DB (gitignored)
│   └── src/main/
│       ├── java/com/automation/       ← Java-Quelldateien
│       └── resources/
│           ├── application.yml
│           ├── application-stable.yml ← Stable-Profil
│           ├── application-prod.yml   ← Produktions-Profil
│           ├── application-test.yml   ← Test-Profil
│           ├── application-dev.yml    ← Dev-Profil
│           ├── agent-instructions.md  ← Basis-Agent-Instructions
│           ├── templates/builtin/     ← Built-in Template JSON-Dateien
│           └── db/migration/          ← 68 Flyway-Migrationen (V1–V68)
│
└── ai-automation-frontend/
    ├── package.json
    ├── vite.config.ts
    ├── tailwind.config.ts
    ├── eslint.config.js               ← Flat Config (ESLint 9+)
    ├── index.html
    └── src/
        ├── App.tsx                    ← Router
        ├── main.tsx                   ← Entry Point
        ├── index.css                  ← Globale Styles
        ├── api/client.ts              ← HTTP-Client (Singleton)
        ├── types/index.ts             ← TypeScript-Typen
        ├── pages/                     ← 37 Seiten (inkl. Simple-Mode-Varianten)
        ├── components/
        │   ├── ui/                    ← Basis-UI-Primitives
        │   ├── layout/                ← App-Shell, Sidebar, Navigation
        │   ├── shared/                ← StatusBadge, LogViewer, etc.
        │   ├── dashboard/             ← Dashboard-Widgets
        │   ├── workflow/              ← Block-Editor, Templates
        │   ├── assistant/             ← AI-Assistenten-Panel
        │   ├── calendar/              ← Kalender-Ansicht
        │   ├── factory/               ← Workflow-Factory-Builder
        │   ├── insights/              ← Analytics/Insights
        │   ├── simple/                ← Simple-Mode-Komponenten
        │   ├── todo/                  ← TODO-Verwaltung
        │   └── wishes/ u.a.           ← Weitere Feature-Komponenten
        ├── hooks/                     ← TanStack Query Hooks
        ├── stores/                    ← Zustand Stores
        ├── i18n/                      ← DE/EN Übersetzungen
        ├── theme/                     ← Theme-Tokens
        ├── data/                      ← Skill-Konstanten
        ├── utils/                     ← Hilfsfunktionen
        └── test/                      ← MSW-Handler, Test-Setup
```
