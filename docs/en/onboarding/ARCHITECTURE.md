<!-- last-sync: 2026-06-29 from: docs/onboarding/ARCHITECTURE.md -->

# Architecture

This document describes the system architecture, data flow, and core concepts of the MySuricates platform.

---

## System Overview

```
+-------------------+     HTTP/WS      +--------------------+     Docker API     +------------------+
|                   | <-------------->  |                    | <--------------->  |                  |
|  React Frontend   |    /api/v1        | Spring Boot        |                    | Agent Container  |
|  (Port 3000)      |    /ws            | Backend            |                    | (Python/Node/Go) |
|                   |                   | (Port 9090)        |                    |                  |
+-------------------+                   +--------------------+                    +------------------+
                                              |         |
                                   SQLite     |         |    Claude CLI
                                   (Flyway)   |         |    (Host process)
                                              v         v
                                        +---------+  +----------+
                                        | .db     |  | claude   |
                                        | file    |  | (Host)   |
                                        +---------+  +----------+
```

### Communication Paths

1. **Frontend → Backend:** REST API (`/api/v1/*`) for CRUD, WebSocket (`/ws`) for real-time updates
2. **Backend → SQLite:** JPA/Hibernate via JDBC (WAL mode, HikariCP `maximum-pool-size: 1` -- SQLite allows only one writer)
3. **Backend → Docker:** Docker Java Client via Unix socket (`/var/run/docker.sock`)
4. **Backend → Claude CLI:** `ProcessBuilder` starts `claude` as a host process (stdin for prompts)

---

## Backend Architecture

### Layer Model

```
Controller → Service → Repository → Entity (JPA/SQLite)
     ↕            ↕
  DTO/Mapper   Executor → BlockExecutor → HostClaudeService
                              ↕
                       ContainerService → Docker API
```

### Package Structure

| Package | Responsibility |
|---|---|
| `controller/` | REST endpoints, request validation, response mapping |
| `entity/` | JPA entities, enums, BlockDefinition (JSON-serialized) |
| `dto/request/` | Incoming request objects (with validation) |
| `dto/response/` | Outgoing response objects (projection) |
| `mapper/` | MapStruct mappers: entity ↔ DTO conversion |
| `repository/` | Spring Data JPA interfaces |
| `service/` | Business logic (as of 2026-09-18: 325 files in 35 sub-packages) |
| `executor/` | Workflow execution engine + block executors |
| `config/` | Spring beans, seeders, built-in definitions |
| `security/` | API key auth, rate limiting, Tailscale integration |
| `websocket/` | WebSocket handler for updates + terminal |
| `event/` | Spring Application Events (async processing) |
| `exception/` | Custom exceptions + global error handler |
| `util/` | JSON converters, helper functions |

### Controller Overview (selection from 47 REST controllers)

The table below lists the central controllers. The full list is in [../api-referenz.md](../api-referenz.md); re-measure the count with `find ai-automation-backend/src/main/java -name '*Controller.java' | wc -l`.

| Controller | Path | Function |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | Project CRUD, container start/stop |
| `WorkflowController` | `/api/v1/workflows` | Workflow CRUD, execution, import/export |
| `ExecutionController` | `/api/v1/executions` | Running executions, pause/resume/cancel |
| `SkillController` | `/api/v1/skills` | Skill catalog, import |
| `ScheduleController` | `/api/v1/schedules` | Cron schedule CRUD |
| `WebhookController` | `/api/v1/webhooks` | Webhook trigger CRUD + triggering |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | Template CRUD |
| `TodoController` | `/api/v1/todos` | Todo CRUD |
| `ActivityLogController` | `/api/v1/activity-log` | Audit log queries |
| `ConversionController` | `/api/v1/conversions` | Agentic → deterministic |
| `LearningController` | `/api/v1/learning` | Learning insights |
| `WorkflowMemoryController` | `/api/v1/workflows/{workflowId}/memory` | Workflow conversation history |
| `SystemController` | `/api/v1/system` | Health, version, metrics |

