> *Last updated: [auto-generated]*

# Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────┐
│                      Browser                            │
│  React 19 PWA (Vite Dev Server :3000 / static)          │
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

Stable (:9091) and Test (:8090) run as separate Docker containers.
```

## Three Environments

| Environment | Port | Description |
|---|---|---|
| **Dev** | 3000 (Frontend) / 9090 (Backend) | Development server, from source code |
| **Stable** | 9091 | Docker container, backend + embedded frontend |
| **Test** | 8090 | Docker container, dedicated test instance |

Dev automatically starts Stable and Test on boot if a snapshot is available (configurable via `automation.staging.auto-start` and `automation.staging.test-auto-start`).

## Backend Architecture

### Layer Model

```
Controller (REST API, 33 Controllers)
    ↓ DTO (Request/Response Records)
Service (Business Logic)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (File: data/automation.db)
```

### Database Maintenance (SQLite, pool-size=1)

- **Run retention** (`RunCleanupService.cleanupTerminalRuns`, daily 03:15): deletes finished runs (COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED) including their `step_results` once they are older than `run-retention-days` — oldest first, at most `run-retention-max-per-night` per pass, each run in its own short transaction via `RunDeletionService` (which also clears DLQ, TODO and wish references). Runs referenced by an audit report are kept (query exclusion plus a guard in the service). Runtime settings in `ExecutionSettingsService`: `run-retention-days` [0,730] (0 = off, default 90), `run-retention-max-per-night` [10,5000] (default 200). INTERRUPTED runs keep their older rule (`cleanupInterruptedRuns`, 03:00).
- **Backup** (`DatabaseBackupService`, daily 02:00, `automation.backup.retain-count`): deliberately runs before retention.
- **VACUUM**: `PRAGMA auto_vacuum = INCREMENTAL` is the **only** statement in the Hikari init SQL — sqlite-jdbc runs just the first statement of a multi-statement string (`foreign_keys`/`busy_timeout` therefore live in the JDBC URL, guard: `DataSourceConfigGuardTest`). The pragma only takes effect on an empty database or after a full `VACUUM`. The startup check `checkAutoVacuumMode` writes an ActivityLog WARNING while the file is still at NONE — run `POST /api/v1/system/vacuum` once, after which the nightly `incremental_vacuum` (03:00) reclaims space. Direct `sqlite3` access stays off-limits (Hibernate cache, single writer).

### Packages

```
com.automation
├── controller/          33 REST Controllers
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
│   ├── todo/            TodoService (+ TodoSpecifications, TodoTrackerExportService, TodoResolutionService, TodoResponseAssembler), AutoTodoService (+ AutoTodoTextBuilder, PendingTodoApprovalService, GranularTodoService), GateStatusResolver, GateStatusOutputParser, ProjectPathResolver, TodoHygieneService, TodoOutputParser, TodoOverviewService, TodoResolutionListener, TodoSimilarityService
│   ├── conversion/      WorkflowConversionService, WorkflowSuggestion, DialogWorkflowSuggestion
│   ├── dialog/          DialogSessionScanner, DialogConversionService, ClaudeJsonlParser
│   ├── factory/         WorkflowFactoryService (+ FactoryPromptBuilder, FactoryResponseParser, FactoryTopicCatalog), ClaudeWorkflowGenerator, CodexWorkflowGenerator, WorkflowTextGenerator, FactorySetupService
│   ├── pool/            WorkflowPoolService
│   ├── wish/            WishService (+ WishFulfillmentLoop, WishEvaluationService), WishWatchdogService, WishDecomposeService, WishDecomposePromptBuilder, WishExecutionService, WishEvaluatePromptBuilder, WishPromptBuilder, WishPromptHelper, WishJsonUtil, WishStatusHelper, WishStringUtil
│   ├── notification/    NotificationService
│   ├── trigger/         CompletionTriggerService, TodoTriggerService, TodoFeedbackListener
│   ├── health/          HealthAutoTodoService
│   ├── integration/     IntegrationRegistry, IntegrationOAuthService, IntegrationEncryptionService, IntegrationProvider, OAuthConfig, TaskExportCapable
│   ├── integration/providers/ 7 Providers in subdirectories (confluence/, github/, google/, jira/, taskwarrior/, trello/)
│   ├── assistant/       AssistantContextBuilder (+ PageContextRenderer, ProjectFileListing), AssistantChatBroadcaster, AssistantFetchService, AssistantToolExecutor (+ AssistantTodoTools, AssistantWorkflowTools, AssistantChainTools, AssistantToolPayloads), AssistantActionSuggestionService, AssistantErrorClassifier, AssistantFetchPolicy, AssistantMutationGate, AssistantActionMerger, AssistantResponseParser, PendingTool, PendingToolRegistry, ToolExecutionResult
│   ├── assistant/context/ AssistantContextSection, BlockTypesIndexSection, ChainsIndexSection, FailedWorkflowsContextSection, IntegrationsIndexSection, RunsContextSection, SchedulesContextSection, SkillsIndexSection, TodosContextSection, WishesContextSection
│   ├── execution/       ExecutionQueryService (reads + DTO mapping), ExecutionCommandService (retry, resume, notes), WorkflowDebugService
│   ├── dlq/             DeadLetterService, DeadLetterRetryService
│   ├── document/        DocumentExtractionService, DocumentExtractionException
│   ├── export/bpmn/     BpmnModelBuilder (chain/single task/loop) + BpmnNodeFactory (ids, placement, edges, command tasks), BpmnGatewayBuilder (conditional/switch: split/rows/merge), BpmnEdgeRouter (right-angle waypoint routing), BpmnBlockText (labels, docs, config helpers), BpmnSegment (sub-graph), BpmnXmlRenderer, BpmnExportAdapter, BpmnModel, BpmnNames
│   ├── search/          SemanticWorkflowSearchService
│   ├── watchdog/        ConnectionPoolWatchdogService
│   ├── repair/          AutoRepairService
│   ├── tray/            SystemTrayService
│   ├── activitylog/     ActivityLogService, ActivityLogLifecycleListener
│   ├── AssistantService.java        Chat assistant (Claude CLI)
│   ├── AssistantSessionService.java Session persistence
│   └── PageSuggestionService.java   Context-sensitive UI suggestions
├── entity/              JPA Entities + Enums
├── dto/
│   ├── request/         Request Records
│   └── response/        Response Records
├── repository/          Spring Data Repositories
├── mapper/              MapStruct Mappers (Entity ↔ DTO)
├── executor/            Workflow Execution Engine
│   └── blocks/          17 Block Executors + Agentic helper classes (AgenticBlockConfig, AgenticPlan, AgenticSafetyGuard, AgenticTokenTracker, AgenticPromptBuilder, AgenticEventBroadcaster, AgenticApprovalGate, AgenticActionRunner, AgenticStopRules)
├── concurrency/         DynamicSemaphore
├── config/              Configuration classes, Seeders (Skill, Template, OnboardingProfile)
├── security/            ApiKeyAuthFilter, RateLimitFilter, ActivityLogSizeLimitFilter, WebhookRateLimiter, WebSocketAuthInterceptor
├── skills/handlers/     CapabilityHandler, LintHandler, SecurityScanHandler, FactCheckHandler, GrammarCheckHandler, OCRHandler
├── exception/           GlobalExceptionHandler, ErrorCode + Exception classes
├── websocket/           LogWebSocketHandler, TerminalWebSocketHandler, TerminalSession, Message DTOs
├── event/               WorkflowRunCompletedEvent, AssistantSessionUpdatedEvent, TodoCreatedEvent
└── util/                JPA Converters (JSON, BlockDefinitions)
```

### All 33 REST Controllers

| Controller | Base Path | Description |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | CRUD, archiving, container lifecycle, context generation |
| `WorkflowController` | `/api/v1/workflows` | CRUD, execution, runs, export/import, skills, optimize, similar, repair, versions |
| `ExecutionController` | `/api/v1/executions` | List, detail, pause, resume, resume-from-step, cancel, retry, approve, approval-check, todos-approve, notes, command |
| `SkillController` | `/api/v1/skills` | CRUD, capabilities, export/import |
| `ScheduleController` | `/api/v1/schedules` | CRUD, enable/disable, runs, calendar, preview, remaining-stats, chain-schedules, maintenance |
| `WebhookController` | `/api/v1/webhooks` | CRUD, enable/disable; trigger via `/api/v1/webhook/trigger/{token}` (public, token-only — payload fields are flagged as `__untrusted_keys` and always escaped in shell blocks, see `ExecutionContext.resolveShellTemplate`) |
| `ChainController` | `/api/v1/chains` | CRUD, execution, runs, cancel |
| `ChainTemplateController` | `/api/v1/chain-templates` | List, get, install as chain |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | CRUD, create from workflow |
| `WorkflowMemoryController` | `/api/v1/workflows/{id}/memory` | CRUD, extract, activate |
| `WorkflowFactoryController` | `/api/v1/factory` | Generate, run, verify, promote, topics |
| `WorkflowPoolController` | `/api/v1/pool` | List, get, install, export |
| `ConversionController` | `/api/v1/conversions` | Suggest, create workflow from run |
| `DialogController` | `/api/v1/dialog` | Sessions, projects, Claude session analysis, workflow creation |
| `LearningController` | `/api/v1/learning` | Dashboard, stats, step-analytics, suggestions, similar, agentic-history, agentic-analytics, tool-patterns, execution-insights, run-insights, rate |
| `TodoController` | `/api/v1/todos` | CRUD, status-patch, resolve, export, count, overview (cockpit), bulk-status, archive-stale, similar |
| `ActivityLogController` | `/api/v1/activity-log` | List, create |
| `AssistantController` | `/api/v1/assistant` | Chat (`POST /chat`), sessions, pending-tool confirmation/rejection |
| `PageSuggestionController` | `/api/v1/assistant` | Suggestions per page (get/refresh) |
| `DeadLetterController` | `/api/v1/dlq` | Dead letter queue: list, retry, delete |
| `SearchController` | `/api/v1/search` | Aggregated search across workflows (substring/fuzzy + semantic ranking) |
| `WorkflowDebugController` | `/api/v1/debug` | Debug page: workflows with failed last run |
| `CompletionTriggerController` | `/api/v1/completion-triggers` | CRUD, enable/disable, filter by workflow |
| `TodoTriggerController` | `/api/v1/todo-triggers` | CRUD, enable/disable, filter by target workflow |
| `NotificationController` | `/api/v1/notifications` | CRUD, enable/disable |
| `WishController` | `/api/v1/wishes` | CRUD, cancel, respond, crystallize |
| `StagingController` | `/api/v1/staging` | Status, start, stop, promote, promote-cancel, sync-preview, sync-definitions |
| `TestingController` | `/api/v1/testing` | Status, start, stop, reset, sync-preview, sync-definitions, report-result, pull-changes |
| `SystemController` | `/api/v1/system` | Health, auth-status, info, settings, execution-config, AI-providers, AI-provider-catalog, capabilities, backup, backups, remote-access, vacuum, browse, open-path, repair, client-errors, integration-snippet |
| `IntegrationController` | `/api/v1/integrations` | OAuth flow, credentials, actions, provider list, task export targets |
| `OnboardingProfileController` | `/api/v1/onboarding-profiles` | Onboarding profiles CRUD, reset, history |
| `ReportsController` | `/api/v1/reports` | Report generation and retrieval |
| `DocsController` | `/api/v1/docs` | Read Markdown documentation |

### Workflow Execution

The workflow engine executes blocks sequentially. The Claude CLI always runs **on the host** (not in containers) to avoid nested session detection.

**Flow:**
1. `WorkflowExecutor` creates a `WorkflowRun` (status: PENDING → RUNNING)
2. For each `BlockDefinition`, the appropriate `BlockExecutor` is selected
3. Claude CLI calls are executed on the host via `HostClaudeService` (`ProcessBuilder`)
4. Shell commands can optionally be executed via Docker Exec in a container
5. Results are stored as `StepResult`
6. Live logs are sent to the frontend via WebSocket (STOMP)
7. After completion, `RunCompletionHandler` evaluates gate status and TODO markers (all block outputs) and records template verification, activity log and DLQ entry
8. `WorkflowRunCompletedEvent` is fired → `AutoTodoService` reacts

**Controllable from step 1:** before submitting the run, `WorkflowExecutor` registers a provisional `ExecutionContext` in the `WorkflowExecutionRegistry`. A cancel therefore takes effect while the run is still waiting for a semaphore slot or for its container to start — the execution thread carries the cancel/pause flag over when it swaps in the real context. As a result `getActiveRunCount()` also counts queued runs, which stops `GhostRunWatchdogService` from releasing the permit of a starting run as a leak.

**Container row:** `ContainerService.createAndStartContainer` updates an existing `containers` row (upsert) instead of deleting and re-inserting it — the delete was un-scheduled during flush by the `Project.container` cascade, so the following INSERT failed on `UNIQUE(containers.project_id)`. The per-project lock now stays in the map (removing it under contention creates a second monitor), and `lastActivityAt` is stamped by a JPQL update (`ContainerRepository.touchActivity`) rather than a detached merge.

### Block Types (14 total)

| Type | Executor | Description |
|---|---|---|
| `simple` | `SimpleBlockExecutor` | Single Claude CLI call with prompt |
| `shell` | `ShellBlockExecutor` | Shell script, optionally via Docker container |
| `retry` | `RetryBlockExecutor` | Prompt with configurable retry attempts |
| `conditional` | `ConditionalBlockExecutor` | If/else branching based on condition |
| `foreach` | `ForEachBlockExecutor` | Iteration over a list of elements |
| `while` | `WhileBlockExecutor` | Repetition with exit condition and max iterations |
| `switch` | `SwitchBlockExecutor` | Multi-way branching per expression with cases |
| `agentic` | `AgenticBlockExecutor` | Goal→Reason→Act→Observe loop, up to 10 iterations |
| `approval` | `ApprovalBlockExecutor` | Pauses execution, waits for user confirmation |
| `sub-workflow` | `SubWorkflowBlockExecutor` | Executes another workflow as a subprocess |
| `skill-capability` | `SkillCapabilityBlockExecutor` | Invokes a named skill capability |
| `human-input` | `HumanInputBlockExecutor` | Collects user input via WebSocket, stores as context variable |
| `integration` | `IntegrationBlockExecutor` | Executes an action on an external integration provider |
| `document` | `DocumentBlockExecutor` | Extracts text from PDF/DOCX/XLSX/HTML/TXT via Apache Tika |

**Template variables** (`{{ variable }}`, `${varName}`) are resolved at runtime in the `ExecutionContext`. `{{ previous_output }}` contains the output of the previous block.

### Agentic Block (Goal→Reason→Act→Observe)

The agentic block implements an autonomous execution loop:

1. **REASON:** Claude analyzes the goal and previous observations → returns structured JSON action
2. **ACT:** Action is executed (shell or Claude CLI)
3. **OBSERVE:** Output is collected and added to the history
4. Loop runs until `done: true` or abort conditions are met

**Safety Mechanisms:**

| Config | Default | Description |
|---|---|---|
| `max_iterations` | 10 | Maximum number of iterations |
| `iteration_timeout_seconds` | 300 | Wall-clock timeout per iteration |
| `total_timeout_seconds` | 1800 | Total block timeout (30 min.) |
| `max_total_tokens` | 200000 | Token budget (input+output, all phases) |
| `max_repeated_actions` | 3 | Abort after N identical actions |
| `require_approval` | false | Approval gate before each action |

Additionally: history compression after 7 turns, action compression at >100,000 characters, token tracking warnings in the log. All 6 safety fields are pre-populated as defaults in the frontend "Add Block" dialog. Helper classes: `AgenticBlockConfig`, `AgenticSafetyGuard`, `AgenticTokenTracker`, `AgenticPlan`.

### Switch Block

The switch block evaluates an expression against multiple cases:
- Supports `${varName}` and `{{ varName }}` for variable interpolation
- Cases are checked in order — first match wins
- Pipe-separated alternatives: `"error|critical"` matches both
- `default` case when no other case matches
- Each case contains a list of nested blocks (shell or claude)
- Returns `COMPLETED` with `matchedCase: "none"` if no match and no default

### Sub-Workflow Block

- Executes another workflow as a nested block
- Circular references are detected via `__parent_workflow_ids`
- Maximum nesting depth: 5 levels
- Polling-based completion, 30-minute timeout
- `@Lazy` injection on `WorkflowExecutor` only where a caller starts runs itself (sub-workflow, chains, triggers); pure status queries (active runs, container ids, approvals) go straight to `WorkflowExecutionRegistry`

### Human-Input Block

- Pauses workflow execution and waits for user input
- Sends `human_input_required` event via WebSocket to `/topic/executions/{runId}/logs`
- User response is stored as a context variable (`variable_name`)
- Supports text and select inputs, validation patterns, timeout with optional default
- Uses the `ApprovalRequest` mechanism of the approval block

## Gate Status System

Workflow blocks can write quality gate markers into their output:

```
gate_status: GREEN|YELLOW|RED
gate_category: <category>
gate_description: <description>
```

**Processing:**
- `GateStatusOutputParser` — parses markers from block outputs (tolerant of Markdown formatting)
- `RunCompletionHandler` scans all block outputs — highest severity wins (GREEN < YELLOW < RED)
- Gate status is stored in `WorkflowRun.metadata` (`gate_status`, `gate_category`, `gate_description`)

**Gate → Run Status Mapping:**
- **YELLOW → `COMPLETED_WITH_ERRORS`:** Run is completed with this status instead of `COMPLETED`
- **RED → `COMPLETED`:** RED gates do not change the run status — `AutoTodoService` creates a TODO instead
- `GateStatusResolver` — derives gate status from run status when no explicit marker exists (`FAILED` → RED, `COMPLETED` → GREEN)

**AutoTodoService:**
- Reacts to `WorkflowRunCompletedEvent`
- RED gate → TODO with `HIGH` priority
- YELLOW gate → TODO with `MEDIUM` priority
- Source: `WORKFLOW`, linked to the run

## Workflow Chains

Chains group multiple workflows for sequential execution:

- **Cross-project:** A chain belongs to no project and can reference workflows from different projects
- **Sequential:** `ChainExecutor` executes entries in `sortOrder` order (virtual threads, polling every 2s, 180-min. timeout)
- **Context propagation:** Each entry receives chain-level context + entry-level context + `previous_chain_output` from the last completed workflow
- **Stop-on-Failure:** Flag at chain and entry level — chain stops only when both are `true`
- **Cancellation:** Via `ConcurrentHashMap<String, AtomicBoolean>`
- **WebSocket:** Status broadcasts to `/topic/chains/{chainRunId}/status`

### Chain Templates

Pre-built chain definitions composed from existing workflow templates:
- `ChainTemplateService` — manages built-in chain templates (`BuiltInChainTemplate` records)
- `ChainTemplateController` — `/api/v1/chain-templates` with list, get, install endpoints
- Install: Creates a new chain (optionally with associated workflows) in a target project

## Completion Triggers (V39)

Automatic workflow chaining based on completion events:
- `CompletionTriggerService` — manages trigger definitions
- `CompletionTriggerController` — `/api/v1/completion-triggers`
- Triggers link a source workflow (trigger) with a target workflow (started after completion)
- API: List, Get, Create, Enable/Disable, Delete; Filter by source or target workflow ID

## Todo Triggers — Self-Improvement Loop (V51/V52)

Automatic execution of fix workflows when TODOs are created — closes the self-improvement loop:

```
Audit Workflow → AutoTodoService → TODO (OPEN)
                                     ↓ TodoCreatedEvent
                             TodoTriggerService → Fix Workflow
                                                     ↓ WorkflowRunCompletedEvent
                                             TodoFeedbackListener → TODO (DONE)
