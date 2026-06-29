<!-- last-sync: 2026-06-29 from: docs/architektur.md -->

> *Last updated: [auto-generated]*

# Architecture

## System overview

```
┌─────────────────────────────────────────────────────────┐
│                      Browser                            │
│  React 18 PWA (Vite dev server :3000 / static)          │
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

## Three environments

| Environment | Port | Description |
|---|---|---|
| **Dev** | 3000 (Frontend) / 9090 (Backend) | Development server, from source code |
| **Stable** | 9091 | Docker container, backend + embedded frontend |
| **Test** | 8090 | Docker container, dedicated test instance |

Dev automatically starts Stable and Test on boot if a snapshot exists (configurable via `automation.staging.auto-start` or `automation.staging.test-auto-start`).

## Backend architecture

### Layer model

```
Controller (REST API, 26 controllers)
    ↓ DTO (Request/Response Records)
Service (business logic)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (file: data/automation.db)
```

### Packages

```
com.automation
├── controller/          30 REST controllers
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
│   ├── trigger/         CompletionTriggerService, TodoTriggerService, TodoFeedbackListener
│   ├── health/          HealthAutoTodoService
│   ├── integration/     IntegrationRegistry, IntegrationOAuthService, IntegrationEncryptionService, IntegrationBlockExecutor, 5 providers
│   ├── onboarding/      OnboardingProfileService, ProjectOnboardingService, OnboardingProfileSeeder
│   ├── repair/          AutoRepairService
│   ├── tray/            SystemTrayService
│   ├── activitylog/     ActivityLogService, ActivityLogLifecycleListener
│   ├── AssistantService.java        Chat assistant (Claude CLI)
│   ├── AssistantSessionService.java Session persistence
│   └── PageSuggestionService.java   Context-sensitive UI suggestions
├── entity/              JPA entities + enums
├── dto/
│   ├── request/         Request records
│   └── response/        Response records
├── repository/          Spring Data repositories
├── mapper/              MapStruct mappers (Entity ↔ DTO)
├── executor/            Workflow execution engine
│   └── blocks/          12 block executors
├── config/              Configuration classes, seeders (Skill, Template)
├── security/            ApiKeyAuthFilter, RateLimitFilter
├── exception/           GlobalExceptionHandler + exception classes
├── websocket/           WebSocket configuration
├── event/               WorkflowRunCompletedEvent, AssistantSessionUpdatedEvent
└── util/                JPA converters (JSON, BlockDefinitions)
```

### All 30 REST controllers

| Controller | Base path | Description |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | CRUD, archiving, container lifecycle, context generation |
| `WorkflowController` | `/api/v1/workflows` | CRUD, execution, export/import, skills, optimize, repair |
| `ExecutionController` | `/api/v1/executions` | Pause, Resume, Resume-from-Step, Cancel, Retry, Approve, Todos-Approve, Command |
| `SkillController` | `/api/v1/skills` | CRUD, capabilities, export/import |
| `ScheduleController` | `/api/v1/schedules` | CRUD, Enable/Disable, Runs, Calendar, Preview, Remaining-Stats, Chain-Schedules, Maintenance |
| `WebhookController` | `/api/v1/webhooks` | CRUD, Enable/Disable; trigger via `/api/v1/webhook/trigger/{token}` |
| `ChainController` | `/api/v1/chains` | CRUD, execution, runs |
| `ChainTemplateController` | `/api/v1/chain-templates` | List, retrieve, install as chain |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | CRUD, create from workflow |
| `WorkflowMemoryController` | `/api/v1/workflows/{id}/memory` | CRUD, extract, activate |
| `WorkflowFactoryController` | `/api/v1/factory` | Generate, Run, Verify, Promote, Topics |
| `WorkflowPoolController` | `/api/v1/pool` | List, retrieve, install |
| `ConversionController` | `/api/v1/conversions` | Suggest, create workflow from run |
| `DialogController` | `/api/v1/dialog` | Sessions, projects, Claude session analysis, workflow creation |
| `LearningController` | `/api/v1/learning` | Dashboard, Stats, Step-Analytics, Suggestions, Similar, Agentic-History, Agentic-Analytics, Tool-Patterns, Execution-Insights, Run-Insights, Rate |
| `TodoController` | `/api/v1/todos` | CRUD, Status-Patch, Count, Overview (Cockpit), Bulk-Status, Archive-Stale |
| `ActivityLogController` | `/api/v1/activity-log` | List |
| `AssistantController` | `/api/v1/assistant` | Chat (`POST /chat`) |
| `PageSuggestionController` | `/api/v1/assistant` | Suggestions per Page (Get/Refresh) |
| `CompletionTriggerController` | `/api/v1/completion-triggers` | CRUD, Enable/Disable, Filter by Workflow |
| `TodoTriggerController` | `/api/v1/todo-triggers` | CRUD, Enable/Disable, Filter by Target-Workflow |
| `NotificationController` | `/api/v1/notifications` | CRUD, Enable/Disable |
| `WishController` | `/api/v1/wishes` | CRUD, Cancel, Respond, Crystallize |
| `StagingController` | `/api/v1/staging` | Status, Start, Stop, Promote, Promote-Cancel, Sync-Preview, Sync-Definitions |
| `TestingController` | `/api/v1/testing` | Status, Start, Stop, Reset, Sync-Preview, Sync-Definitions, Report-Result, Pull-Changes |
| `SystemController` | `/api/v1/system` | Health, Auth-Status, Info, Settings, Execution-Config, AI-Providers, Backup, Backups, Remote-Access, Browse, Open-Path, Repair, Client-Errors |
| `IntegrationController` | `/api/v1/integrations` | OAuth flow, credentials, actions, provider list |
| `OnboardingProfileController` | `/api/v1/projects/{id}/onboarding` | Onboarding profile CRUD, Enable/Disable, Preview |
| `ReportsController` | `/api/v1/reports` | Report generation and retrieval |
| `DocsController` | `/api/v1/docs` | Read Markdown documentation |

### Workflow execution

The workflow engine executes blocks sequentially. Claude CLI **always runs on the host** (not in containers) to avoid nested session detection.

**Flow:**
1. `WorkflowExecutor` creates a `WorkflowRun` (status: PENDING → RUNNING)
2. For each `BlockDefinition`, the matching `BlockExecutor` is selected
3. Claude CLI calls run on the host via `HostClaudeService` (`ProcessBuilder`)
4. Shell commands can optionally be executed via Docker Exec in the container
5. Results are stored as `StepResult`
6. Live logs are sent to the frontend over WebSocket (STOMP)
7. After completion, `WorkflowExecutor` evaluates gate status (all block outputs)
8. `WorkflowRunCompletedEvent` is fired → `AutoTodoService` reacts

### Block types (12 total)

| Type | Executor | Description |
|---|---|---|
| `simple` | `SimpleBlockExecutor` | Single Claude CLI call with prompt |
| `shell` | `SimpleBlockExecutor` (dual-mode) | Shell script or Claude CLI |
| `retry` | `RetryBlockExecutor` | Prompt with configurable retry attempts |
| `conditional` | `ConditionalBlockExecutor` | If/else branch based on condition |
| `forEach` | `ForEachBlockExecutor` | Iteration over a list of elements |
| `while` | `WhileBlockExecutor` | Repetition with abort condition and max iterations |
| `switch` | `SwitchBlockExecutor` | Multi-way branching by expression with cases |
| `agentic` | `AgenticBlockExecutor` | Goal→Reason→Act→Observe loop, up to 10 iterations |
| `approval` | `ApprovalBlockExecutor` | Pauses execution, waits for user confirmation |
| `sub-workflow` | `SubWorkflowBlockExecutor` | Executes another workflow as a subprocess |
| `skill-capability` | `SkillCapabilityBlockExecutor` | Calls a named skill capability |
| `human-input` | `HumanInputBlockExecutor` | Collects user input via WebSocket, stores it as a context variable |

**Template variables** (`{{ variable }}`, `${varName}`) are resolved at runtime in the `ExecutionContext`. `{{ previous_output }}` contains the output of the previous block.

### Agentic Block (Goal→Reason→Act→Observe)

The agentic block implements an autonomous execution loop:

1. **REASON:** Claude analyzes the goal and previous observations → returns structured JSON action
2. **ACT:** Action is executed (shell or Claude CLI)
3. **OBSERVE:** Output is collected and added to the history
4. Loop runs until `done: true` or abort conditions

**Safety mechanisms:**

| Config | Default | Description |
|---|---|---|
| `max_iterations` | 10 | Maximum number of iterations |
| `iteration_timeout_seconds` | 300 | Wall-clock timeout per iteration |
| `total_timeout_seconds` | 1800 | Total block timeout (30 min.) |
| `max_total_tokens` | 200000 | Token budget (input+output, all phases) |
| `max_repeated_actions` | 3 | Abort after N identical actions |
| `require_approval` | false | Approval gate before each action |

Additionally: history compression after 7 turns, token tracking warnings in the log. All 6 safety fields are prefilled as defaults in the frontend "Add block" dialog.

### Switch Block

The switch block evaluates an expression against multiple cases:
- Supports `${varName}` and `{{ varName }}` for variable interpolation
- Cases are checked in order — first match wins
- Pipe-separated alternatives: `"error|critical"` matches both
- `default` case if no other case matches
- Each case contains a list of nested blocks (shell or claude)
- Returns `COMPLETED` with `matchedCase: "none"` if there is no match and no default

### Sub-Workflow Block

- Executes another workflow as a nested block
- Circular references are detected via `__parent_workflow_ids`
- Maximum nesting depth: 5 levels
- Polling-based completion, 30-minute timeout
- `@Lazy` injection on `WorkflowExecutor` to break Spring DI cycles

### Human-Input Block

- Pauses workflow execution and waits for user input
- Sends `human_input_required` event via WebSocket to `/topic/executions/{runId}/logs`
- User response is stored as a context variable (`variable_name`)
- Supports text and select inputs, validation patterns, timeout with optional default
- Uses the `ApprovalRequest` mechanism of the approval block

## Gate Status System

Workflow blocks can write quality gate markers to their output:

```
gate_status: GREEN|YELLOW|RED
gate_category: <category>
gate_description: <description>
```

**Processing:**
- `GateStatusOutputParser` — parses markers from block outputs (tolerant of Markdown formatting)
- `WorkflowExecutor` scans all block outputs — highest severity wins (GREEN < YELLOW < RED)
- Gate status is stored in `WorkflowRun.metadata` (`gate_status`, `gate_category`, `gate_description`)

**Gate → run status mapping:**
- **YELLOW → `COMPLETED_WITH_ERRORS`:** run is ended with this status instead of `COMPLETED`
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
- **Sequential:** `ChainExecutor` executes entries in `sortOrder` order (virtual threads, polling every 2s, 30-min. timeout)
- **Context forwarding:** Each entry receives chain-level context + entry-level context + `previous_chain_output` from the last completed workflow
- **Stop-on-failure:** Flag at chain and entry level — chain stops only if both are `true`
- **Cancellation:** Via `ConcurrentHashMap<String, AtomicBoolean>`
- **WebSocket:** Status broadcasts to `/topic/chains/{chainRunId}/status`

### Chain Templates

Prebuilt chain definitions composed from existing workflow templates:
- `ChainTemplateService` — manages built-in chain templates (`BuiltInChainTemplate` records)
- `ChainTemplateController` — `/api/v1/chain-templates` with list, get, install endpoints
- Install: creates a new chain (optionally with associated workflows) in a target project

## Completion Triggers (V39)

Automatic chaining of workflows based on completion events:
- `CompletionTriggerService` — manages trigger definitions
- `CompletionTriggerController` — `/api/v1/completion-triggers`
- Triggers link a source workflow (trigger) to a target workflow (started after completion)
- API: List, Get, Create, Enable/Disable, Delete; filter by source or target workflow ID

## Todo Triggers — Self-Improvement Loop (V51/V52)

Automatic execution of fix workflows when TODOs are created — closes the self-improvement loop:

```
Audit workflow → AutoTodoService → TODO (OPEN)
                                     ↓ TodoCreatedEvent
                             TodoTriggerService → Fix workflow
                                                     ↓ WorkflowRunCompletedEvent
                                             TodoFeedbackListener → TODO (DONE)
