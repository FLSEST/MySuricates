> *Zuletzt aktualisiert: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

# Architektur

## Systemübersicht

```
┌─────────────────────────────────────────────────────────┐
│                      Browser                            │
│  React 18 PWA (Vite Dev-Server :3000 / statisch)        │
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
Controller (REST API, 26 Controller)
    ↓ DTO (Request/Response Records)
Service (Geschäftslogik)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (Datei: data/automation.db)
```

### Packages

```
com.automation
├── controller/          26 REST-Controller
├── service/
│   ├── project/         ProjectService, ContainerService, ProjectContextService, PathValidator
│   ├── workflow/        WorkflowExecutor, WorkflowService, ExecutionBroadcaster, TemplateVerificationService, WorkflowOptimizationService, GhostRunWatchdogService, RunCleanupService, AgentInstructionsService, WorkflowTemplateService
│   ├── skill/           SkillService, SkillImportExportService, SkillRegistry, CapabilityRegistry
│   ├── scheduler/       SchedulerService, ScheduledWorkflowExecutor
│   ├── webhook/         WebhookService, GitWebhookPayloadParser
│   ├── learning/        LearningService, AgenticLearningService, ExecutionInsightsService, WorkflowStatsService, WorkflowSimilarityService, BlockTypesBackfillService, MetadataExtractor
│   ├── system/          ApiKeyService, DatabaseBackupService, ExecutionSettingsService, SystemIdentityService, TailscaleService
│   ├── ai/              HostClaudeService, HostCodexService, AiProviderRegistry, ClaudeAiProvider, CodexAiProvider
│   ├── cli/             CommandExecutorService, CliSessionManager, ClaudeOutputParser, ShellCommandService, ContainerShellService
│   ├── chain/           ChainService, ChainExecutor, ChainTemplateService, BuiltInChainTemplate
│   ├── memory/          WorkflowMemoryService, ClaudeSummarizer, CodexSummarizer, KnowledgeSummarizer, ConversationPreprocessor, SummarizationSetupService
│   ├── staging/         StagingService, TestingService, InstanceSyncHelper, DockerContainerManager
│   ├── todo/            TodoService, AutoTodoService, GateStatusResolver, GateStatusOutputParser, ProjectPathResolver
│   ├── conversion/      WorkflowConversionService, WorkflowSuggestion, DialogWorkflowSuggestion
│   ├── dialog/          DialogSessionScanner, DialogConversionService, ClaudeJsonlParser
│   ├── factory/         WorkflowFactoryService, ClaudeWorkflowGenerator, CodexWorkflowGenerator, WorkflowTextGenerator, FactorySetupService
│   ├── pool/            WorkflowPoolService
│   ├── wish/            WishService, WishWatchdogService
│   ├── notification/    NotificationService
│   ├── trigger/         CompletionTriggerService
│   ├── health/          HealthAutoTodoService
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
│   └── blocks/          12 Block-Executors
├── config/              Konfigurationsklassen, Seeder (Skill, Template)
├── security/            ApiKeyAuthFilter, RateLimitFilter
├── exception/           GlobalExceptionHandler + Exception-Klassen
├── websocket/           WebSocket-Konfiguration
├── event/               WorkflowRunCompletedEvent, AssistantSessionUpdatedEvent
└── util/                JPA-Converter (JSON, BlockDefinitions)
```

### Alle 26 REST-Controller

