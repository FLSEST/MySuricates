> *Last updated: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

# Architecture

## System Overview

```
┌─────────────────────────────────────────────────────────┐
│                      Browser                            │
│  React 18 PWA (Vite Dev Server :3000 / static)          │
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

Dev automatically starts Stable and Test on boot if a snapshot is available (configurable via `automation.staging.auto-start` and `automation.staging.test-auto-start` respectively).

## Backend Architecture

### Layer Model

```
Controller (REST API, 26 Controllers)
    ↓ DTO (Request/Response Records)
Service (Business Logic)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (File: data/automation.db)
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
│   ├── integration/     IntegrationRegistry, IntegrationOAuthService, IntegrationEncryptionService, IntegrationBlockExecutor, 5 Providers
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

### All 30 REST Controllers

| Controller | Base Path | Description |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | CRUD, archiving, container lifecycle, context generation |
| `WorkflowController` | `/api/v1/workflows` | CRUD, execution, export/import, skills, optimize, repair |
| `ExecutionController` | `/api/v1/executions` | Pause, resume, resume-from-step, cancel, retry, approve, todos-approve, command |
| `SkillController` | `/api/v1/skills` | CRUD, capabilities, export/import |
| `ScheduleController` | `/api/v1/schedules` | CRUD, enable/disable, runs, calendar, preview, remaining-stats, chain-schedules, maintenance |
| `WebhookController` | `/api/v1/webhooks` | CRUD, enable/disable; trigger via `/api/v1/webhook/trigger/{token}` |
| `ChainController` | `/api/v1/chains` | CRUD, execution, runs |
| `ChainTemplateController` | `/api/v1/chain-templates` | List, get, install as chain |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | CRUD, create from workflow |
| `WorkflowMemoryController` | `/api/v1/workflows/{id}/memory` | CRUD, extract, activate |
| `WorkflowFactoryController` | `/api/v1/factory` | Generate, run, verify, promote, topics |
| `WorkflowPoolController` | `/api/v1/pool` | List, get, install |
| `ConversionController` | `/api/v1/conversions` | Suggest, create workflow from run |
| `DialogController` | `/api/v1/dialog` | Sessions, projects, Claude session analysis, workflow creation |
| `LearningController` | `/api/v1/learning` | Dashboard, stats, step analytics, suggestions, similar, agentic history, agentic analytics, tool patterns, execution insights, run insights, rate |
| `TodoController` | `/api/v1/todos` | CRUD, status patch, count |
| `ActivityLogController` | `/api/v1/activity-log` | List |
| `AssistantController` | `/api/v1/assistant` | Chat (`POST /chat`) |
| `PageSuggestionController` | `/api/v1/assistant` | Suggestions per page (get/refresh) |
| `CompletionTriggerController` | `/api/v1/completion-triggers` | CRUD, enable/disable, filter by workflow |
| `TodoTriggerController` | `/api/v1/todo-triggers` | CRUD, enable/disable, filter by target workflow |
| `NotificationController` | `/api/v1/notifications` | CRUD, enable/disable |
| `WishController` | `/api/v1/wishes` | CRUD, cancel, respond, crystallize |
| `StagingController` | `/api/v1/staging` | Status, start, stop, promote, promote-cancel, sync-preview, sync-definitions |
| `TestingController` | `/api/v1/testing` | Status, start, stop, reset, sync-preview, sync-definitions, report-result, pull-changes |
| `SystemController` | `/api/v1/system` | Health, auth-status, info, settings, execution-config, AI-providers, backup, backups, remote-access, browse, open-path, repair, client-errors |
| `IntegrationController` | `/api/v1/integrations` | OAuth flow, credentials, actions, provider list |
| `OnboardingProfileController` | `/api/v1/projects/{id}/onboarding` | Onboarding profiles CRUD, enable/disable, preview |
| `ReportsController` | `/api/v1/reports` | Report generation and retrieval |
| `DocsController` | `/api/v1/docs` | Read Markdown documentation |