```

- `TodoTriggerService` — `@TransactionalEventListener` on `TodoCreatedEvent`, matches TODOs against configured triggers (tag pattern AND semantics, priority filter, project filter), starts fix workflow with TODO data as context variables
- `TodoFeedbackListener` — `@EventListener` on `WorkflowRunCompletedEvent`, closes TODO on GREEN gate (if `autoClose=true`), logs escalation when fix attempts are exhausted
- `TodoTriggerController` — `/api/v1/todo-triggers`
- API: List, Get, Create, Enable/Disable, Delete
- **Loop prevention**: 3 layers — metadata flag (`todoTriggerRun`), max-attempts counter (`todo.fixAttempts`), running check (`todo.lastFixRunId`)
- **Context variables** for fix workflow: `todo_id`, `todo_title`, `todo_description`, `todo_tags`, `todo_priority`, `todo_project_id`, `todo_trigger_id`

## Project Cockpit & TODO Hygiene

Because about 96% of TODOs are machine-generated and spread across many projects, there is a cross-project overview plus a cleanup mechanism.

- **Cockpit** (`GET /api/v1/todos/overview`, `TodoOverviewService`): returns a rollup per project (status counts, `staleCount`, `userActive` vs. `machineActive`, `needsYouCount`) plus a global "Needs you" list (USER source ∨ CRITICAL ∨ overdue). Everything from lean GROUP BY projections (no TEXT blobs → pool-size=1-safe), frontend page `ProjectCockpit.tsx` (route `/cockpit`).
- **ARCHIVED status**: storage next to BACKLOG for "out of sight". Hidden from the default list (`TodoService.buildSpec` excludes ARCHIVED when no status filter is set), available via the archive tab / edit modal.
- **Hygiene** (`TodoHygieneService`): `archiveStaleNow(projectId)` moves stale auto TODOs (OPEN/BACKLOG, source AI/WORKFLOW, tag `auto:workflow`, `updatedAt` older than threshold) to ARCHIVED — via bulk UPDATE in a short transaction. The cockpit button "Archive stale" (`POST /todos/archive-stale?projectId=`) is the primary human-in-the-loop path. The `@Scheduled` job (daily 03:30) calls the same path, but is **OFF** by default (`todo-hygiene-enabled`). Thresholds runtime-configurable via `ExecutionSettingsService`: `todo-hygiene-enabled` [0,1], `todo-stale-days` [7,365], `todo-hygiene-max-per-run` [10,2000].
- **Bulk status** (`POST /todos/bulk-status`): generic bulk UPDATE for many TODO IDs to one status.

## Notification System (V40)

Notification configurations for workflow events:
- `NotificationService` — CRUD for notification configurations
- `NotificationController` — `/api/v1/notifications`
- API: List, Get, Create, Enable/Disable, Delete

## Assistant System (V41+V42)

Context-sensitive chat assistant for the platform. See also: [assistant-system.md](assistant-system.md)

- `AssistantService` — chat logic, context preparation, Claude CLI call
- `AssistantSessionService` — session persistence in DB (V41 migration: `assistant_sessions`, V58: `preview` column). Endpoints: `GET /sessions` (top 50), `GET /sessions/{id}` (detail), `DELETE /sessions/{id}`
- `AssistantContextBuilder` — injects last 5 sessions (user message + response) and up to 8 active workflow memories into the system prompt — on **all** pages
- `PageSuggestionService` — generates and caches context-sensitive UI suggestions per page (V42 migration: `page_suggestions`)
- `AssistantController` — `POST /api/v1/assistant/chat`, `GET /sessions`, `GET /sessions/{id}`, `DELETE /sessions/{id}`
- `PageSuggestionController` — `GET /api/v1/assistant/suggestions?page=...`, `POST /api/v1/assistant/suggestions/refresh?page=...`
- Sessions are persisted after each chat call; `AssistantSessionUpdatedEvent` triggers page suggestion refresh
- Default model: `claude-sonnet-4-6`

## Workflow Memory

Workflows can accumulate knowledge across runs:
- `WorkflowMemoryService` — extracts knowledge from completed runs
- `ClaudeSummarizer` / `CodexSummarizer` — AI-based summarization
- `KnowledgeSummarizer` — interface for the summarization backends
- `HostCodexService` — runs OpenAI Codex CLI on the host (analogous to `HostClaudeService`)
- `SummarizationSetupService` — configuration and setup of the summarization backends
- `ConversationPreprocessor` — preprocessing of conversation data for summarization
- Active memory entries are injected into subsequent runs as context
- `AutoMemoryExtractionListener` — automatic extraction after successful runs, configurable per workflow metadata (`autoMemoryExtraction.enabled`, `autoMemoryExtraction.interval`)
- `WorkflowMemory` entity (V16 migration)

## Agent Instructions System

Two-stage instruction injection via Claude CLI `--append-system-prompt` / `--system-prompt`:

1. **Base instructions** (`src/main/resources/agent-instructions.md`) — loaded at startup by `AgentInstructionsService`, apply to all workflows
2. **Workflow-specific instructions** (`workflow.instructions`) — per workflow, editable in the UI (Monaco editor)

Injection paths:
- **Block execution:** instructions via `--append-system-prompt` on host Claude CLI (`HostClaudeService.executeAction()`)
- **Agentic REASON:** system prompt via `--system-prompt` to `HostClaudeService.reasonStructured()`, incl. workflow instructions in `## Workflow Instructions`