| Controller | Base-Path | Beschreibung |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | CRUD, Archivierung, Container-Lifecycle, Kontext-Generierung |
| `WorkflowController` | `/api/v1/workflows` | CRUD, Ausführung, Export/Import, Skills, Optimize, Repair |
| `ExecutionController` | `/api/v1/executions` | Pause, Resume, Resume-from-Step, Cancel, Retry, Approve, Todos-Approve, Command |
| `SkillController` | `/api/v1/skills` | CRUD, Capabilities, Export/Import |
| `ScheduleController` | `/api/v1/schedules` | CRUD, Enable/Disable, Runs, Calendar, Preview, Remaining-Stats, Chain-Schedules, Maintenance |
| `WebhookController` | `/api/v1/webhooks` | CRUD, Enable/Disable; Trigger via `/api/v1/webhook/trigger/{token}` |
| `ChainController` | `/api/v1/chains` | CRUD, Ausführung, Runs |
| `ChainTemplateController` | `/api/v1/chain-templates` | Liste, Abrufen, Installieren als Chain |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | CRUD, Aus-Workflow erstellen |
| `WorkflowMemoryController` | `/api/v1/workflows/{id}/memory` | CRUD, Extrahieren, Aktivieren |
| `WorkflowFactoryController` | `/api/v1/factory` | Generate, Run, Verify, Promote, Topics |
| `WorkflowPoolController` | `/api/v1/pool` | Liste, Abrufen, Installieren |
| `ConversionController` | `/api/v1/conversions` | Suggest, Create-Workflow aus Run |
| `DialogController` | `/api/v1/dialog` | Sessions, Projekte, Claude-Session-Analyse, Workflow-Erstellung |
| `LearningController` | `/api/v1/learning` | Dashboard, Stats, Step-Analytics, Suggestions, Similar, Agentic-History, Agentic-Analytics, Tool-Patterns, Execution-Insights, Run-Insights, Rate |
| `TodoController` | `/api/v1/todos` | CRUD, Status-Patch, Count |
| `ActivityLogController` | `/api/v1/activity-log` | Liste |
| `AssistantController` | `/api/v1/assistant` | Chat (`POST /chat`) |
| `PageSuggestionController` | `/api/v1/assistant` | Suggestions per Page (Get/Refresh) |
| `CompletionTriggerController` | `/api/v1/completion-triggers` | CRUD, Enable/Disable, Filter by Workflow |
| `NotificationController` | `/api/v1/notifications` | CRUD, Enable/Disable |
| `WishController` | `/api/v1/wishes` | CRUD, Cancel, Respond, Crystallize |
| `StagingController` | `/api/v1/staging` | Status, Start, Stop, Promote, Promote-Cancel, Sync-Preview, Sync-Definitions |
| `TestingController` | `/api/v1/testing` | Status, Start, Stop, Reset, Sync-Preview, Sync-Definitions, Report-Result, Pull-Changes |
| `SystemController` | `/api/v1/system` | Health, Auth-Status, Info, Settings, Execution-Config, AI-Providers, Backup, Backups, Remote-Access, Browse, Open-Path, Repair, Client-Errors |
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
7. Nach Abschluss wertet `WorkflowExecutor` Gate-Status aus (alle Block-Outputs)
8. `WorkflowRunCompletedEvent` wird gefeuert → `AutoTodoService` reagiert

### Block-Typen (12 gesamt)

| Typ | Executor | Beschreibung |
|---|---|---|
| `simple` | `SimpleBlockExecutor` | Einzelner Claude-CLI-Aufruf mit Prompt |
| `shell` | `SimpleBlockExecutor` (dual-mode) | Shell-Skript oder Claude-CLI |
| `retry` | `RetryBlockExecutor` | Prompt mit konfigurierbaren Wiederholungsversuchen |
| `conditional` | `ConditionalBlockExecutor` | If/Else-Verzweigung basierend auf Bedingung |
| `forEach` | `ForEachBlockExecutor` | Iteration über eine Liste von Elementen |
| `while` | `WhileBlockExecutor` | Wiederholung mit Abbruchbedingung und Max-Iterations |
| `switch` | `SwitchBlockExecutor` | Multi-Way-Branching per Expression mit Cases |
| `agentic` | `AgenticBlockExecutor` | Goal→Reason→Act→Observe Loop, bis zu 10 Iterationen |
| `approval` | `ApprovalBlockExecutor` | Hält Ausführung an, wartet auf Nutzer-Bestätigung |
| `sub-workflow` | `SubWorkflowBlockExecutor` | Führt einen anderen Workflow als Unterprozess aus |
| `skill-capability` | `SkillCapabilityBlockExecutor` | Ruft eine benannte Skill-Capability auf |
| `human-input` | `HumanInputBlockExecutor` | Sammelt Nutzereingabe via WebSocket, speichert als Kontext-Variable |

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

Zusätzlich: History-Kompression nach 7 Turns, Token-Tracking-Warnungen im Log. Alle 6 Safety-Felder werden im Frontend-"Block hinzufügen"-Dialog als Defaults vorbelegt.

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
- `@Lazy`-Injection auf `WorkflowExecutor`, um Spring-DI-Zyklen zu brechen

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
- `WorkflowExecutor` scannt alle Block-Ausgaben — höchste Schwere gewinnt (GREEN < YELLOW < RED)
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
- **Sequenziell:** `ChainExecutor` führt Entries in `sortOrder`-Reihenfolge aus (virtual threads, polling alle 2s, 30-Min.-Timeout)
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

## Notification System (V40)

Benachrichtigungs-Konfigurationen für Workflow-Ereignisse:
- `NotificationService` — CRUD für Benachrichtigungs-Konfigurationen
- `NotificationController` — `/api/v1/notifications`
- API: List, Get, Create, Enable/Disable, Delete