### Workflow Execution

The workflow engine executes blocks sequentially. Claude CLI always runs **on the host** (not inside containers) to avoid nested session detection.

**Flow:**
1. `WorkflowExecutor` creates a `WorkflowRun` (status: PENDING → RUNNING)
2. For each `BlockDefinition`, the appropriate `BlockExecutor` is selected
3. Claude CLI calls are made on the host via `HostClaudeService` (`ProcessBuilder`)
4. Shell commands can optionally be executed via Docker Exec inside the container
5. Results are saved as `StepResult`
6. Live logs are sent to the frontend via WebSocket (STOMP)
7. After completion, `WorkflowExecutor` evaluates gate status (all block outputs)
8. `WorkflowRunCompletedEvent` is fired → `AutoTodoService` reacts

### Block Types (12 total)

| Type | Executor | Description |
|---|---|---|
| `simple` | `SimpleBlockExecutor` | Single Claude CLI call with prompt |
| `shell` | `SimpleBlockExecutor` (dual-mode) | Shell script or Claude CLI |
| `retry` | `RetryBlockExecutor` | Prompt with configurable retry attempts |
| `conditional` | `ConditionalBlockExecutor` | If/else branching based on condition |
| `forEach` | `ForEachBlockExecutor` | Iteration over a list of elements |
| `while` | `WhileBlockExecutor` | Loop with termination condition and max iterations |
| `switch` | `SwitchBlockExecutor` | Multi-way branching per expression with cases |
| `agentic` | `AgenticBlockExecutor` | Goal→Reason→Act→Observe loop, up to 10 iterations |
| `approval` | `ApprovalBlockExecutor` | Pauses execution, waits for user confirmation |
| `sub-workflow` | `SubWorkflowBlockExecutor` | Runs another workflow as a subprocess |
| `skill-capability` | `SkillCapabilityBlockExecutor` | Invokes a named skill capability |
| `human-input` | `HumanInputBlockExecutor` | Collects user input via WebSocket, stores as context variable |

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
| `total_timeout_seconds` | 1800 | Total block timeout (30 min) |
| `max_total_tokens` | 200000 | Token budget (input+output, all phases) |
| `max_repeated_actions` | 3 | Abort after N identical actions |
| `require_approval` | false | Approval gate before each action |

Additionally: history compression after 7 turns, token tracking warnings in the log. All 6 safety fields are pre-populated as defaults in the frontend "Add Block" dialog.

### Switch Block

The switch block evaluates an expression against multiple cases:
- Supports `${varName}` and `{{ varName }}` for variable interpolation
- Cases are evaluated in order — first match wins
- Pipe-separated alternatives: `"error|critical"` matches both
- `default` case when no other case matches
- Each case contains a list of nested blocks (shell or claude)
- Returns `COMPLETED` with `matchedCase: "none"` when no match and no default

### Sub-Workflow Block

- Runs another workflow as a nested block
- Circular references are detected via `__parent_workflow_ids`
- Maximum nesting depth: 5 levels
- Polling-based completion, 30-minute timeout
- `@Lazy` injection on `WorkflowExecutor` to break Spring DI cycles

### Human-Input Block

- Pauses workflow execution and waits for user input
- Sends `human_input_required` event via WebSocket to `/topic/executions/{runId}/logs`
- User response is stored as a context variable (`variable_name`)
- Supports text and select inputs, validation patterns, timeout with optional default
- Reuses the `ApprovalRequest` mechanism from the approval block

## Gate Status System

Workflow blocks can write quality gate markers in their output:

```
gate_status: GREEN|YELLOW|RED
gate_category: <category>
gate_description: <description>
```

**Processing:**
- `GateStatusOutputParser` — parses markers from block outputs (tolerant of Markdown formatting)
- `WorkflowExecutor` scans all block outputs — highest severity wins (GREEN < YELLOW < RED)
- Gate status is stored in `WorkflowRun.metadata` (`gate_status`, `gate_category`, `gate_description`)