---

## Frontend Architecture

### Component Hierarchy

```
App.tsx (Router) -- excerpt, complete in src/App.tsx
  └── Layout (Sidebar + Header)
       ├── Dashboard         → QuickStats, RecentRuns, ExecutionInsights, Schedules, Todos
       ├── Projects          → Project list with container status
       ├── ProjectDetail     → Project workflows & setup
       ├── Workflows         → Workflow list + template selection
       ├── WorkflowDetail    → Block editor (drag-drop) + execution
       ├── WorkflowExecution → Step timeline + terminal
       ├── Runs              → Execution history with filters
       ├── Skills            → Skill catalog
       ├── Schedules         → Cron schedules
       ├── Webhooks          → Webhook configuration
       ├── Todos             → Todo list
       ├── ActivityLog       → Audit trail
       └── Settings          → API key, theme, language
```

### State Management

| Type | Technology | Usage |
|---|---|---|
| **Server State** | TanStack Query | All API data (workflows, projects, skills, runs...) |
| **Client State** | Zustand | Settings, theme, sidebar, toast notifications |

**TanStack Query Configuration:**
- `staleTime: 30s` — data is considered fresh for 30 seconds
- `retry: 1` — one retry on error
- `refetchOnWindowFocus: false` — no automatic reload on tab switch

**Zustand Stores:**
- `settingsStore` — API key, advanced mode (persisted in localStorage)
- `themeStore` — active theme (registry `theme/themes.ts`), custom colors per theme
- `sidebarStore` — sidebar open/closed
- `toastStore` — notification queue

### Routing (React Router v7, selection)

The application has 39 pages under `src/pages/`; some of them belong to optional modules and are wrapped in `ModuleRoute` in `App.tsx`. Excerpt:

| Path | Page |
|---|---|
| `/` | Dashboard |
| `/projects` | Project list |
| `/projects/:id` | Project detail |
| `/workflows` | Workflow list |
| `/workflows/:id` | Workflow editor |
| `/workflows/:id/run/:runId` | Execution view |
| `/runs` | Execution history |
| `/skills` | Skill catalog |
| `/schedules` | Schedules |
| `/webhooks` | Webhooks |
| `/todos` | Todo list |
| `/activity` | Activity log |
| `/settings` | Settings |

All pages are loaded via `lazy()` (code splitting). The complete route list is in `src/App.tsx`.

---

## Data Model

### Entity Relationships

```
Project (1) ←→ (1) Container
    |
    ↓ (1:N)
Workflow (N) ←→ (N) Skill      [via workflow_skills]
    |
    ↓ (1:N)
WorkflowRun
    |
    ↓ (1:N)
StepResult

ScheduledWorkflow → Workflow
WebhookTrigger → Workflow
WorkflowMemory → Workflow
WorkflowTemplate (independent)
Todo (independent)
ActivityLog (independent)
```

### Database Migrations (Flyway, currently V1-V81)

The table below describes the base schema (V1-V19). V20-V81 mostly add columns and indexes to these tables plus a few new tables; the full list is under `src/main/resources/db/migration/`.

| Migration | Table | Description |
|---|---|---|
| V1 | `projects` | Projects (name, type, path, status) |
| V2 | `containers` | Docker containers (image, status, mounts) |
| V3 | `skills` | Skills (name, category, AI context) |
| V4 | `capabilities` | Skill capabilities |
| V5 | `workflows`, `workflow_skills` | Workflows + skill assignment |
| V6 | `workflow_runs`, `workflow_steps` | Executions + steps |
| V7 | `scheduled_workflows` | Scheduled executions |
| V8 | `webhook_triggers` | HTTP triggers |
| V9 | `updated_at` columns | Timestamps for all tables |
| V10 | Performance indexes | Database optimization |
| V11 | `container.last_activity` | Idle timeout tracking |
| V12 | `app_settings` | Application settings |
| V13 | `workflow.model`, `workflow.instructions` | AI model & instructions |
| V14 | `schedule.type`, `schedule.scheduled_at` | Extended scheduling |
| V15 | `activity_log` | Audit logging |
| V16 | `workflow_memory` | Conversation history |
| V17 | `todos` | Todo management |
| V18 | `project.subtitle`, `project.description` | Project extension |
| V19 | `workflow_templates` | Workflow templates |