```

- `TodoTriggerService` — `@TransactionalEventListener` on `TodoCreatedEvent`, matches TODOs against configured triggers (tag pattern AND semantics, priority filter, project filter), starts fix workflow with TODO data as context variables
- `TodoFeedbackListener` — `@EventListener` on `WorkflowRunCompletedEvent`, closes TODO on GREEN gate (when `autoClose=true`), logs escalation when fix attempts are exhausted
- `TodoTriggerController` — `/api/v1/todo-triggers`
- API: List, Get, Create, Enable/Disable, Delete
- **Loop Prevention**: 3 layers — metadata flag (`todoTriggerRun`), max attempts counter (`todo.fixAttempts`), running check (`todo.lastFixRunId`)
- **Context variables** for fix workflow: `todo_id`, `todo_title`, `todo_description`, `todo_tags`, `todo_priority`, `todo_project_id`, `todo_trigger_id`

## Project Cockpit & TODO Hygiene

Since ~96% of TODOs are machine-generated and scattered across many projects, there is a cross-project overview plus a cleanup mechanism.

- **Cockpit** (`GET /api/v1/todos/overview`, `TodoOverviewService`): delivers a rollup per project (status counts, `staleCount`, `userActive` vs. `machineActive`, `needsYouCount`) plus a global "Needs You" list (USER source ∨ CRITICAL ∨ overdue). All from lean GROUP BY projections (no TEXT blobs → pool-size=1-safe), frontend page `ProjectCockpit.tsx` (route `/cockpit`).
- **ARCHIVED status**: Storage alongside BACKLOG for "out of sight." Hidden from the default list (`TodoSpecifications.buildSpec` excludes ARCHIVED when no status filter is set), accessible via the archive tab / edit modal.
- **Hygiene** (`TodoHygieneService`): `archiveStaleNow(projectId)` moves stale auto-TODOs (OPEN/BACKLOG, source AI/WORKFLOW, tag `auto:workflow`, `updatedAt` older than threshold) to ARCHIVED — via bulk UPDATE in a short transaction. Cockpit button "Archive stale" (`POST /todos/archive-stale?projectId=`) is the primary, human-in-the-loop path. The `@Scheduled` job (daily 03:30) calls the same path but is **OFF** by default (`todo-hygiene-enabled`). Thresholds are runtime-configurable via `ExecutionSettingsService`: `todo-hygiene-enabled` [0,1], `todo-stale-days` [7,365], `todo-hygiene-max-per-run` [10,2000].
- **Bulk Status** (`POST /todos/bulk-status`): generic bulk UPDATE for many TODO IDs to a single status.

## Notification System (V40)

Notification configurations for workflow events:
- `NotificationService` — CRUD for notification configurations
- `NotificationController` — `/api/v1/notifications`
- API: List, Get, Create, Enable/Disable, Delete

## Assistant System (V41+V42)

Context-sensitive chat assistant for the platform. See also: [assistant-system.md](assistant-system.md)

- `AssistantService` — Chat logic, context preparation, Claude CLI call
- `AssistantSessionService` — Session persistence in DB (V41 migration: `assistant_sessions`, V58: `preview` column). Endpoints: `GET /sessions` (top 50), `GET /sessions/{id}` (detail), `DELETE /sessions/{id}`
- `AssistantContextBuilder` — injects last 5 sessions (user message + response) and up to 8 active workflow memories into the system prompt — on **all** pages
- `PageSuggestionService` — generates and caches context-sensitive UI suggestions per page (V42 migration: `page_suggestions`)
- `AssistantController` — `POST /api/v1/assistant/chat`, `GET /sessions`, `GET /sessions/{id}`, `DELETE /sessions/{id}`, `POST /pending-tools/{id}/confirm`, `POST /pending-tools/{id}/reject`
- `PageSuggestionController` — `GET /api/v1/assistant/suggestions?page=...`, `POST /api/v1/assistant/suggestions/refresh?page=...`
- Sessions are persisted after each chat call; `AssistantSessionUpdatedEvent` triggers page suggestion refresh
- Default model: `claude-sonnet-4-6`

## Workflow Memory

Workflows can accumulate knowledge across runs:
- `WorkflowMemoryService` — extracts knowledge from completed runs
- `ClaudeSummarizer` / `CodexSummarizer` — AI-based summarization
- `KnowledgeSummarizer` — Interface for summarization backends
- `HostCodexService` — runs OpenAI Codex CLI on the host (analogous to `HostClaudeService`)
- `SummarizationSetupService` — Configuration and setup of summarization backends
- `ConversationPreprocessor` — Preprocessing of conversation data for summarization
- Active memory entries are injected as context into subsequent runs
- `AutoMemoryExtractionListener` — automatic extraction after successful runs, configurable per workflow metadata (`autoMemoryExtraction.enabled`, `autoMemoryExtraction.interval`)
- `WorkflowMemory` entity (V16 migration)

## Agent Instructions System

Two-level instruction injection via Claude CLI `--append-system-prompt` / `--system-prompt`:

1. **Base instructions** (`src/main/resources/agent-instructions.md`) — loaded at startup by `AgentInstructionsService`, apply to all workflows
2. **Workflow-specific instructions** (`workflow.instructions`) — per workflow, editable in the UI (Monaco editor)

Injection paths:
- **Block execution:** Instructions via `--append-system-prompt` on host Claude CLI (`HostClaudeService.executeAction()`)
- **Agentic REASON:** System prompt via `--system-prompt` to `HostClaudeService.reasonStructured()`, including workflow instructions in `## Workflow Instructions`