## Assistant System (V41+V42)

Kontext-sensitiver Chat-Assistent für die Plattform. Siehe auch: [assistant-system.md](assistant-system.md)

- `AssistantService` — Chat-Logik, Kontext-Aufbereitung, Claude-CLI-Aufruf
- `AssistantSessionService` — Session-Persistierung in DB (V41-Migration: `assistant_sessions`)
- `PageSuggestionService` — generiert und cached kontextsensitive UI-Vorschläge pro Seite (V42-Migration: `page_suggestions`)
- `AssistantController` — `POST /api/v1/assistant/chat`
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
- `HostClaudeService.reasonStructured()` — REASON-Phase, nutzt `--system-prompt`, `--max-turns 3`, kein Modell-Flag
- Prompt wird via **stdin** an den Prozess geschrieben (`writePromptToStdin()`) — vermeidet Linux ARG_MAX-Limit
- `CLAUDE_CODE` und `CLAUDECODE` Umgebungsvariablen werden aus dem Prozess-Environment entfernt (verhindert Nested-Session-Fehler)
- `CommandExecutorService` bietet `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `writeSkillContextToProject()` — alle Overloads nutzen die `HostExecutionRequest`-Builder-API
- Skill-Kontext wird dateibasiert injiziert: `writeSkillContextToProject()` schreibt `.claude/rules/skills-context.md` ins Projektverzeichnis (ersetzt die alte CLI-prozessbasierte Methode `injectSkillContextOnHost()`)

## Provider Routing

Alle Block-Executors (simple, retry, conditional, forEach, while, switch, skill-capability) leiten `context.getProvider()` als `providerId` via `HostExecutionRequest.builder()` an den `CommandExecutorService` weiter. Das Setzen von `"provider": "CODEX"` in einem Workflow-JSON gilt damit für jeden nicht-Shell-Block-Typ.

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
- Die Agentic-REASON-Phase verwendet das lokal konfigurierte Standard-Modell

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
- **44 Built-in Templates** werden beim Start durch `WorkflowTemplateSeeder` eingespielt (idempotent, fügt fehlende ein, aktualisiert bei Version-Bump)
- Template-Definitionen liegen als einzelne JSON-Dateien in `src/main/resources/templates/builtin/` (eine Datei pro Template, geladen durch `BuiltInTemplateLoader`)
- Name und Beschreibung sind i18n JSON-Maps: `{"de":"...","en":"..."}` — gespeichert via `JsonStringMapConverter`
- Built-in Templates (`builtIn=true`) können nicht gelöscht werden; nur `sortOrder` und `category` sind aktualisierbar
- Nutzer können eigene Templates via API oder durch Speichern eines bestehenden Workflows anlegen
- `workflow.templateId` — optionale Back-Referenz auf das Quell-Template
- Frontend löst lokalisierte Template-Namen via `resolveTemplateName()` in `utils/templateUtils.ts` auf
- CRUD-API unter `/api/v1/workflow-templates`

## Built-in Skills

- Skills werden in der `skills`-DB-Tabelle gespeichert, nicht in Frontend oder In-Memory-Beans hardcodiert
- **16 Built-in Skills** werden beim Start durch `SkillSeeder` eingespielt (idempotent)
- Skill-Definitionen liegen in `BuiltInSkills.java` (`config/`-Package)
- Built-in Skills haben stabile IDs (z.B. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` registriert alle Skills auch im In-Memory `SkillRegistry` für Capability-Lookups
- `SkillRegistry` hängt nicht mehr von `SkillProvider`-Beans ab — Seeder übernimmt die Registrierung
- Capability-Handler-Klassen (z.B. `LintHandler`, `OCRHandler`) bleiben als unabhängige `@Component`-Beans in `skills/handlers/`
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

## Wishes (Wunsch-System)