## Host Claude Service

All Claude CLI calls run on the host (not in the container):
- `HostClaudeService.executeAction()` — ACT phase, uses `--append-system-prompt`, `--model` flag, project working directory
- `HostClaudeService.reasonStructured()` — REASON phase, uses `--system-prompt`, `--max-turns 3`, no model flag
- Prompt is written to the process via **stdin** (`writePromptToStdin()`) — avoids Linux ARG_MAX limit
- `CLAUDE_CODE` and `CLAUDECODE` environment variables are removed from the process environment (prevents nested session errors)
- `CommandExecutorService` provides `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `writeSkillContextToProject()` — all overloads use the `HostExecutionRequest` builder API
- Skill context is injected file-based: `writeSkillContextToProject()` writes `.claude/rules/skills-context.md` to the project directory (replaces the old CLI process-based method `injectSkillContextOnHost()`)

## Provider Routing

All block executors (simple, retry, conditional, forEach, while, switch, skill-capability) pass `context.getProvider()` as `providerId` via `HostExecutionRequest.builder()` to `CommandExecutorService`. Setting `"provider": "CODEX"` in a workflow JSON therefore applies to every non-shell block type.

`AiProviderRegistry.resolve()` falls back to the configured default provider (not hardcoded CLAUDE) when an unknown provider ID is passed.

## Per-Block Skills

Blocks can bring their own skills via the `skillIds` field, applied additively to workflow-level skills:

- `BlockDefinition.skillIds` — optional list of skill IDs enabled only for this block
- `WorkflowExecutor` merges workflow-level skills + block-level skills before each block execution
- After block execution, the baseline skill state is restored
- Skill context is provided file-based via `.claude/rules/skills-context.md` in the project directory (`CommandExecutorService.writeSkillContextToProject()`)
- Frontend: skill picker toggles in the add/edit block modals, zap icon indicator on `SortableBlock`

## Model Selection

- `workflow.model` field (e.g. `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5`)
- Passed to the Claude CLI on the host via `--model` flag
- The agentic REASON phase uses the locally configured default model

## Per-Call Token Limit

- `HostClaudeConfig.maxTokens` (property `automation.host-claude.max-tokens`) — optional output token limit per call
- Applied via `--max-tokens` flag in `reasonStructured()` and `executeAction()`
- Differs from the `max_total_tokens` budget of the agentic block (which tracks cumulative input+output across all iterations)

## Per-Block Claude CLI Timeout

- All block types support an optional `timeout_seconds` field in the block config
- Overrides the global `automation.host-claude.timeout-seconds` value (default: 300s) for this block
- Without `timeout_seconds` in the config → global default applies
- For dual-mode blocks (shell/claude), `timeout_seconds` controls both shell and Claude CLI timeout
- Applies to `reasonStructured()` and `executeAction()` in agentic blocks

## Workflow Conversion

Successful agentic runs can be converted into deterministic workflows:
- `WorkflowConversionService` — analyzes the run and suggests a structured workflow (rule-based + AI)
- `ConversionController` — API endpoints for preview and creation
- Frontend: conversion dialog in the execution view

### Dialog-to-Workflow Conversion

Claude Code sessions can also serve as templates for workflows:
- `DialogSessionScanner` — scans `~/.claude/projects/` for JSONL session files
- `DialogConversionService` — analyzes a session with Claude and generates a workflow suggestion
- `ClaudeJsonlParser` — parses JSONL session files
- `DialogController` — `/api/v1/dialog` with session list, analysis, and workflow creation
- Path traversal protection: only paths under `~/.claude/projects/` with `.jsonl` extension are allowed

## Workflow Templates

- Templates are stored in the `workflow_templates` DB table (V19 migration), not hardcoded
- **44 built-in templates** are imported at startup by `WorkflowTemplateSeeder` (idempotent, adds missing ones, updates on version bump)
- Template definitions live as individual JSON files in `src/main/resources/templates/builtin/` (one file per template, loaded by `BuiltInTemplateLoader`)
- Name and description are i18n JSON maps: `{"de":"...","en":"..."}` — stored via `JsonStringMapConverter`
- Built-in templates (`builtIn=true`) cannot be deleted; only `sortOrder` and `category` are updatable
- Users can create their own templates via API or by saving an existing workflow
- `workflow.templateId` — optional back-reference to the source template
- Frontend resolves localized template names via `resolveTemplateName()` in `utils/templateUtils.ts`
- CRUD API under `/api/v1/workflow-templates`

## Built-in Skills

- Skills are stored in the `skills` DB table, not hardcoded in frontend or in-memory beans
- **16 built-in skills** are imported at startup by `SkillSeeder` (idempotent)
- Skill definitions live in `BuiltInSkills.java` (`config/` package)
- Built-in skills have stable IDs (e.g. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` also registers all skills in the in-memory `SkillRegistry` for capability lookups
- `SkillRegistry` no longer depends on `SkillProvider` beans — the seeder handles registration
- Capability handler classes (e.g. `LintHandler`, `OCRHandler`) remain independent `@Component` beans in `skills/handlers/`
- Users can create their own skills via import API (`POST /api/v1/skills/import`)
- `Skill.java` entity uses `@PrePersist ensureId()` (instead of `@GeneratedValue`) for explicit IDs on built-ins