**Gate → Run Status Mapping:**
- **YELLOW → `COMPLETED_WITH_ERRORS`:** Run finishes with this status instead of `COMPLETED`
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
- **Sequential:** `ChainExecutor` executes entries in `sortOrder` order (virtual threads, polling every 2s, 30-min timeout)
- **Context passing:** Each entry receives chain-level context + entry-level context + `previous_chain_output` from the last completed workflow
- **Stop-on-failure:** Flag at both chain and entry level — chain stops only when both are `true`
- **Cancellation:** Via `ConcurrentHashMap<String, AtomicBoolean>`
- **WebSocket:** Status broadcasts to `/topic/chains/{chainRunId}/status`

### Chain Templates

Pre-built chain definitions composed from existing workflow templates:
- `ChainTemplateService` — manages built-in chain templates (`BuiltInChainTemplate` records)
- `ChainTemplateController` — `/api/v1/chain-templates` with list, get, install endpoints
- Install: Creates a new chain (with associated workflows if needed) in a target project

## Completion Triggers (V39)

Automatic chaining of workflows based on completion events:
- `CompletionTriggerService` — manages trigger definitions
- `CompletionTriggerController` — `/api/v1/completion-triggers`
- Triggers link a source workflow (trigger) with a target workflow (started after completion)
- API: List, get, create, enable/disable, delete; filter by source or target workflow ID

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
- `TodoFeedbackListener` — `@EventListener` on `WorkflowRunCompletedEvent`, closes TODO on GREEN gate (if `autoClose=true`), logs escalation when fix attempts exhausted
- `TodoTriggerController` — `/api/v1/todo-triggers`
- API: List, get, create, enable/disable, delete
- **Loop prevention**: 3 layers — metadata flag (`todoTriggerRun`), max attempts counter (`todo.fixAttempts`), running check (`todo.lastFixRunId`)
- **Context variables** for fix workflow: `todo_id`, `todo_title`, `todo_description`, `todo_tags`, `todo_priority`, `todo_project_id`, `todo_trigger_id`

## Notification System (V40)

Notification configurations for workflow events:
- `NotificationService` — CRUD for notification configurations
- `NotificationController` — `/api/v1/notifications`
- API: List, get, create, enable/disable, delete

## Assistant System (V41+V42)

Context-sensitive chat assistant for the platform. See also: [assistant-system.md](assistant-system.md)

- `AssistantService` — Chat logic, context preparation, Claude CLI call
- `AssistantSessionService` — Session persistence in DB (V41 migration: `assistant_sessions`)
- `PageSuggestionService` — generates and caches context-sensitive UI suggestions per page (V42 migration: `page_suggestions`)
- `AssistantController` — `POST /api/v1/assistant/chat`
- `PageSuggestionController` — `GET /api/v1/assistant/suggestions?page=...`, `POST /api/v1/assistant/suggestions/refresh?page=...`
- Sessions are persisted after each chat call; `AssistantSessionUpdatedEvent` triggers page suggestion refresh
- Default model: `claude-sonnet-4-6`

## Workflow Memory

Workflows can accumulate knowledge across runs:
- `WorkflowMemoryService` — extracts knowledge from completed runs
- `ClaudeSummarizer` / `CodexSummarizer` — AI-based summarization
- `KnowledgeSummarizer` — Interface for the summarization backends
- `HostCodexService` — runs OpenAI Codex CLI on the host (analogous to `HostClaudeService`)
- `SummarizationSetupService` — Configuration and setup of the summarization backends
- `ConversationPreprocessor` — Preprocessing of conversation data for summarization
- Active memory entries are injected as context into subsequent runs
- `WorkflowMemory` entity (V16 migration)

## Agent Instructions System

Two-level instruction injection via Claude CLI `--append-system-prompt` / `--system-prompt`:

1. **Base instructions** (`src/main/resources/agent-instructions.md`) — loaded at startup by `AgentInstructionsService`, apply to all workflows
2. **Workflow-specific instructions** (`workflow.instructions`) — per workflow, editable in the UI (Monaco editor)

Injection paths:
- **Block execution:** Instructions via `--append-system-prompt` on host Claude CLI (`HostClaudeService.executeAction()`)
- **Agentic REASON:** System prompt via `--system-prompt` to `HostClaudeService.reasonStructured()`, including workflow instructions in `## Workflow Instructions`

## Host Claude Service

All Claude CLI calls are made on the host (not inside the container):
- `HostClaudeService.executeAction()` — ACT phase, uses `--append-system-prompt`, `--model` flag, project working directory
- `HostClaudeService.reasonStructured()` — REASON phase, uses `--system-prompt`, `--max-turns 3`, no model flag
- Prompt is written via **stdin** to the process (`writePromptToStdin()`) — avoids the Linux ARG_MAX limit
- `CLAUDE_CODE` and `CLAUDECODE` environment variables are removed from the process environment (prevents nested session errors)
- `CommandExecutorService` provides `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `writeSkillContextToProject()` — all overloads use the `HostExecutionRequest` builder API
- Skill context injection is **file-based**: `writeSkillContextToProject()` writes a `.claude/rules/skills-context.md` file into the project directory (replacing the former CLI-process-based `injectSkillContextOnHost()`)
- `WorkflowExecutor` supports **per-block skills**: before each block, workflow-level and block-level skills (from `BlockDefinition.skillIds`) are merged and written to the project; after the block completes, the baseline workflow-level skills are restored

## Provider Routing

All block executors (simple, retry, conditional, forEach, while, switch, skill-capability) pass `context.getProvider()` as `providerId` via `HostExecutionRequest.builder()` to `CommandExecutorService`. Setting `"provider": "CODEX"` in a workflow JSON therefore applies to every non-shell block type.

`AiProviderRegistry.resolve()` falls back to the configured default provider (not hardcoded CLAUDE) when an unknown provider ID is given.

## Model Selection

- `workflow.model` field (e.g. `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5`)
- Passed via `--model` flag to Claude CLI on the host
- The agentic REASON phase uses the locally configured default model

## Per-Call Token Limit

- `HostClaudeConfig.maxTokens` (property `automation.host-claude.max-tokens`) — optional per-call output token limit
- Applied via `--max-tokens` flag in `reasonStructured()` and `executeAction()`
- Distinct from the agentic block's `max_total_tokens` budget (which tracks cumulative input+output across all iterations)

## Per-Block Claude CLI Timeout

- All block types support an optional `timeout_seconds` field in the block config
- Overrides the global `automation.host-claude.timeout-seconds` value (default: 300s) for that block
- Without `timeout_seconds` in the config → global default applies
- For dual-mode blocks (shell/claude), `timeout_seconds` controls both the shell timeout and the Claude CLI timeout
- Applies to `reasonStructured()` and `executeAction()` calls in agentic blocks

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
- `DialogController` — `/api/v1/dialog` with session list, analysis, and workflow creation
- Path traversal protection: only paths under `~/.claude/projects/` with `.jsonl` extension are allowed

## Workflow Templates

- Templates are stored in the `workflow_templates` DB table (V19 migration), not hardcoded
- **44 built-in templates** are seeded on startup by `WorkflowTemplateSeeder` (idempotent, inserts missing ones, updates on version bump)
- Template definitions are stored as individual JSON files in `src/main/resources/templates/builtin/` (one file per template, loaded by `BuiltInTemplateLoader`)
- Name and description are i18n JSON maps: `{"de":"...","en":"..."}` — stored via `JsonStringMapConverter`
- Built-in templates (`builtIn=true`) cannot be deleted; only `sortOrder` and `category` can be updated
- Users can create custom templates via API or by saving an existing workflow
- `workflow.templateId` — optional back-reference to the source template
- Frontend resolves localized template names via `resolveTemplateName()` in `utils/templateUtils.ts`
- CRUD API at `/api/v1/workflow-templates`

## Built-in Skills

- Skills are stored in the `skills` DB table, not hardcoded in the frontend or in-memory beans
- **16 built-in skills** are seeded on startup by `SkillSeeder` (idempotent)
- Skill definitions are in `BuiltInSkills.java` (`config/` package)
- Built-in skills have stable IDs (e.g. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` also registers all skills in the in-memory `SkillRegistry` for capability lookups
- `SkillRegistry` no longer depends on `SkillProvider` beans — the seeder handles registration
- Capability handler classes (e.g. `LintHandler`, `OCRHandler`) remain as independent `@Component` beans in `skills/handlers/`
- Users can create custom skills via the import API (`POST /api/v1/skills/import`)
- `Skill.java` entity uses `@PrePersist ensureId()` (instead of `@GeneratedValue`) for explicit IDs on built-ins