## Host Claude Service

All Claude CLI calls are executed on the host (not in containers):
- `HostClaudeService.executeAction()` — ACT phase, uses `--append-system-prompt`, `--model` flag, project working directory
- `HostClaudeService.reasonStructured()` — REASON phase, uses `--system-prompt`, `--max-turns 3`, no model flag
- Prompt is written via **stdin** to the process (`writePromptToStdin()`) — avoids Linux ARG_MAX limit
- `CLAUDE_CODE` and `CLAUDECODE` environment variables are removed from the process environment (prevents nested session errors)
- `CommandExecutorService` provides `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `writeSkillContextToProject()` — all overloads use the `HostExecutionRequest` builder API
- Skill context is injected file-based: `writeSkillContextToProject()` writes `.claude/rules/skills-context.md` into the project directory (replaces the old CLI process-based method `injectSkillContextOnHost()`)

## Provider Routing

All block executors (simple, retry, conditional, foreach, while, switch, skill-capability, integration) pass `context.getProvider()` as `providerId` via `HostExecutionRequest.builder()` to the `CommandExecutorService`. Setting `"provider": "CODEX"` in a workflow JSON therefore applies to every non-shell block type.

`AiProviderRegistry.resolve()` falls back to the configured default provider (not hardcoded CLAUDE) when an unknown provider ID is passed.

## Per-Block Skills

Blocks can bring their own skills via the `skillIds` field, which apply additively to the workflow-level skills:

- `BlockDefinition.skillIds` — optional list of skill IDs that are activated only for this block
- `WorkflowExecutor` merges workflow-level skills + block-level skills before each block execution
- After block execution, the baseline skill state is restored
- Skill context is provided file-based via `.claude/rules/skills-context.md` in the project directory (`CommandExecutorService.writeSkillContextToProject()`)
- Frontend: Skill picker toggles in the add/edit block modals, zap icon indicator on `SortableBlock`

## Model Selection

- `workflow.model` field (e.g. `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5`)
- Passed via `--model` flag to the Claude CLI on the host
- The agentic REASON phase uses the locally configured default model

## Per-Call Token Limit

- `HostClaudeConfig.maxTokens` (property `automation.host-claude.max-tokens`) — optional output token limit per call
- Applied via `--max-tokens` flag in `reasonStructured()` and `executeAction()`
- Distinct from the agentic block's `max_total_tokens` budget (which tracks cumulative input+output across all iterations)

## Per-Block Claude CLI Timeout

- All block types support an optional `timeout_seconds` field in the block config
- Overrides the global `automation.host-claude.timeout-seconds` value (default: 300s) for this block
- Without `timeout_seconds` in the config → global default applies
- For dual-mode blocks (shell/claude), `timeout_seconds` controls both the shell and Claude CLI timeout
- Applies to `reasonStructured()` and `executeAction()` in agentic blocks

## Workflow Conversion

Successful agentic runs can be converted into deterministic workflows:
- `WorkflowConversionService` — analyzes the run and proposes a structured workflow (rule-based + AI)
- `ConversionController` — API endpoints for preview and creation
- Frontend: Conversion dialog in the execution view

### Dialog-to-Workflow Conversion

Claude Code sessions can also serve as templates for workflows:
- `DialogSessionScanner` — scans `~/.claude/projects/` for JSONL session files
- `DialogConversionService` — analyzes a session with Claude and generates a workflow suggestion
- `ClaudeJsonlParser` — parses JSONL session files
- `DialogController` — `/api/v1/dialog` with session list, analysis and workflow creation
- Path traversal protection: only paths under `~/.claude/projects/` with `.jsonl` extension are allowed

## Workflow Templates

- Templates are stored in the `workflow_templates` DB table (V19 migration), not hardcoded
- **66 built-in templates** are seeded at startup by `WorkflowTemplateSeeder` (idempotent, inserts missing ones, updates on version bump)
- Template definitions are stored as individual JSON files in `src/main/resources/templates/builtin/` (one file per template, loaded by `BuiltInTemplateLoader`)
- Name and description are i18n JSON maps: `{"de":"...","en":"..."}` — stored via `JsonStringMapConverter`
- Built-in templates (`builtIn=true`) cannot be deleted; only `sortOrder` and `category` are updatable
- Users can create custom templates via API or by saving an existing workflow
- `workflow.templateId` — optional back-reference to the source template
- Frontend resolves localized template names via `resolveTemplateName()` in `utils/templateUtils.ts`
- CRUD API at `/api/v1/workflow-templates`

## Built-in Skills

- Skills are stored in the `skills` DB table, not hardcoded in frontend or in-memory beans
- **19 built-in skills** are seeded at startup by `SkillSeeder` (idempotent)
- Skill definitions are in `BuiltInSkills` (order) and `BuiltIn<Category>Skills` (`config/` package)
- Built-in skills have stable IDs (e.g. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` also registers all skills in the in-memory `SkillRegistry` for capability lookups
- `SkillRegistry` no longer depends on `SkillProvider` beans — the seeder handles registration
- Capability handler classes (e.g. `LintHandler`, `OCRHandler`, `SecurityScanHandler`, `FactCheckHandler`, `GrammarCheckHandler`) in `skills/handlers/` (subdirectories `code/`, `document/`)
- Users can create custom skills via import API (`POST /api/v1/skills/import`)
- `Skill.java` entity uses `@PrePersist ensureId()` (instead of `@GeneratedValue`) for explicit IDs on built-ins