---

## Workflow Execution

### Execution Flow

```
1. Frontend: POST /api/v1/workflows/{id}/execute
2. WorkflowController → WorkflowExecutor.execute()
3. WorkflowRun is created with status RUNNING
4. Response is returned immediately (async)
5. Virtual thread executes blocks sequentially:
   ┌─────────────────────────────────┐
   │ for each BlockDefinition:       │
   │   1. Select BlockExecutor       │
   │   2. Execute block              │
   │   3. Save StepResult            │
   │   4. Send WebSocket update      │
   │   5. Update previousOutput      │
   └─────────────────────────────────┘
6. WorkflowRun status → COMPLETED/FAILED
7. Publish WorkflowRunCompletedEvent
```

### Block Types

| Type | Executor | Description |
|---|---|---|
| `simple` | `SimpleBlockExecutor` | A single AI call with a prompt, uses the workflow model |
| `shell` | `ShellBlockExecutor` | Execute a shell command (no API access, no DB access) |
| `agentic` | `AgenticBlockExecutor` | Multi-step agent with REASON+ACT phases and a token budget |
| `conditional` | `ConditionalBlockExecutor` | Branch: `branch_true` / `branch_false` |
| `foreach` | `ForEachBlockExecutor` | Iteration over a list, sub-blocks per element |
| `while` | `WhileBlockExecutor` | Loop with exit condition and `max_iterations` |
| `switch` | `SwitchBlockExecutor` | Multi-way branch based on a match value |
| `retry` | `RetryBlockExecutor` | Retries a sub-block with backoff |
| `approval` | `ApprovalBlockExecutor` | Pauses until approval (optionally with choices) |
| `sub-workflow` | `SubWorkflowBlockExecutor` | Calls another workflow |
| `skill-capability` | `SkillCapabilityBlockExecutor` | Runs a capability of a skill |
| `human-input` | `HumanInputBlockExecutor` | Asks the user for values, pauses until answered |
| `integration` | `IntegrationBlockExecutor` | Action of an integration provider (e.g. `github:create_issue`) |
| `document` | `DocumentBlockExecutor` | OCR / document processing (PDF, images) |
| `todo-create` | `TodoCreateBlockExecutor` | Creates a TODO and stores its id in a variable |
| `todo-update` | `TodoUpdateBlockExecutor` | Updates fields of an existing TODO by id |
| `todo-close` | `TodoCloseBlockExecutor` | Sets an existing TODO to DONE by id |
| `compress` | `CompressBlockExecutor` | Deterministic compression of an earlier block output (no AI) |
| `compress-ai` | `CompressAiBlockExecutor` | AI compression of an earlier block output (one capped call) |

The list is bound to the registered executors: `BlockTypeListsGuardTest` (backend) and `types/__tests__/blockTypes.test.ts` (frontend) enforce parity with `BLOCK_TYPES`. Per-type configuration details: [WORKFLOW-GUIDE.md](../WORKFLOW-GUIDE.md).

### Agentic Block (Goal→Reason→Act)

The `AgenticBlockExecutor` implements an AI-driven loop:

```
Iteration 1..N:
  1. REASON: Claude analyzes goal + results so far
     → Returns JSON with {action, parameters, reasoning}
  2. ACT: Executes the proposed action (Claude CLI)
     → Result is stored as an observation
  3. CHECK: Goal reached? → If yes, exit loop
```