## Template Verification

- `TemplateVerification` entity (V22 migration) — tracks the quality status of workflow templates
- `TemplateVerificationService` — manages verification status per template+project pair
- Workflows created from templates can be test-run to verify template correctness
- Verification results are visible in template CRUD API responses

## Workflow Factory

Automatic generation and verification of workflows from a topic:
- `WorkflowFactoryService` — orchestrates the entire factory process
- `ClaudeWorkflowGenerator` / `CodexWorkflowGenerator` — AI-based workflow generation
- `WorkflowTextGenerator` — Text-based workflow generation
- `FactorySetupService` — Configuration and setup of the factory environment
- `WorkflowFactoryController` — `/api/v1/factory` with endpoints:
  - `POST /generate` — Generate workflow from topic
  - `POST /run` — Run generated workflow on test instance
  - `POST /verify/{id}` — Verify workflow
  - `POST /promote/{id}` — Promote workflow to production
  - `GET /topics` — List available topics
- Uses the test instance (port 8090) for safe execution

## Workflow Pool

Pre-built workflow definitions from the `workflow-pool/` directory:
- `WorkflowPoolService` — reads JSON files from the configured pool directory
- `WorkflowPoolController` — `/api/v1/pool` with endpoints:
  - `GET /` — List all pool workflows
  - `GET /{id}` — Get a single pool workflow
  - `POST /{id}/install` — Import workflow from pool into target project

## Wishes (Wish System)

Natural language wishes are automatically converted into workflows/chains:
- `WishService` — creates a wish, starts asynchronous fulfillment
- `WishWatchdogService` — monitors running wishes for stale/timeout states
- Iterative process: Decomposition → Workflow/chain generation → Execution → Feedback
- Supports up to `max_iterations` iterations for improvements
- `crystallize()` — promotes a successful wish as a template

## Docs System

The backend reads Markdown documentation files and exposes them as an API:
- `DocsController` — `/api/v1/docs`, reads `.md` files from the configured `docs/` directory
- Path traversal protection built-in
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
| `TodoTrigger` | `todo_triggers` | Automatic fix workflow execution on TODO creation (self-improvement loop) |
| `Container` | `containers` | Docker container per project with status and mounts |
| `NotificationConfig` | `notification_configs` | Notification configuration (webhook/desktop) |
| `PageSuggestion` | `page_suggestions` | Cached UI suggestions per page |
| `Project` | `projects` | Project with type, path, status, AI context |
| `ScheduledWorkflow` | `scheduled_workflows` | Cron/once/delayed schedule for workflows and chains |
| `Skill` | `skills` | Skill with category, capabilities, configuration schema |
| `StepResult` | `step_results` | Result of a single workflow step with context delta |
| `TemplateVerification` | `template_verifications` | Quality status of a template per project |
| `Todo` | `todos` | Task with status, priority, source, tags |
| `WebhookTrigger` | `webhook_triggers` | Webhook trigger for workflows with secret token |
| `Wish` | `wishes` | Natural language wish with decomposition and iteration history |
| `Workflow` | `workflows` | Workflow with blocks, instructions, model, provider (soft-delete) |
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
| `Todo.Status` | OPEN, IN_PROGRESS, DONE, BACKLOG |
| `Todo.Priority` | LOW, MEDIUM, HIGH |
| `Todo.Source` | USER, AI, WORKFLOW |
| `ActivityLog.LogLevel` | DEBUG, INFO, WARN, ERROR |
| `ActivityLog.LogCategory` | SYSTEM, EXECUTION, SCHEDULE, WEBHOOK, SECURITY, CONTAINER |
| `ScheduledWorkflow.ScheduleType` | CRON, ONCE, DELAYED |
| `ScheduledWorkflow.TargetType` | WORKFLOW, CHAIN |
| `ScheduledWorkflow.ConflictBehavior` | SKIP, OVERLAP |
| `NotificationConfig.ChannelType` | WEBHOOK, DESKTOP |