## Template Verification

- `TemplateVerification` entity (V22 migration) — tracks the quality status of workflow templates
- `TemplateVerificationService` — manages verification status per template+project pair
- Workflows created from templates can be test-started to verify template correctness
- Verification results are visible in template CRUD API responses

## Workflow Factory

Automatic generation and verification of workflows from a topic:
- `WorkflowFactoryService` — orchestrates the entire factory process
- `ClaudeWorkflowGenerator` / `CodexWorkflowGenerator` — AI-based workflow generation
- `WorkflowTextGenerator` — text-based workflow generation
- `FactorySetupService` — configuration and setup of the factory environment
- `WorkflowFactoryController` — `/api/v1/factory` with endpoints:
  - `POST /generate` — generate workflow from topic
  - `POST /run` — run generated workflow on test instance
  - `POST /verify/{id}` — verify workflow
  - `POST /promote/{id}` — promote workflow to production
  - `GET /topics` — list available topics
- Uses the test instance (port 8090) for safe execution

## Workflow Pool

Pre-built workflow definitions from the `workflow-pool/` directory:
- `WorkflowPoolService` — reads JSON files from the configured pool directory
- `WorkflowPoolController` — `/api/v1/pool` with endpoints:
  - `GET /` — list all pool workflows
  - `GET /{id}` — get a single pool workflow
  - `POST /{id}/install` — import workflow from pool into target project
  - `POST /export/{workflowId}` — export workflow to the pool