## Template Verification

- `TemplateVerification` entity (V22 migration) — tracks the quality status of workflow templates
- `TemplateVerificationService` — manages verification status per template+project pair
- Workflows created from templates can be test-started to check template correctness
- Verification results are visible in template CRUD API responses

## Workflow Factory

Automatic generation and verification of workflows from a topic:
- `WorkflowFactoryService` — orchestrates the entire factory process
- `ClaudeWorkflowGenerator` / `CodexWorkflowGenerator` — AI-based workflow generation
- `WorkflowTextGenerator` — text-based workflow generation
- `FactorySetupService` — configuration and setup of the factory environment
- `WorkflowFactoryController` — `/api/v1/factory` with endpoints:
  - `POST /generate` — generate workflow from topic
  - `POST /run` — execute generated workflow on test instance
  - `POST /verify/{id}` — verify workflow
  - `POST /promote/{id}` — promote workflow to production
  - `GET /topics` — list available topics
- Uses the test instance (port 8090) for safe execution

## Workflow Pool

Prebuilt workflow definitions from the `workflow-pool/` directory:
- `WorkflowPoolService` — reads JSON files from the configured pool directory
- `WorkflowPoolController` — `/api/v1/pool` with endpoints:
  - `GET /` — list all pool workflows
  - `GET /{id}` — retrieve a single pool workflow
  - `POST /{id}/install` — import workflow from pool into target project