## Frontend Architecture

### Component Structure

```
App.tsx (Router)
├── Layout
│   ├── Sidebar (Navigation, Collapse)
│   └── Header (Language, Theme)
│
├── Pages (18+ Routes)
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
- **Broker:** Simple message broker on `/topic`
- **Topics:**
  - `/topic/executions/{runId}/logs` — Workflow execution logs
  - `/topic/chains/{chainRunId}/status` — Chain execution status
- **Message format:** `{ timestamp, level, message }`
- **Terminal WebSocket:** `/ws/terminal` for real-time container access

## Security

### API Key Authentication

- **Header:** `X-API-Key`
- **Filter:** `ApiKeyAuthFilter` (before Spring Security)
- **Comparison:** Constant-time (`MessageDigest.isEqual`)
- **Auto-generation:** Key is saved in `app_settings` on first startup
- **Disableable:** `automation.security.enabled=false` (development)

### Public Endpoints (no API key required)

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `POST /api/v1/webhook/trigger/{token}`
- `/ws/**` (WebSocket)
- `/actuator/**`

### Rate Limiting

- **Filter:** `RateLimitFilter` (reads from `RateLimitConfig` bean)
- **Algorithm:** Sliding window (1 minute)
- **Default:** 120 requests/minute per client IP
- **Response header:** `X-RateLimit-Remaining`
- **Runtime-configurable:** Settings → Execution → API Rate Limiting (toggle + RPM value)
- **Env override:** `AUTOMATION_RATE_LIMIT_ENABLED` / `AUTOMATION_RATE_LIMIT_RPM`

### CORS

- All origins allowed (`*`) with credentials
- All HTTP methods (GET, POST, PUT, DELETE, PATCH, OPTIONS)

## Database Migrations (Flyway)

The DB migrations (`src/main/resources/db/migration/`) document the feature timeline:

| Migration | Feature |
|---|---|
| V1–V9 | Base tables: Projects, Containers, Skills, Capabilities, Workflows, Runs, Schedules, Webhooks, Timestamps |
| V10–V11 | Performance indexes, container last-activity |
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
| V32 | Workflow soft-delete (deleted_at) |
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
| V43 | Triggered-by-schedule-ID |
| V44 | Schedule end conditions |
| V45 | Schedule conflict behavior |
| V46 | Stable synced-at |
| V47 | Context delta in step results |
| V48 | Related-Todo-ID |
| V49 | Onboarding profiles |
| V50 | Workflow versions |
| V51 | Todo triggers table |
| V52 | Fix attempts + last fix run ID on TODOs |

## Directory Structure

```
autotrigger/
├── CLAUDE.md                          ← Project instructions for Claude Code
├── VISION.md                          ← Product vision
├── docs/                              ← These documentation files
├── workflow-pool/                     ← Pre-built workflow definitions (JSON)
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
        ├── utils/                     ← Utility functions
        └── test/                      ← MSW handlers, test setup
```