## Wishes (Wish System)

Natural language wishes are automatically converted into workflows/chains:
- `WishService` — creates wishes, handles user responses (`respond`), crystallizes into templates
- `WishFulfillmentLoop` — asynchronous meta-loop (decompose → execute → evaluate) incl. cancel flags and total timeout
- `WishEvaluationService` — evaluation phase (AI verdict → AWAITING_APPROVAL / REFINING / FAILED)
- `WishWatchdogService` — monitors running wishes for stale/timeout states
- Iterative process: Decomposition → Workflow/Chain generation → Execution → Feedback
- Supports up to `max_iterations` iterations for improvements
- `crystallize()` — promotes successful wish as template

## Integration System (V55/V56)

Plugin framework for external services:
- `IntegrationRegistry` — auto-discovery of all `IntegrationProvider` beans
- `IntegrationOAuthService` — OAuth2 flow (Authorization Code Grant)
- `IntegrationEncryptionService` — credential encryption (AES-GCM)
- `IntegrationConnection` entity — connection status (CONNECTED, DISCONNECTED, EXPIRED)
- `IntegrationController` — `/api/v1/integrations` with OAuth callback, credentials, actions
- 7 providers in subdirectories: Confluence, GitHub, GoogleCalendar, GoogleGmail, Jira, Taskwarrior, Trello
- `IntegrationBlockExecutor` — executes provider actions as workflow blocks
- `TaskExportCapable` — interface for providers that support TODO export