Natürlichsprachige Wünsche werden automatisch in Workflows/Chains umgesetzt:
- `WishService` — erstellt Wish, startet asynchrone Erfüllung
- `WishWatchdogService` — überwacht laufende Wishes auf Stale-/Timeout-Zustände
- Iterativer Prozess: Decomposition → Workflow-/Chain-Generierung → Ausführung → Feedback
- Unterstützt bis zu `max_iterations` Iterationen für Verbesserungen
- `crystallize()` — promotet erfolgreichen Wish als Template

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
| `Container` | `containers` | Docker-Container pro Projekt mit Status und Mounts |
| `NotificationConfig` | `notification_configs` | Benachrichtigungs-Konfiguration (Webhook/Desktop) |
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
| `Todo.Status` | OPEN, IN_PROGRESS, DONE, BACKLOG |
| `Todo.Priority` | LOW, MEDIUM, HIGH |
| `Todo.Source` | USER, AI, WORKFLOW |
| `ActivityLog.LogLevel` | DEBUG, INFO, WARN, ERROR |
| `ActivityLog.LogCategory` | SYSTEM, EXECUTION, SCHEDULE, WEBHOOK, SECURITY, CONTAINER |
| `ScheduledWorkflow.ScheduleType` | CRON, ONCE, DELAYED |
| `ScheduledWorkflow.TargetType` | WORKFLOW, CHAIN |
| `ScheduledWorkflow.ConflictBehavior` | SKIP, OVERLAP |
| `NotificationConfig.ChannelType` | WEBHOOK, DESKTOP |

## Frontend-Architektur

### Komponentenstruktur

```
App.tsx (Router)
├── Layout
│   ├── Sidebar (Navigation, Collapse)
│   └── Header (Sprache, Theme)
│
├── Pages (18+ Routen)
│   ├── Dashboard, Projects, ProjectDetail
│   ├── Workflows, WorkflowDetail, WorkflowExecution
│   ├── Skills, Schedules, Webhooks, Settings
│   ├── Runs, ActivityLog, Todos
│   ├── Chains, ChainExecution
│   ├── Wishes, WorkflowFactory, Docs
│
├── Hooks (TanStack Query + Custom)
│   ├── useProjects, useWorkflows, useSkills, ...
│   ├── useChains, useTodos, useActivityLog
│   ├── useWorkflowMemory, useWishes, useWorkflowFactory
│   ├── useStaging, useTesting, usePool, useDocs
│   └── useWebSocket, useToast
│
├── Stores (Zustand)
│   ├── sidebarStore, themeStore
│   ├── settingsStore, i18nStore, toastStore
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
- **Nachrichtenformat:** `{ timestamp, level, message }`
- **Terminal-WebSocket:** `/ws/terminal` für Echtzeit-Container-Zugriff

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
- `POST /api/v1/webhook/trigger/{token}`
- `/ws/**` (WebSocket)
- `/actuator/**`

### Rate Limiting

- **Filter:** `RateLimitFilter`
- **Algorithmus:** Sliding Window (1 Minute)
- **Standard:** 120 Requests/Minute pro Client-IP
- **Response-Header:** `X-RateLimit-Remaining`
- **Deaktivierbar:** `automation.security.rate-limit.enabled=false`

### CORS

- Alle Origins erlaubt (`*`) mit Credentials
- Alle HTTP-Methoden (GET, POST, PUT, DELETE, PATCH, OPTIONS)

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

## Verzeichnisstruktur

```
autotrigger/
├── CLAUDE.md                          ← Projektanweisungen für Claude Code
├── VISION.md                          ← Produkt-Vision
├── docs/                              ← Diese Dokumentationsdateien
├── workflow-pool/                     ← Vorgefertigte Workflow-Definitionen (JSON)
├── scripts/
│   ├── release.sh                     ← Versions-Bump + Git-Tag
│   └── staging.sh                     ← Stable-Umgebung verwalten
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
│   │   └── Dockerfile.agent-go
│   ├── data/                          ← SQLite-DB (gitignored)
│   └── src/main/
│       ├── java/com/automation/       ← Java-Quelldateien
│       └── resources/
│           ├── application.yml
│           ├── application-stable.yml ← Stable-Profil
│           ├── agent-instructions.md  ← Basis-Agent-Instructions
│           ├── templates/builtin/     ← Built-in Template JSON-Dateien
│           └── db/migration/          ← 47 Flyway-Migrationen (V1–V47)
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
        ├── pages/                     ← 18+ Seiten
        ├── components/
        │   ├── ui/                    ← Wiederverwendbare UI-Komponenten
        │   ├── layout/                ← Sidebar, Header, Layout
        │   ├── shared/                ← StatusBadge, LogViewer, etc.
        │   ├── dashboard/             ← Chart-Komponenten
        │   └── workflow/              ← Block-Editor, Templates
        ├── hooks/                     ← TanStack Query Hooks
        ├── stores/                    ← Zustand Stores
        ├── i18n/                      ← DE/EN Übersetzungen
        ├── theme/                     ← Theme-Tokens
        ├── data/                      ← Skill-Konstanten
        ├── utils/                     ← Hilfsfunktionen
        └── test/                      ← MSW-Handler, Test-Setup
```