**Safety Limits:**

| Limit | Default | Description |
|---|---|---|
| `max_iterations` | 10 | Maximum number of iterations |
| `iteration_timeout_seconds` | 300 | Timeout per iteration |
| `total_timeout_seconds` | 1800 | Total timeout (30 minutes) |
| `max_total_tokens` | 200000 | Token budget (cumulative) |
| `max_repeated_actions` | 3 | Stop on identical repeated actions |
| `require_approval` | false | Approval required before each action |

### ExecutionContext

The `ExecutionContext` is passed through all blocks:

```java
ExecutionContext {
  containerId      // Docker container ID
  workflowRunId    // Current execution
  projectPath      // Working directory
  previousOutput   // Output of the last block
  variables        // Template variables {{ name }}
  stepCounter      // Step counter
  paused           // Pause status
  cancelled        // Cancellation status
  pendingApproval  // Pending approval
}
```

Template variables are resolved with `{{ variable_name }}` or `{{variable_name}}`.

---

## AI Integration (Claude CLI)

### HostClaudeService

All AI calls go through `HostClaudeService`, which starts the Claude CLI on the host system:

```
Backend → ProcessBuilder → claude (Host process)
```

**Important Details:**
- Prompts are written via **stdin** (not as CLI arguments) to avoid `E2BIG` errors
- `CLAUDE_CODE` and `CLAUDECODE` environment variables are removed (prevents nested session detection)
- `--model` flag for model selection -- always use the CLI aliases `opus`, `sonnet`, `haiku`, `fable`, never dated model ids
- `--max-tokens` flag for optional per-call token limit
- Configurable timeout (default: 300 seconds)

### Two-Level Instruction System

1. **Base instructions** (`agent-instructions.md`) — apply to all workflows
2. **Workflow-specific instructions** (`workflow.instructions`) — editable per workflow in the Monaco editor

---

## WebSocket Communication

| Endpoint | Purpose |
|---|---|
| `/ws` | Real-time updates for workflow executions (STOMP) |
| `/ws/terminal` | Terminal access to Docker containers (xterm.js) |

### WebSocket Messages

- **Step updates:** Status, output, errors for each execution step
- **Run updates:** Overall execution status (RUNNING, COMPLETED, FAILED)
- **Approval requests:** Notification of pending approvals

---

## Security

| Feature | Configuration | Default |
|---|---|---|
| API Key Auth | `AUTOMATION_SECURITY_ENABLED` | `true` |
| Rate Limiting | `AUTOMATION_RATE_LIMIT_ENABLED` | `true` (also configurable in Settings → Execution) |
| Optional modules | `AUTOMATION_MODULES_PRESET` | `core` (remote access: `AUTOMATION_MODULES_REMOTE_ACCESS`) |
| Trusted Proxies | `AUTOMATION_TRUSTED_PROXIES` | `127.0.0.1,::1` |

The API key is passed in the `X-API-Key` header. In the frontend it is stored in `settingsStore` (localStorage) and automatically added to every request.

---

## Docker Container Management

### Container Lifecycle

```
CREATED → RUNNING → STOPPED
              ↑         |
              └─────────┘ (Restart)
```

- Containers are created per project (1:1 relationship)
- Resource limits: 4 GB RAM, CPU quota, 100 PIDs
- Idle timeout: 60 minutes (configurable)
- Docker socket is mounted into the backend container

### Agent Containers

| Dockerfile | Image | Purpose |
|---|---|---|
| `Dockerfile.agent-python` | `automation-agent-python:latest` | Python-based skills |
| `Dockerfile.agent-node` | `automation-agent-node:latest` | Node.js-based skills |
| `Dockerfile.agent-go` | `automation-agent-go:latest` | Go-based skills |
| `Dockerfile.backend` | Backend image | For Docker-based deployment |

Agent containers are NOT started via Docker Compose — they are managed dynamically by the backend via the Docker API.