## Dead Letter Queue (V63)

Failed asynchronous operations are stored in a dead letter queue:
- `DeadLetterService` — management of dead letter entries (CRUD, retry, discard)
- `DeadLetterController` — `/api/v1/dlq`
- `DeadLetterEntry` entity with status: OPEN, RETRIED, DISCARDED

## Workflow Search

Aggregated search across workflows with multiple search strategies:
- `SemanticWorkflowSearchService` — substring/fuzzy search + semantic ranking via AI
- `SearchController` — `/api/v1/search`

## Docs System

Backend reads Markdown documentation files and serves them as an API:
- `DocsController` — `/api/v1/docs`, reads `.md` files from the configured `docs/` directory
- Path traversal protection built in
- Frontend displays docs in split layout with Markdown rendering

## Entities

All JPA entities and enums in the `com.automation.entity` package:

| Entity | Table | Description |
|---|---|---|
| `ActivityLog` | `activity_log` | System/execution log entries with level, category, metadata |
| `AssistantSession` | `assistant_sessions` | Persisted chat sessions of the assistant |
| `BlockDefinition` | *(embedded)* | Block definition with type, config, required capabilities (value object) |
| `Capability` | `capabilities` | Skill capabilities with schema and handler reference |
| `ChainEntry` | *(embedded)* | Entry in a chain with workflow reference and context (value object) |
| `ChainRun` | `chain_runs` | Execution instance of a chain with entry runs |
| `ChainRunEntry` | *(embedded)* | Status of a single chain entry during execution (value object) |
| `CompletionTrigger` | `completion_triggers` | Source→target workflow link on completion |
| `DeadLetterEntry` | `dead_letter_entries` | Failed messages in the dead letter queue |
| `IntegrationConnection` | `integration_connections` | Connection to external service (OAuth, credentials) |
| `TodoTrigger` | `todo_triggers` | Automatic fix workflow execution on TODO creation (self-improvement loop) |
| `Container` | `containers` | Docker container per project with status and mounts |
| `NotificationConfig` | `notification_configs` | Notification configuration (webhook/desktop) |
| `OnboardingProfile` | `onboarding_profiles` | Onboarding profile with type (BASE/PROFILE) |
| `OnboardingProfileHistory` | `onboarding_profile_history` | Change history of an onboarding profile |
| `PageSuggestion` | `page_suggestions` | Cached UI suggestions per page |
| `Project` | `projects` | Project with type, path, status, AI context |
| `ScheduledWorkflow` | `scheduled_workflows` | Cron/once/delayed schedule for workflows and chains |
| `Skill` | `skills` | Skill with category, capabilities, configuration schema |
| `StepResult` | `step_results` | Result of a single workflow step with context delta |
| `TemplateVerification` | `template_verifications` | Quality status of a template per project |
| `Todo` | `todos` | Task with status, priority, source, tags |
| `WebhookTrigger` | `webhook_triggers` | Webhook trigger for workflows with secret token |
| `Wish` | `wishes` | Natural language wish with decomposition and iteration history |
| `Workflow` | `workflows` | Workflow with blocks, instructions, model, provider (soft delete) |
| `WorkflowChain` | `workflow_chains` | Chain with sorted entries and stop-on-failure |
| `WorkflowMemory` | `workflow_memory` | Extracted knowledge from workflow runs |
| `WorkflowRun` | `workflow_runs` | Execution instance of a workflow with steps and metadata |
| `WorkflowTemplate` | `workflow_templates` | Template with i18n name/description and blocks |
| `WorkflowVersion` | `workflow_versions` | Versioned workflow snapshots |

