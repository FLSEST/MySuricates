> *Last updated: 28. März 2026, 14:30 Uhr — Workflow: MySuricates Docs aktualisieren | Run: c47b8112-0468-445d-bfaf-a7a8a5d46264*

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

Dev automatically starts Stable and Test on boot if a snapshot exists (configurable via `automation.staging.auto-start` and `automation.staging.test-auto-start`).

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
├── controller/          26 REST Controllers
├── service/
│   ├── project/         Projects + Container Lifecycle
│   ├── workflow/        WorkflowExecutor, WorkflowService, ExecutionBroadcaster, TemplateVerificationService
│   ├── skill/           Skills + Import/Export
│   ├── scheduler/       Cron-based Execution
│   ├── webhook/         Webhook Triggers
│   ├── learning/        Dashboard Analytics
│   ├── system/          Tailscale Remote Access
│   ├── ai/              HostClaudeService, HostCodexService
│   ├── cli/             CommandExecutorService, CliSessionManager, ClaudeOutputParser
│   ├── chain/           ChainService, ChainExecutor, ChainTemplateService
│   ├── memory/          WorkflowMemoryService, ClaudeSummarizer, CodexSummarizer
│   ├── staging/         StagingService, TestingService, InstanceSyncHelper
│   ├── todo/            TodoService, AutoTodoService, GateStatusResolver
│   ├── conversion/      WorkflowConversionService, DialogConversionService
│   ├── dialog/          DialogSessionScanner, DialogConversionService
│   ├── factory/         WorkflowFactoryService
│   ├── pool/            WorkflowPoolService
│   ├── wish/            WishService
│   ├── notification/    NotificationService
│   ├── trigger/         CompletionTriggerService
│   ├── health/          Health Checks
│   ├── repair/          WorkflowRepairService
│   ├── tray/            System Tray Integration
│   ├── activitylog/     ActivityLogService
│   ├── AssistantService.java        Chat Assistant (Claude CLI)
│   ├── AssistantSessionService.java Session Persistence
│   └── PageSuggestionService.java   Context-sensitive UI Suggestions
├── entity/              JPA Entities + Enums
├── dto/
│   ├── request/         Request Records
│   └── response/        Response Records
├── repository/          Spring Data Repositories
├── mapper/              MapStruct Mappers (Entity ↔ DTO)
├── executor/            Workflow Execution Engine
│   └── blocks/          12 Block Executors
├── config/              Configuration Classes, Seeders (Skill, Template)
├── security/            ApiKeyAuthFilter, RateLimitFilter
├── exception/           GlobalExceptionHandler + Exception Classes
├── websocket/           WebSocket Configuration
├── event/               WorkflowRunCompletedEvent, AssistantSessionUpdatedEvent
└── util/                JPA Converters (JSON, BlockDefinitions)
```

### All 26 REST Controllers

| Controller | Base Path | Description |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | CRUD, Archiving, Container Lifecycle, Context Generation |
| `WorkflowController` | `/api/v1/workflows` | CRUD, Execution, Export/Import, Skills, Optimize, Repair |
| `ExecutionController` | `/api/v1/executions` | Pause, Resume, Resume-from-Step, Cancel, Retry, Approve, Todos-Approve, Command |
| `SkillController` | `/api/v1/skills` | CRUD, Capabilities, Export/Import |
| `ScheduleController` | `/api/v1/schedules` | CRUD, Enable/Disable, Runs, Calendar, Preview, Remaining-Stats, Chain-Schedules, Maintenance |
| `WebhookController` | `/api/v1/webhooks` | CRUD, Enable/Disable; Trigger via `/api/v1/webhook/trigger/{token}` |
| `ChainController` | `/api/v1/chains` | CRUD, Execution, Runs |
| `ChainTemplateController` | `/api/v1/chain-templates` | List, Get, Install as Chain |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | CRUD, Create from Workflow |
| `WorkflowMemoryController` | `/api/v1/workflows/{id}/memory` | CRUD, Extract, Activate |
| `WorkflowFactoryController` | `/api/v1/factory` | Generate, Run, Verify, Promote, Topics |
| `WorkflowPoolController` | `/api/v1/pool` | List, Get, Install |
| `ConversionController` | `/api/v1/conversions` | Suggest, Create Workflow from Run |
| `DialogController` | `/api/v1/dialog` | Sessions, Projects, Claude Session Analysis, Workflow Creation |
| `LearningController` | `/api/v1/learning` | Dashboard, Stats, Step Analytics, Suggestions, Similar, Agentic History, Agentic Analytics, Tool Patterns, Execution Insights, Run Insights, Rate |
| `TodoController` | `/api/v1/todos` | CRUD, Status Patch, Count |
| `ActivityLogController` | `/api/v1/activity-log` | List |
| `AssistantController` | `/api/v1/assistant` | Chat (`POST /chat`) |
| `PageSuggestionController` | `/api/v1/assistant` | Suggestions per Page (Get/Refresh) |
| `CompletionTriggerController` | `/api/v1/completion-triggers` | CRUD, Enable/Disable, Filter by Workflow |
| `NotificationController` | `/api/v1/notifications` | CRUD, Enable/Disable |
| `WishController` | `/api/v1/wishes` | CRUD, Cancel, Respond, Crystallize |
| `StagingController` | `/api/v1/staging` | Status, Start, Stop, Promote, Promote-Cancel, Sync-Preview, Sync-Definitions |
| `TestingController` | `/api/v1/testing` | Status, Start, Stop, Reset, Sync-Preview, Sync-Definitions, Report-Result, Pull-Changes |
| `SystemController` | `/api/v1/system` | Health, Auth Status, Info, Settings, AI Providers, Backup, Backups, Remote Access, Browse, Open Path, Repair, Client Errors |
| `DocsController` | `/api/v1/docs` | Read Markdown Documentation |

### Workflow Execution

The workflow engine executes blocks sequentially. Claude CLI always runs **on the host** (not in containers) to avoid nested session detection.

**Flow:**
1. `WorkflowExecutor` creates a `WorkflowRun` (Status: PENDING → RUNNING)
2. For each `BlockDefinition`, the appropriate `BlockExecutor` is selected
3. Claude CLI calls are made on the host via `HostClaudeService` (`ProcessBuilder`)
4. Shell commands can optionally be executed via Docker Exec in the container
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
| `while` | `WhileBlockExecutor` | Repetition with termination condition and max iterations |
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
3. **OBSERVE:** Output is collected and added to history
4. Loop runs until `done: true` or termination conditions

**Safety mechanisms:**

| Config | Default | Description |
|---|---|---|
| `max_iterations` | 10 | Maximum number of iterations |
| `iteration_timeout_seconds` | 300 | Wall-clock timeout per iteration |
| `total_timeout_seconds` | 1800 | Overall block timeout (30 min.) |
| `max_total_tokens` | 200000 | Token budget (input+output, all phases) |
| `max_repeated_actions` | 3 | Abort after N identical actions |
| `require_approval` | false | Approval gate before each action |

Additionally: history compression after 7 turns, token tracking warnings in logs. All 6 safety fields are pre-populated as defaults in the frontend "Add Block" dialog.

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
- Uses the `ApprovalRequest` mechanism from the approval block

## Gate Status System

Workflow blocks can write quality gate markers into their output:

```
gate_status: GREEN|YELLOW|RED
gate_category: <category>
gate_description: <description>
```

**Processing:**
- `GateStatusOutputParser` — parses markers from block outputs (tolerant of markdown formatting)
- `WorkflowExecutor` scans all block outputs — highest severity wins (GREEN < YELLOW < RED)
- Gate status is stored in `WorkflowRun.metadata` (`gate_status`, `gate_category`, `gate_description`)

**Gate → Run Status Mapping:**
- **YELLOW → `COMPLETED_WITH_ERRORS`:** Run is finished with this status instead of `COMPLETED`
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
- **Sequential:** `ChainExecutor` runs entries in `sortOrder` order (virtual threads, polling every 2s, 30-min. timeout)
- **Context passing:** Each entry receives chain-level context + entry-level context + `previous_chain_output` from the last completed workflow
- **Stop-on-Failure:** Flag at chain and entry level — chain stops only when both are `true`
- **Cancellation:** Via `ConcurrentHashMap<String, AtomicBoolean>`
- **WebSocket:** Status broadcasts to `/topic/chains/{chainRunId}/status`

### Chain Templates

Pre-built chain definitions composed from existing workflow templates:
- `ChainTemplateService` — manages built-in chain templates (`BuiltInChainTemplate` records)
- `ChainTemplateController` — `/api/v1/chain-templates` with List, Get, Install endpoints
- Install: Creates a new chain (optionally with associated workflows) in a target project

## Completion Triggers (V39)

Automatic chaining of workflows based on completion events:
- `CompletionTriggerService` — manages trigger definitions
- `CompletionTriggerController` — `/api/v1/completion-triggers`
- Triggers link a source workflow (trigger) with a target workflow (started after completion)
- API: List, Get, Create, Enable/Disable, Delete; Filter by source or target workflow ID

## Notification System (V40)

Notification configurations for workflow events:
- `NotificationService` — CRUD for notification configurations
- `NotificationController` — `/api/v1/notifications`
- API: List, Get, Create, Enable/Disable, Delete

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
- `SummarizationConfig` — Configuration for backend selection
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

All Claude CLI calls are made on the host (not in containers):
- `HostClaudeService.executeAction()` — ACT phase, uses `--append-system-prompt`, `--model` flag, project working directory
- `HostClaudeService.reasonStructured()` — REASON phase, uses `--system-prompt`, `--max-turns 3`, no model flag
- Prompt is written via **stdin** to the process (`writePromptToStdin()`) — avoids Linux ARG_MAX limit
- `CLAUDE_CODE` and `CLAUDECODE` environment variables are removed from the process environment (prevents nested session errors)
- `CommandExecutorService` provides `executeOnHost(HostExecutionRequest)` / `executeStepOnHost(HostExecutionRequest)` / `injectSkillContextOnHost()` — all overloads use the `HostExecutionRequest` builder API

## Provider Routing

All block executors (simple, retry, conditional, forEach, while, switch, skill-capability) pass `context.getProvider()` as `providerId` via `HostExecutionRequest.builder()` to `CommandExecutorService`. Setting `"provider": "CODEX"` in a workflow JSON therefore applies to every non-shell block type.

`AiProviderRegistry.resolve()` falls back to the configured default provider (not hardcoded CLAUDE) when an unknown provider ID is given.

## Model Selection

- `workflow.model` field (e.g. `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5`)
- Passed via `--model` flag to the Claude CLI on the host
- The agentic REASON phase uses the locally configured default model

## Per-Call Token Limit

- `HostClaudeConfig.maxTokens` (property `automation.host-claude.max-tokens`) — optional per-call output token limit
- Applied via `--max-tokens` flag in `reasonStructured()` and `executeAction()`
- Distinct from the agentic block's `max_total_tokens` budget (which tracks cumulative input+output across all iterations)

## Per-Block Claude CLI Timeout

- All block types support an optional `timeout_seconds` field in the block config
- Overrides the global `automation.host-claude.timeout-seconds` value (default: 300s) for that block
- Without `timeout_seconds` in the config → global default applies
- For dual-mode blocks (shell/claude), `timeout_seconds` controls both the shell and Claude CLI timeout
- Applies to `reasonStructured()` and `executeAction()` in agentic blocks

## Workflow Conversion

Successful agentic runs can be converted into deterministic workflows:
- `WorkflowConversionService` — analyzes the run and suggests a structured workflow (rule-based + AI)
- `ConversionController` — API endpoints for preview and creation
- Frontend: Conversion dialog in the execution view

### Dialog-to-Workflow Conversion

Claude Code sessions can also serve as templates for workflows:
- `DialogSessionScanner` — scans `~/.claude/projects/` for JSONL session files
- `DialogConversionService` — analyzes a session with Claude and generates a workflow suggestion
- `DialogController` — `/api/v1/dialog` with session list, analysis, and workflow creation
- Path traversal protection: only paths under `~/.claude/projects/` with `.jsonl` extension are allowed

## Workflow Templates

- Templates are stored in the `workflow_templates` DB table (V19 migration), not hardcoded
- **44 built-in templates** are seeded on startup by `WorkflowTemplateSeeder` (idempotent, inserts missing, updates on version bump)
- Template definitions are stored as individual JSON files in `src/main/resources/templates/builtin/` (one file per template, loaded by `BuiltInTemplateLoader`)
- Name and description are i18n JSON maps: `{"de":"...","en":"..."}` — stored via `JsonStringMapConverter`
- Built-in templates (`builtIn=true`) cannot be deleted; only `sortOrder` and `category` can be updated
- Users can create custom templates via API or by saving an existing workflow
- `workflow.templateId` — optional back-reference to the source template
- Frontend resolves localized template names via `resolveTemplateName()` in `utils/templateUtils.ts`
- CRUD API at `/api/v1/workflow-templates`

## Built-in Skills

- Skills are stored in the `skills` DB table, not hardcoded in frontend or in-memory beans
- **16 built-in skills** are seeded on startup by `SkillSeeder` (idempotent)
- Skill definitions are in `BuiltInSkills.java` (`config/` package)
- Built-in skills have stable IDs (e.g. `builtin-code-review`, `builtin-test-generator`)
- `SkillSeeder` also registers all skills in the in-memory `SkillRegistry` for capability lookups
- `SkillRegistry` no longer depends on `SkillProvider` beans — seeder handles registration
- Capability handler classes (e.g. `LintHandler`, `OCRHandler`) remain as independent `@Component` beans in `skills/handlers/`
- Users can create custom skills via the import API (`POST /api/v1/skills/import`)
- `Skill.java` entity uses `@PrePersist ensureId()` (not `@GeneratedValue`) to allow explicit IDs for built-ins

## Template Verification

- `TemplateVerification` entity (V22 migration) — tracks the quality status of workflow templates
- `TemplateVerificationService` — manages verification state per template+project pair
- Workflows created from templates can be test-run to verify template correctness
- Verification results are visible in template CRUD API responses

## Workflow Factory

Automatic generation and verification of workflows from a topic:
- `WorkflowFactoryService` — generates, deploys to test instance, verifies, optionally promotes
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
- `WishService` — creates wish, starts asynchronous fulfillment
- Iterative process: Decomposition → Workflow/Chain generation → Execution → Feedback
- Supports up to `max_iterations` iterations for improvements
- `crystallize()` — promotes successful wish as template

## Docs System

Backend reads Markdown documentation files and serves them as an API:
- `DocsController` — `/api/v1/docs`, reads `.md` files from the configured `docs/` directory
- Path traversal protection built in
- Frontend displays docs in split layout with Markdown rendering

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
- **Endpoint:** `/ws` (SockJS Handshake)
- **Broker:** Simple Message Broker on `/topic`
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
- **Auto-generation:** Key is stored in `app_settings` on first start
- **Disableable:** `automation.security.enabled=false` (development)

### Public Endpoints (no API key required)

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `POST /api/v1/webhook/trigger/{token}`
- `/ws/**` (WebSocket)
- `/actuator/**`

### Rate Limiting

- **Filter:** `RateLimitFilter`
- **Algorithm:** Sliding Window (1 minute)
- **Default:** 120 requests/minute per client IP
- **Response header:** `X-RateLimit-Remaining`
- **Disableable:** `automation.security.rate-limit.enabled=false`

### CORS

- All origins allowed (`*`) with credentials
- All HTTP methods (GET, POST, PUT, DELETE, PATCH, OPTIONS)

## Database Migrations (Flyway)

The DB migrations (`src/main/resources/db/migration/`) document the feature timeline:

| Migration | Feature |
|---|---|
| V1–V9 | Base tables: Projects, Containers, Skills, Capabilities, Workflows, Runs, Schedules, Webhooks, Timestamps |
| V10–V11 | Performance indexes, Container Last Activity |
| V12 | App Settings |
| V13 | Workflow Model + Instructions |
| V14 | Schedule Types + Scheduled-At |
| V15 | Activity Log Table |
| V16 | Workflow Memory Table |
| V17 | Todos Table |
| V18 | Project Subtitle + Description |
| V19 | Workflow Templates Table |
| V20 | Input Context in Workflow Runs |
| V21 | Workflow Chains |
| V22 | Template Quality Status |
| V23 | Default Context in Workflows |
| V24 | Activity Log Composite Index |
| V25 | Workflow Test Status |
| V26 | Block Types Column |
| V27 | Chain Support for Schedules |
| V28–V29 | Wishes + Wish Reference Workflow IDs |
| V30 | Project AI Context |
| V31 | Workflow ID in Activity Log |
| V32 | Workflow Soft Delete (deleted_at) |
| V33 | Wish Iteration History |
| V34 | Workflow Provider |
| V35 | Project Archiving (archived_at) |
| V36 | Todo Session ID |
| V37 | Delayed Schedule Type |
| V38 | Scheduled Workflow ID nullable |
| V39 | Completion Triggers Table |
| V40 | Notification Configs Table |
| V41 | Assistant Sessions Table |
| V42 | Page Suggestions Table |
| V43 | Triggered By Schedule ID |
| V44 | Schedule End Conditions |
| V45 | Schedule Conflict Behavior |
| V46 | Stable Synced At |
| V47 | Context Delta in Step Results |

## Directory Structure

```
autotrigger/
├── CLAUDE.md                          ← Project instructions for Claude Code
├── VISION.md                          ← Product Vision
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
    ├── eslint.config.js               ← Flat Config (ESLint 9+)
    ├── index.html
    └── src/
        ├── App.tsx                    ← Router
        ├── main.tsx                   ← Entry Point
        ├── index.css                  ← Global Styles
        ├── api/client.ts              ← HTTP Client (Singleton)
        ├── types/index.ts             ← TypeScript Types
        ├── pages/                     ← 18+ Pages
        ├── components/
        │   ├── ui/                    ← Reusable UI Components
        │   ├── layout/                ← Sidebar, Header, Layout
        │   ├── shared/                ← StatusBadge, LogViewer, etc.
        │   ├── dashboard/             ← Chart Components
        │   └── workflow/              ← Block Editor, Templates
        ├── hooks/                     ← TanStack Query Hooks
        ├── stores/                    ← Zustand Stores
        ├── i18n/                      ← DE/EN Translations
        ├── theme/                     ← Theme Tokens
        ├── data/                      ← Skill Constants
        ├── utils/                     ← Utility Functions
        └── test/                      ← MSW Handlers, Test Setup
```
