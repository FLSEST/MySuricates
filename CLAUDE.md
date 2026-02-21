# AI Automation Platform (AutoPilot)

AI-powered workflow automation platform with a skill system, Docker-based execution, and a React frontend.

## Project Structure

```
ai-automation-backend/     Spring Boot 3.2 (Java 21) REST API
  src/main/java/com/automation/
    controller/            REST controllers (Workflow, Skill, Execution, Learning, Schedule, Webhook, System, Project, Conversion)
    entity/                JPA entities (Workflow, Skill, Project, Container, ScheduledWorkflow, WebhookTrigger, WorkflowRun, ...)
    dto/                   Request/response DTOs
    mapper/                MapStruct mappers (Entity <-> DTO)
    repository/            Spring Data JPA repositories
    service/               Business logic
    executor/              Docker-based skill execution
    executor/blocks/       Block executors (Simple, Retry, Conditional, ForEach, While, Agentic, Approval, SubWorkflow, SkillCapability)
    service/ai/            Host Claude CLI integration (HostClaudeService)
    service/cli/           Claude output parsing (ClaudeOutputParser, ClaudeJsonResponse)
    service/conversion/    Agentic-to-workflow conversion (WorkflowConversionService)
    security/              API key auth, rate limiting, Tailscale
    config/                Spring configuration
    websocket/             WebSocket for real-time updates
  src/main/resources/
    db/migration/          Flyway migrations (V1-V10, SQLite)
    application.yml        App config
  docker/                  Dockerfiles for agent containers (Python, Node, Go)

ai-automation-frontend/    React 18 SPA (TypeScript)
  src/
    api/client.ts          Shared API client (fetch-based, /api/v1 prefix)
    pages/                 Route pages (Dashboard, Projects, Workflows, Skills, Schedules, Webhooks, Settings, ...)
    components/            UI components (ui/, layout/, dashboard/, workflow/, shared/, skill/)
    hooks/                 TanStack Query hooks (useWorkflows, useSkills, useProjects, useSchedules, useWebhooks, useConversion, useInsights, ...)
    data/                  Skill catalog data, workflow templates
    utils/                 Utility functions (formatTime, extractWorkflowVariables)
    stores/                Zustand stores (settings, sidebar, theme, toast)
    i18n/                  Translations (de.ts, en.ts)
    types/index.ts         Shared TypeScript types
  cypress/                 E2E tests (Cypress 13)
```

## Build & Run

```bash
# Backend
cd ai-automation-backend
mvn clean package                        # Build JAR
mvn test                                 # Run tests
mvn spring-boot:run                      # Start dev server (port 9090)

# Frontend
cd ai-automation-frontend
npm install                              # Install dependencies
npm run dev                              # Start dev server (port 3000, proxies /api to :9090)
npm run build                            # Production build (tsc + vite)
npm run lint                             # ESLint

# E2E Tests
cd ai-automation-frontend
npm run test:e2e                         # Run Cypress headless
npm run cy:open                          # Open Cypress UI
```

## Key Conventions

- **Backend ORM**: Lombok for boilerplate, MapStruct for entity-DTO mapping
- **Database**: SQLite with Flyway migrations. Naming: `V{N}__description.sql`
- **API prefix**: All endpoints under `/api/v1`
- **Frontend state**: Zustand for client state (settings, theme, sidebar), TanStack Query for server state
- **API client**: Singleton `api` instance in `src/api/client.ts` — all hooks use this
- **i18n**: German (`de.ts`) and English (`en.ts`), both must be updated together
- **Styling**: TailwindCSS utility classes, dark mode support via theme store
- **PWA**: Vite PWA plugin configured, app name "AutoPilot"
- **WebSocket**: Backend sends real-time updates via `/ws`, frontend connects via `useWebSocket` hook

## Architecture Notes

- Workflows consist of ordered steps; each step runs a Skill
- Skills are categorized (see `SkillCategory` enum) and have associated Docker containers (Python/Node/Go)
- Docker containers are used for project file mounting, lifecycle management, and ad-hoc terminal access
- **All Claude CLI execution happens on the host** (not inside Docker containers) to avoid nested-session detection
  - `HostClaudeService.executeAction()` runs `claude -p` on the host with `ProcessBuilder.directory(projectPath)`
  - `CLAUDE_CODE` and `CLAUDECODE` env vars are removed from the process environment to prevent nested-session errors
  - `CommandExecutorService` provides `executeCommandOnHost()` / `executeStepOnHost()` / `injectSkillContextOnHost()` methods
- Docker containers have resource limits: 4GB memory, CPU quota, 100 PIDs, 60min idle timeout
- Security is optional (disabled by default): API key auth via `X-API-Key` header, rate limiting
- Block types: simple, retry, conditional, forEach, while, agentic, approval, sub-workflow, skill-capability
- Agentic blocks use a Goal→Reason→Act→Observe loop powered by Host Claude CLI
- Workflow Conversion: Successful agentic runs can be converted to deterministic workflows (rule-based + AI)
- Terminal WebSocket at `/ws/terminal` for real-time container access

## Agent Instructions System

Two-level instruction injection via Claude CLI `--append-system-prompt` / `--system-prompt` flags:

1. **Base instructions** (`src/main/resources/agent-instructions.md`) — loaded at startup by `AgentInstructionsService`, apply to all workflows
2. **Workflow-specific instructions** (`workflow.instructions` column) — per-workflow, editable in UI (Monaco editor)

Injection paths:
- **Block execution** (all block types): Instructions passed via `--append-system-prompt` flag on host Claude CLI (`HostClaudeService.executeAction()`)
- **Agentic orchestrator** (REASON phase): System prompt passed via `--system-prompt` flag to `HostClaudeService.reasonStructured()`, includes workflow instructions in `## Workflow Instructions` section

## Model Selection

- `workflow.model` field (e.g. `claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5`)
- Passed via `--model` flag to Claude CLI on the host
- Agentic REASON phase uses the locally configured default model

## Per-Call Token Limit

- `HostClaudeConfig.maxTokens` (property `automation.host-claude.max-tokens`) — optional per-call output token limit
- Applied via `--max-tokens` flag in both `reasonStructured()` and `executeAction()`
- Distinct from the agentic block's `max_total_tokens` budget (which tracks cumulative input+output across all iterations)

## Agentic Block Safety

| Config Key | Default | Description |
|---|---|---|
| `max_iterations` | 10 | Hard iteration cap |
| `iteration_timeout_seconds` | 300 | Per-iteration wall-clock timeout |
| `total_timeout_seconds` | 1800 | Overall block timeout (30min) |
| `max_total_tokens` | 200000 | Token budget (input+output, reason+act) |
| `max_repeated_actions` | 3 | Abort after N identical consecutive actions |
| `require_approval` | false | Approval gate before each action |

Additional safety:
- History compression (after 7 turns, older turns are summarized to manage context window)
- Token tracking warnings: `AgenticBlockExecutor` logs `WARN` when REASON or ACT phase returns no usage data, indicating the token budget is not being enforced
- All 6 safety fields are pre-populated in the frontend "Add Block" dialog defaults

## Sub-Workflow Block

- `SubWorkflowBlockExecutor` — runs another workflow as a nested block
- Circular reference detection via `__parent_workflow_ids` context variable
- Max nesting depth: 5 levels
- Polling-based completion with 30min timeout
- `@Lazy` injection on `WorkflowExecutor` to break Spring DI cycle