**Enums:**

| Enum | Values |
|---|---|
| `ContainerStatus` | CREATED, RUNNING, STOPPED, ERROR |
| `GateStatus` | GREEN, YELLOW, RED |
| `ProjectStatus` | STOPPED, STARTING, RUNNING, ERROR |
| `ProjectType` | CODE, CONTENT, RESEARCH, BUSINESS, PERSONAL |
| `RunStatus` | PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED, INTERRUPTED |
| `SkillCategory` | DOCUMENT, CODE, DATA, CONTENT, RESEARCH, BUSINESS, PRODUCTIVITY |
| `StepStatus` | PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, WAITING_APPROVAL |
| `WishStatus` | RECEIVED, DECOMPOSING, AWAITING_DECOMPOSE_APPROVAL, EXECUTING, EVALUATING, REFINING, AWAITING_CLARIFICATION, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED |
| `Todo.Status` | OPEN, IN_PROGRESS, DONE, BACKLOG, ARCHIVED (ARCHIVED = "out of sight" — hidden from the default list, dedicated archive tab; target of hygiene/bulk archiving) |
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

## Frontend Architecture

### Component Structure

```
App.tsx (Router)
├── Layout
│   ├── Sidebar (Navigation, Collapse)
│   └── Header (Language, Theme)
│
├── Pages (28 Routes)
│   ├── Dashboard, Projects, ProjectDetail, ProjectCockpit
│   ├── Workflows, WorkflowDetail, WorkflowExecution, WorkflowDebug, WorkflowFactory
│   ├── Skills, Schedules, Calendar, Webhooks, Integrations, Settings
│   ├── Runs, ActivityLog, Todos, DeadLetterQueue
│   ├── Chains, ChainExecution
│   ├── Wishes, Docs, Reports, Insights, OnboardingProfiles, DialogImport
│   ├── Simple* variants (SimpleDashboard, SimpleWorkflows, etc.)
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

### Data Flow

```
User Interaction
    ↓