## Wishes (Wish System)

Natural-language wishes are automatically turned into workflows/chains:
- `WishService` — creates wish, starts asynchronous fulfillment
- `WishWatchdogService` — monitors running wishes for stale/timeout states
- Iterative process: Decomposition → workflow/chain generation → execution → feedback
- Supports up to `max_iterations` iterations for improvements
- `crystallize()` — promotes successful wish as template

## Docs System

Backend reads Markdown documentation files and provides them as an API:
- `DocsController` — `/api/v1/docs`, reads `.md` files from the configured `docs/` directory
- Path traversal protection built in
- Frontend shows docs in split layout with Markdown rendering

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
| `ChainRunEntry` | *(embedded)* | Status of an individual chain entry during execution (value object) |
| `CompletionTrigger` | `completion_triggers` | Link source→target workflow on completion |
| `TodoTrigger` | `todo_triggers` | Automatic fix workflow execution on TODO creation (self-improvement loop) |
| `Container` | `containers` | Docker container per project with status and mounts |
| `NotificationConfig` | `notification_configs` | Notification configuration (webhook/desktop) |
| `PageSuggestion` | `page_suggestions` | Cached UI suggestions per page |
| `Project` | `projects` | Project with type, path, status, AI context |
| `ScheduledWorkflow` | `scheduled_workflows` | Cron/once/delayed schedule for workflows and chains |
| `Skill` | `skills` | Skill with category, capabilities, configuration schema |
| `StepResult` | `step_results` | Result of an individual workflow step with context delta |
| `TemplateVerification` | `template_verifications` | Quality status of a template per project |
| `Todo` | `todos` | Task with status, priority, source, tags |
| `WebhookTrigger` | `webhook_triggers` | Webhook trigger for workflows with secret token |
| `Wish` | `wishes` | Natural-language wish with decomposition and iteration history |
| `Workflow` | `workflows` | Workflow with blocks, instructions, model, provider (soft delete) |
| `WorkflowChain` | `workflow_chains` | Chain with sorted entries and stop-on-failure |
| `WorkflowMemory` | `workflow_memory` | Extracted knowledge from workflow runs |
| `WorkflowRun` | `workflow_runs` | Execution instance of a workflow with steps and metadata |
| `WorkflowTemplate` | `workflow_templates` | Template with i18n name/description and blocks |

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
| `Todo.Status` | OPEN, IN_PROGRESS, DONE, BACKLOG, ARCHIVED (ARCHIVED = "out of sight" — hidden from the default list, own archive tab; target of hygiene/bulk archiving) |
| `Todo.Priority` | LOW, MEDIUM, HIGH |
| `Todo.Source` | USER, AI, WORKFLOW |
| `ActivityLog.LogLevel` | DEBUG, INFO, WARN, ERROR |
| `ActivityLog.LogCategory` | SYSTEM, EXECUTION, SCHEDULE, WEBHOOK, SECURITY, CONTAINER |
| `ScheduledWorkflow.ScheduleType` | CRON, ONCE, DELAYED |
| `ScheduledWorkflow.TargetType` | WORKFLOW, CHAIN |
| `ScheduledWorkflow.ConflictBehavior` | SKIP, OVERLAP |
| `NotificationConfig.ChannelType` | WEBHOOK, DESKTOP |

## Frontend architecture

### Component structure

```
App.tsx (Router)
├── Layout
│   ├── Sidebar (navigation, collapse)
│   └── Header (language, theme)
│
├── Pages (18+ routes)
│   ├── Dashboard, Projects, ProjectDetail
│   ├── Workflows, WorkflowDetail, WorkflowExecution
│   ├── Skills, Schedules, Webhooks, Settings
│   ├── Runs, ActivityLog, Todos
│   ├── Chains, ChainExecution
│   ├── Wishes, WorkflowFactory, Docs
│
├── Hooks (TanStack Query + custom)
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

### Data flow

```
User interaction
    ↓
React component
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
Response (DTO) → React Query Cache → UI update
```

## WebSocket (live logs + status)

- **Protocol:** STOMP over SockJS
- **Endpoint:** `/ws` (SockJS handshake)
- **Broker:** Simple Message Broker on `/topic`
- **Topics:**
  - `/topic/executions/{runId}/logs` — workflow execution logs
  - `/topic/chains/{chainRunId}/status` — chain execution status
- **Message format:** `{ timestamp, level, message }`
- **Terminal WebSocket:** `/ws/terminal` for real-time container access

## Security

### API key authentication

- **Header:** `X-API-Key`
- **Filter:** `ApiKeyAuthFilter` (before Spring Security)
- **Comparison:** constant-time (`MessageDigest.isEqual`)
- **Auto generation:** key is stored in `app_settings` on first startup
- **Can be disabled:** `automation.security.enabled=false` (development)

### Public endpoints (no API key required)

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `POST /api/v1/webhook/trigger/{token}`
- `/ws/**` (WebSocket)
- `/actuator/**`

### Rate limiting

- **Filter:** `RateLimitFilter` (reads from `RateLimitConfig` bean)
- **Algorithm:** sliding window (1 minute)
- **Default:** 120 requests/minute per client IP
- **Response header:** `X-RateLimit-Remaining`
- **Runtime-configurable:** Settings → Execution → API rate limit (toggle + RPM value)
- **Env override:** `AUTOMATION_RATE_LIMIT_ENABLED` / `AUTOMATION_RATE_LIMIT_RPM`

### CORS

- All origins allowed (`*`) with credentials
- All HTTP methods (GET, POST, PUT, DELETE, PATCH, OPTIONS)

## Database migrations (Flyway)

The DB migrations (`src/main/resources/db/migration/`) document the feature timeline:

| Migration | Feature |
|---|---|
| V1–V9 | Base tables: Projects, Containers, Skills, Capabilities, Workflows, Runs, Schedules, Webhooks, Timestamps |
| V10–V11 | Performance indexes, container last activity |
| V12 | App settings |
| V13 | Workflow model + instructions |
| V14 | Schedule types + scheduled at |
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
| V43 | Triggered by schedule ID |
| V44 | Schedule end conditions |
| V45 | Schedule conflict behavior |
| V46 | Stable synced at |
| V47 | Context delta in step results |
| V48 | Related todo ID |
| V49 | Onboarding profiles |
| V50 | Workflow versions |
| V51 | Todo triggers table |
| V52 | Fix attempts + last fix run ID on TODOs |

## Directory structure

```
autotrigger/
├── CLAUDE.md                          ← Project instructions for Claude Code
├── VISION.md                          ← Product vision
├── docs/                              ← These documentation files
├── workflow-pool/                     ← Prebuilt workflow definitions (JSON)
├── scripts/
│   ├── release.sh                     ← Version bump + Git tag
│   └── staging.sh                     ← Manage stable environment
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
│   │   └── Dockerfile.agent-go
│   ├── data/                          ← SQLite DB (gitignored)
│   └── src/main/
│       ├── java/com/automation/       ← Java source files
│       └── resources/
│           ├── application.yml
│           ├── application-stable.yml ← Stable profile
│           ├── agent-instructions.md  ← Base agent instructions
│           ├── templates/builtin/     ← Built-in template JSON files
│           └── db/migration/          ← 47 Flyway migrations (V1–V47)
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
        ├── pages/                     ← 18+ pages
        ├── components/
        │   ├── ui/                    ← Reusable UI components
        │   ├── layout/                ← Sidebar, Header, Layout
        │   ├── shared/                ← StatusBadge, LogViewer, etc.
        │   ├── dashboard/             ← Chart components
        │   └── workflow/              ← Block editor, templates
        ├── hooks/                     ← TanStack Query hooks
        ├── stores/                    ← Zustand stores
        ├── i18n/                      ← DE/EN translations
        ├── theme/                     ← Theme tokens
        ├── data/                      ← Skill constants
        ├── utils/                     ← Helper functions
        └── test/                      ← MSW handlers, test setup
```