React Component
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
Response (DTO) → React Query Cache → UI Update
```

## WebSocket (Live Logs + Status)

- **Protocol:** STOMP over SockJS
- **Endpoint:** `/ws` (SockJS handshake)
- **Broker:** Simple Message Broker on `/topic`
- **Topics:**
  - `/topic/executions/{runId}/logs` — Workflow execution logs
  - `/topic/chains/{chainRunId}/status` — Chain execution status
  - `/topic/activity-log` — Live activity log updates
  - `/topic/wishes/{wishId}/status` — Wish status updates
  - `/topic/assistant/{requestId}/status` — Assistant chat status (per request)
  - `/topic/assistant/{sessionId}/events` — Assistant push events (per session)
- **Message format:** `{ timestamp, level, message }`
- **Terminal WebSocket:** `/ws/terminal/**` for real-time container access (raw WebSocket via `RawWebSocketConfig`, handler: `TerminalWebSocketHandler`)

## Security

### API Key Authentication

- **Header:** `X-API-Key`
- **Filter:** `ApiKeyAuthFilter` (before Spring Security)
- **Comparison:** Constant-time (`MessageDigest.isEqual`)
- **Auto-generation:** Key is stored in `app_settings` on first startup
- **Disableable:** `automation.security.enabled=false` (development)

### Public Endpoints (no API key required)

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `GET /api/v1/system/auth-status`
- `GET /api/v1/system/integration-snippet`
- `POST /api/v1/webhook/trigger/{token}`
- `/api/v1/integrations/*/callback` (OAuth2 Callbacks)
- `/ws/**` (WebSocket)
- `/actuator/health`

### Rate Limiting

- **Filter:** `RateLimitFilter` (reads from `RateLimitConfig` bean)
- **Algorithm:** Sliding window (1 minute)
- **Default:** 120 requests/minute per client IP
- **Response header:** `X-RateLimit-Remaining`
- **Runtime-configurable:** Settings → Execution → API rate limit (toggle + RPM value)
- **Env override:** `AUTOMATION_RATE_LIMIT_ENABLED` / `AUTOMATION_RATE_LIMIT_RPM`
- **Webhook rate limiter:** `WebhookRateLimiter` — separate per-IP limiter for `/api/v1/webhook/trigger/**`, always active

### Request Size Limit

- **Filter:** `ActivityLogSizeLimitFilter`
- **Max:** 64 KB (configurable via `automation.activity-log.max-request-bytes`)
- Only for `/api/**` paths

### WebSocket Authentication

- **Interceptor:** `WebSocketAuthInterceptor` — validates API key during WebSocket handshake

### CORS

- Origin patterns configurable via `automation.cors.allowed-origins` (default: localhost:3000/5173 + Tailscale patterns)
- All HTTP methods (GET, POST, PUT, PATCH, DELETE, OPTIONS)
- Credentials allowed

## Database Migrations (Flyway)

The DB migrations (`src/main/resources/db/migration/`) document the feature timeline:

| Migration | Feature |
|---|---|
| V1–V9 | Base tables: Projects, Containers, Skills, Capabilities, Workflows, Runs, Schedules, Webhooks, Timestamps |
| V10–V11 | Performance indexes, container last activity |
| V12 | App settings |
| V13 | Workflow model + instructions |
| V14 | Schedule types + scheduled-at |
| V15 | Activity log table |
| V16 | Workflow memory table |
| V17 | Todos table |
| V18 | Project subtitle + description |
| V19 | Workflow templates table |
| V20 | Input context in workflow runs |
| V21 | Workflow chains |
| V22 | Template quality status |
| V23 | Default context in workflows |
| V24 | Activity log composite index |
| V25 | Workflow test status |
| V26 | Block types column |
| V27 | Chain support for schedules |
| V28–V29 | Wishes + wish reference workflow IDs |
| V30 | Project AI context |
| V31 | Workflow ID in activity log |
| V32 | Workflow soft delete (deleted_at) |
| V33 | Wish iteration history |
| V34 | Workflow provider |
| V35 | Project archiving (archived_at) |
| V36 | Todo session ID |
| V37 | Delayed schedule type |
| V38 | Scheduled workflow ID nullable |
| V39 | Completion triggers table |
| V40 | Notification configs table |
| V41 | Assistant sessions table |
| V42 | Page suggestions table |
| V43 | Triggered-by schedule ID |
| V44 | Schedule end conditions |
| V45 | Schedule conflict behavior |
| V46 | Stable synced-at |
| V47 | Context delta in step results |
| V48 | Related todo ID |
| V49 | Onboarding profiles |
| V50 | Workflow versions |
| V51 | Todo triggers table |
| V52 | Fix attempts + last fix run ID on TODOs |
| V53 | Process backlog flag on todo triggers |
| V54 | Composite indexes on todos |
| V55 | Integration connections table |
| V56 | Client credentials on connections |
| V57 | Due date on todos |
| V58 | Assistant session preview column |
| V59 | Linked workflow on todos |
| V60 | Workflow name in runs |
| V61 | Workflow tags and category |
| V62 | Auto-refine on wishes |
| V63 | Dead letter entries table |
| V64 | Writes flag on capabilities |
| V65 | Catch-up-on-resume on schedules |
| V66 | Todo export link |

## Directory Structure

```
autotrigger/
├── CLAUDE.md                          ← Project instructions for Claude Code
├── VISION.md                          ← Pointer to docs/vision.md
├── docs/                              ← These documentation files
├── workflow-pool/                     ← Pre-built workflow definitions (JSON)
├── scripts/
│   ├── release.sh                     ← Version bump + git tag
│   ├── staging.sh                     ← Manage stable/test environment
│   └── start-autotrigger-dev.sh       ← Start dev environment
│
├── ai-automation-backend/
│   ├── pom.xml
│   ├── docker-compose.yml
│   ├── docker-compose.stable.yml      ← Stable container
│   ├── docker-compose.test.yml        ← Test container
│   ├── docker/
│   │   ├── Dockerfile.backend
│   │   ├── Dockerfile.stable          ← Ubuntu-based (glibc for Claude CLI)
│   │   ├── Dockerfile.agent-node
│   │   ├── Dockerfile.agent-python
│   │   ├── Dockerfile.agent-go
│   │   ├── Dockerfile.agent-devops
│   │   └── Dockerfile.agent-java
│   ├── data/                          ← SQLite DB (gitignored)
│   └── src/main/
│       ├── java/com/automation/       ← Java source files
│       └── resources/
│           ├── application.yml
│           ├── application-stable.yml ← Stable profile
│           ├── application-prod.yml   ← Production profile
│           ├── application-test.yml   ← Test profile
│           ├── application-dev.yml    ← Dev profile
│           ├── agent-instructions.md  ← Base agent instructions
│           ├── templates/builtin/     ← Built-in template JSON files
│           └── db/migration/          ← 66 Flyway migrations (V1–V66)
│
└── ai-automation-frontend/
    ├── package.json
    ├── vite.config.ts
    ├── tailwind.config.ts
    ├── eslint.config.js               ← Flat config (ESLint 9+)
    ├── index.html
    └── src/
        ├── App.tsx                    ← Router
        ├── main.tsx                   ← Entry point
        ├── index.css                  ← Global styles
        ├── api/client.ts              ← HTTP client (singleton)
        ├── types/index.ts             ← TypeScript types
        ├── pages/                     ← 37 pages (incl. simple mode variants)
        ├── components/
        │   ├── ui/                    ← Base UI primitives
        │   ├── layout/                ← App shell, sidebar, navigation
        │   ├── shared/                ← StatusBadge, LogViewer, etc.
        │   ├── dashboard/             ← Dashboard widgets
        │   ├── workflow/              ← Block editor, templates
        │   ├── assistant/             ← AI assistant panel
        │   ├── calendar/              ← Calendar view
        │   ├── factory/               ← Workflow factory builder
        │   ├── insights/              ← Analytics/insights
        │   ├── simple/                ← Simple mode components
        │   ├── todo/                  ← TODO management
        │   └── wishes/ etc.           ← Additional feature components
        ├── hooks/                     ← TanStack Query hooks
        ├── stores/                    ← Zustand stores
        ├── i18n/                      ← DE/EN translations
        ├── theme/                     ← Theme tokens
        ├── data/                      ← Skill constants
        ├── utils/                     ← Utility functions
        └── test/                      ← MSW handlers, test setup
```
