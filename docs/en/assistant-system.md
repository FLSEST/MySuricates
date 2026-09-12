> *Last updated: [auto-generated]*

# Assistant System

The Assistant is a context-sensitive chat assistant available as a floating panel in the platform. It knows the current page, can create or explain workflows, and suggests context-sensitive actions.

## Overview

The Assistant system consists of four areas:

1. **Chat** — Freeform conversation via `POST /api/v1/assistant/chat`
2. **Sessions** — Persisted chat histories per page (V41 migration: `assistant_sessions`)
3. **Page Suggestions** — Precomputed, page-specific action suggestions (V42 migration: `page_suggestions`)
4. **Pending Tools** — Confirmation-required tool mechanism for destructive actions (in-memory registry)

## Backend Components

### AssistantService

- Main class for chat logic (`service/AssistantService.java`)
- Prepares the context (current page, workflow data, etc.)
- Calls Claude CLI on the host (`HostClaudeService`)
- Default model: `sonnet` (seeded into `app_settings` on startup, cached in `cachedAssistantModel`)
- Allowed CLI tools: `Read`, `Grep`, `Glob`, `WebFetch` (`CHAT_ALLOWED_TOOLS`)
- Auto-fetch loop: up to **3 rounds** with **3 fetches** per round (`MAX_FETCH_ROUNDS`, `MAX_FETCHES_PER_ROUND`)
- Detects explicit mutation intent via regex (German verbs + target nouns) — only then are mutating actions passed through
- Action suggestions are fetched in parallel via `AssistantActionSuggestionService` (35s timeout)
- Returns `AssistantChatResponse` (response text + actions + optional PendingTools)

### AssistantSessionService

- Persists chat histories in the `assistant_sessions` table (V41) (`service/AssistantSessionService.java`)
- `appendMessages(sessionId, request, response)` — appends user and assistant messages, saves PendingTools metadata
- `appendToolResult(sessionId, result)` — appends a `ToolExecutionResult` to the last assistant message with the matching PendingTool
- `listRecentSessions()` — top **50** sessions sorted by `startedAt` desc
- `getSession(id)` / `deleteSession(id)` — single access / deletion
- Preview is truncated to **100 characters** (first user message)

### PageSuggestionService

- Generates and caches context-sensitive suggestions per UI page (`service/PageSuggestionService.java`)
- Persisted in the `page_suggestions` table (V42)
- `getSuggestions(page)` — returns cached suggestions or defaults
- `refreshSuggestions(page)` — generates via Claude (uses last 10 sessions for the page) and saves
- `onSessionUpdated(AssistantSessionUpdatedEvent)` — `@Async @EventListener`, triggers throttled refresh
- Throttle: **600 seconds** (10 min.) per page, timeout: **60 seconds**, max. **4** suggestions
- Dedicated model: `suggestions-model` setting (default: `sonnet`)
- Defaults for 13 pages: `run`, `workflow`, `workflows`, `runs`, `todos`, `project`, `projects`, `schedules`, `activity`, `insights`, `wishes`, `factory`, `chains`

### AssistantSessionUpdatedEvent

- Spring Application Event (`event/AssistantSessionUpdatedEvent.java`), fired after every chat call
- Record with one field: `String page`
- Fired by `AssistantController.chat()` after persisting
- Consumed by `PageSuggestionService.onSessionUpdated()` for throttled suggestion refresh

### AssistantActionSuggestionService

- Generates context-sensitive action suggestions in parallel with chat (`service/assistant/AssistantActionSuggestionService.java`)
- Timeout: **30 seconds**, max. **4** actions
- Model: `suggestions-model` setting (default: `sonnet`)
- Navigation types: `show_run`, `show_workflow`, `start_wish`, `ask_choice`, `run_workflow`
- Propose types (converted to PendingTools): `propose_create_todo`, `propose_cancel_run`, `propose_rerun_workflow`, `propose_update_todo_status`
- Prompt contains page context (max 6000 characters), workflow index (top 40), last 5 runs

### AssistantChatBroadcaster

- WebSocket broadcast via `SimpMessagingTemplate` (`service/assistant/AssistantChatBroadcaster.java`)
- `broadcastStatus(requestId, phase, detail, startNanos)` → topic `/topic/assistant/{requestId}/status`
- `broadcastEvent(sessionId, eventKind, title, detail, linkTo)` → topic `/topic/assistant/{sessionId}/events`
- Phases: `RECEIVED`, `BUILDING_CONTEXT`, `THINKING`, `FETCHING_DATA`, `EXECUTING_TOOLS`, `COMPLETED`, `ERROR`

### PendingToolRegistry

- In-memory registry for confirmation-required tool calls (`service/assistant/PendingToolRegistry.java`)
- `ConcurrentHashMap<String, PendingTool>`, TTL: **30 minutes**
- `register(type, description, payloadJson)` — creates UUID, sets expiration time
- `take(id)` — atomic removal and return (filters expired entries)
- `cleanupExpired()` — `@Scheduled(fixedDelay = 300000)` (every 5 min.)

## API Endpoints

### Chat

```
POST /api/v1/assistant/chat
```

Request body (`AssistantChatRequest`):
```json
{
  "message": "Explain the last workflow error to me",
  "history": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}],
  "context": {
    "page": "workflows",
    "entityId": "optional",
    "projectId": "optional",
    "workflowId": "abc-123",
    "runId": "optional",
    "pathname": "/workflows/abc-123"
  },
  "provider": "optional-provider-id",
  "language": "de",
  "sessionId": "optional-uuid",
  "requestId": "optional-uuid"
}
```

Response (`AssistantChatResponse`):
```json
{
  "message": "The workflow failed because...",
  "actions": [],
  "model": "sonnet",
  "provider": "CLAUDE",
  "responseTimeMs": 3200,
  "pendingTools": []
}
```

- `message` (required), `history`, `context`, `provider`, `language`, `sessionId`, `requestId` — all except `message` optional
- `context.page` controls which platform data is injected into the system prompt
- `requestId` is used for WebSocket status updates (topic `/topic/assistant/{requestId}/status`)
- Response fields `provider`, `responseTimeMs` only present when non-null (`@JsonInclude(NON_NULL)`), `pendingTools` only when non-empty (`@JsonInclude(NON_EMPTY)`)

### Page Suggestions

```
GET  /api/v1/assistant/suggestions?page=workflows
POST /api/v1/assistant/suggestions/refresh?page=workflows
```

- `GET` returns cached suggestions as `List<String>` (immediate, no AI call)
- `POST /refresh` generates new suggestions via Claude and returns them (blocking)

### Pending Tools

```
POST /api/v1/assistant/pending-tools/{id}/confirm
POST /api/v1/assistant/pending-tools/{id}/reject
```

Request body (optional): `{"sessionId": "uuid"}`

- `confirm` executes the tool and returns a `ToolExecutionResult` (`{toolId, type, success, message}`)
- `reject` marks the tool as rejected (message: `"✗ Rejected"`)
- Expired tools (TTL 30 min.) return HTTP **410 Gone** (message: `"✗ Suggestion expired"`)
- When `sessionId` is provided, the result is written to the session via `AssistantSessionService.appendToolResult()`

## Database Schema

### assistant_sessions (V41)

Stores chat histories as a JSON array per session.

| Column | Type | Description |
|---|---|---|
| `id` | TEXT PK | Session UUID |
| `page` | TEXT | UI page (e.g. `"workflows"`) |
| `entity_id` | TEXT | Optional entity reference (e.g. workflow ID) |
| `preview` | TEXT | First user message (max 100 characters, V58) |
| `messages` | TEXT | JSON array of chat messages (full length) |
| `message_count` | INTEGER | Number of messages |
| `started_at` | REAL | Creation timestamp |
| `ended_at` | REAL | Last update |

### page_suggestions (V42)

Cached suggestions per UI page.

| Column | Type | Description |
|---|---|---|
| `id` | TEXT PK | UUID |
| `page` | TEXT NOT NULL UNIQUE | UI page (e.g. `"dashboard"`) |
| `suggestions` | TEXT NOT NULL DEFAULT '[]' | JSON array of suggestions |
| `generated_by` | TEXT NOT NULL DEFAULT 'CLAUDE' | Generator identifier |
| `generated_at` | REAL NOT NULL | Timestamp of last generation |
| `based_on_sessions` | INTEGER NOT NULL DEFAULT 0 | Number of sessions considered |

## Controller Responsibilities

Both controllers are registered under the base path `/api/v1/assistant`:

| Controller | Endpoints | Description |
|---|---|---|
| `AssistantController` | `POST /chat` | Process chat request, persist session |
| `AssistantController` | `GET /sessions` | Last 50 sessions with preview (without messages) |
| `AssistantController` | `GET /sessions/{id}` | Single session with all messages |
| `AssistantController` | `DELETE /sessions/{id}` | Delete session |
| `AssistantController` | `POST /pending-tools/{id}/confirm` | Confirm and execute PendingTool |
| `AssistantController` | `POST /pending-tools/{id}/reject` | Reject PendingTool |
| `PageSuggestionController` | `GET /suggestions`, `POST /suggestions/refresh` | Page-specific suggestions |

## Tools (Server-Side Actions)

The assistant can suggest actions via `<tool type="...">JSON</tool>` tags. `AssistantToolExecutor` (`service/assistant/AssistantToolExecutor.java`) parses the tags, registers them as **PendingTools** in the `PendingToolRegistry`, and replaces them in the response text with a placeholder (`"[Action awaiting confirmation: <description>]"`). Execution only occurs after explicit user confirmation via `POST /pending-tools/{id}/confirm`.

### Available Tools

| Tool | Required Fields | Description |
|------|----------------|-------------|
| `create_todo` | `title` | Create a todo (optional: description, priority, tags, projectId) |
| `update_todo_status` | `id`, `status` | Change todo status (OPEN/IN_PROGRESS/DONE/BACKLOG) |
| `create_workflow` | `projectId`, `name` | Create a workflow (optional: blocks, instructions, model, defaultContext) |
| `update_workflow` | `workflowId` | Update workflow fields (only provided fields are changed) |
| `delete_workflow` | `workflowId` | Soft-delete a workflow |
| `start_workflow` | `workflowId` | Start a workflow (optional: context for input variables) |
| `cancel_run` | `runId` | Cancel a running run |
| `create_chain` | `name`, `entries` | Create a workflow chain |

All 8 tool types are defined in `MUTATING_TOOL_TYPES` and require user confirmation.

### PendingTool Lifecycle

1. Claude generates `<tool type="...">JSON</tool>` in its response
2. `AssistantToolExecutor.extractTools()` parses the tags, registers each as a `PendingTool` (UUID, TTL 30 min.)
3. Frontend displays `PendingToolCard` with Confirm/Reject buttons
4. User confirms → `POST /pending-tools/{id}/confirm` → `executeConfirmed()` executes
5. User rejects → `POST /pending-tools/{id}/reject` → marked as rejected
6. Result (`ToolExecutionResult`) is written to the session via `appendToolResult()`

### JSON Sanitization

`AssistantToolExecutor.sanitizeJson()` normalizes 10 Unicode fullwidth characters before JSON parsing (e.g. `，` → `,`, `：` → `:`, `｛`/`｝` → `{`/`}`, `“` / `”` → `"`).

### Safety Rules

- All tool tags are registered as PendingTools — no direct execution without user confirmation
- Expired PendingTools (>30 min.) are rejected with HTTP 410 Gone
- `PendingToolRegistry.cleanupExpired()` cleans up every 5 minutes

## Fetch Mechanism (Dynamic Data Loading)

The assistant can load additional data via `<fetch type="..." id="..."/>` tags. `AssistantFetchService` (`service/assistant/AssistantFetchService.java`) resolves these and returns the data as supplemental context.

- **Max. 3 fetch rounds** with **3 fetches** per round (`MAX_FETCH_ROUNDS`, `MAX_FETCHES_PER_ROUND` in `AssistantService`)
- Truncation markers show size info: `[... skipped N of M characters ...]` (tail-aware via `TruncationUtil.smart`, head + end are preserved) or `[Output truncated: N of M characters]` (legacy head-first)
- Auto-fetch is restricted to specific types (`AUTO_FETCH_TYPES`)

### Available Fetch Types (18)

| Type | Description | Truncation |
|------|------------|------------|
| `run_detail` | Run detail with steps | Error: smart(200,400), Output: smart(200,600) — tail-aware |
| `step_output` | Single step output (`runId/stepNumber`); paging via `?offset=<n>` / `?tail=<n>` | Default smart(2000,6000); window/tail max 8000 |
| `workflow_detail` | Workflow overview | Desc: 150, Instructions: 400, Prompt: 200 |
| `workflow-full` | Complete workflow including config | Instructions: 600, Config values: 300 |
| `workflow-inputs` | Workflow input variables | Values: 100 |
| `workflow_runs` | Latest runs of a workflow | Top 5, Error: 100 |
| `find_workflow` | Workflow search by name | Max 20 results |
| `wish_detail` | Wish detail | Text: 200, Error: 300 |
| `docs` | Documentation section | Full: 8000, Section: 12000 |
| `pool_workflows` | All workflows in the pool | Desc: 120 |
| `pool_workflow` | Single pool workflow | Instructions: 500 |
| `project_context` | Project context via `ProjectContextService` | — |
| `project_files` | Project file listing | Max 50 top-level, depth 1, max 10 sub-entries |
| `project_file` | Single project file | Max 500KB, Content: 8000 |
| `skill` | Skill detail | Desc: 200, AI context: 400, Capability desc: 100 |
| `integration` | Integration detail | Desc: 200, Account: 80, Action desc: 100 |
| `chain` | Chain detail | Desc: 200 |
| `block-spec` | Block type specification (14 types) | — |

Doc files: `workflow-guide` → `docs/WORKFLOW-GUIDE.md`, `architektur` → `docs/architektur.md`, `vision` → `docs/vision.md`

## Context Injection

`AssistantContextBuilder` (`service/assistant/AssistantContextBuilder.java`) classifies `context.page` into a `PageCategory` and injects different context sections accordingly.

### Page Categories

| Page(s) | Category |
|---------|----------|
| `run`, `runs` | RUNS |
| `workflow`, `workflows`, `chains` | WORKFLOWS |
| `todos`, `project`, `projects` | TASKS |
| `wishes`, `factory` | WISHES |
| `schedules` | SCHEDULES |
| `debug` | DEBUG |
| everything else | OVERVIEW |

### Context Sections per Category

| Section | RUNS | WORKFLOWS | TASKS | WISHES | SCHEDULES | DEBUG | OVERVIEW |
|---------|------|-----------|-------|--------|-----------|-------|----------|
| Runs | — | — | — | — | — | — | ✓ |
| FailedWorkflows | — | — | — | — | — | ✓ | — |
| Todos | — | — | ✓ | — | — | — | ✓ |
| Wishes | — | — | — | ✓ | — | — | ✓ |
| Schedules | — | — | — | — | ✓ | — | ✓ |
| Projects | — | ✓ | ✓(compact) | — | — | — | ✓ |
| Workflows | — | ✓ | ✓(compact) | — | — | — | ✓ |
| Skills/Integrations/Chains/BlockTypes | — | ✓(compact) | — | — | — | — | ✓ |
| Memory & Sessions | — | — | — | — | — | — | ✓ |

**Special case RUNS**: When a run ID can be resolved, `buildRunFocusedSystemPrompt()` is used — a lean prompt with only the run/workflow detail, without global sections. The workflow is loaded separately via `workflowRepository.findById()` (no lazy-proxy access — OSIV is off; no `JOIN FETCH` — `@SQLRestriction` on `Workflow` would throw `FetchNotFoundException` for soft-deleted workflows). For **FAILED** runs the diagnosis evidence is pre-loaded directly: block type + block config of the failed step plus the step output tail-aware (`TruncationUtil.smart(output, 500, 4000)`) — a diagnosis usually needs no fetch round-trip anymore.

### 9 Context Section Classes (Package `service/assistant/context/`)

All implement `AssistantContextSection` with `append(StringBuilder sb, boolean compact)`:

1. **RunsContextSection** — Active runs + last 10 runs
2. **TodosContextSection** — All non-DONE todos; compact: counts by priority, full: top 10
3. **WishesContextSection** — Active wishes (limit 5), failed (limit 5)
4. **SchedulesContextSection** — Next 24 hours
5. **SkillsIndexSection** — Max 20 entries, desc: 80 characters
6. **IntegrationsIndexSection** — All providers with connected/disconnected status
7. **ChainsIndexSection** — Max 15 entries, desc: 80 characters
8. **BlockTypesIndexSection** — 14 block types (static list; it lags behind the 17 registered block executors — the three `todo-*` types are missing)
9. **FailedWorkflowsContextSection** — Broken workflows via `WorkflowDebugService`, error: 160 characters

### Additional on **OVERVIEW** Pages

- **Workflows** — Max **25** workflows (focused project prioritized), desc truncated to **60** characters
- **Last 5 sessions** — First user message (**150** characters) + first assistant response (**100** characters)
- **Workflow Memory** — Up to **8** active memories (**300** characters per memory)
- **Project files** — Max **300** characters, only `docs/` subdirectory expanded (limit 5 files)

## Model Selection

- **Chat model** (`assistant-model` setting): Default `sonnet`, cached in `AssistantService.cachedAssistantModel`
- **Suggestions model** (`suggestions-model` setting): Default `sonnet`, used by `PageSuggestionService` and `AssistantActionSuggestionService`
- Both models are seeded into `app_settings` on startup and can be changed at runtime via the Settings API (`refreshCachedModel()`)
- Haiku (`haiku`) is too imprecise for assistant tasks
- Opus (`opus`) is too slow for interactive chats

## Frontend Integration

### AssistantPanel (`components/AssistantPanel.tsx`)

The assistant is implemented as a floating panel in the root layout (`Layout.tsx`):
- Floating button (bottom right) with `MeerkatIcon` toggles the panel
- Resizable: width and height via drag handle, default **420×600px** (persisted in Zustand)
- Mobile-responsive: full width, 85vh height on screens < 640px
- ESC closes the panel
- Header shows "MySuricates" + last used model (without `claude-` prefix and date suffix) or "Online"
- Message queue: while a request is in progress, additional messages are queued and processed sequentially
- Cancel support via `AbortController`
- Enter to send, Shift+Enter for line break, auto-growing textarea (max 240px)

### Zustand Store (`stores/assistantStore.ts`)

Persistence key: `'assistant-sessions'` (localStorage)

| State | Type | Default |
|-------|------|---------|
| `open` | boolean | false |
| `sessions` | ChatSession[] | [] |
| `activeSessionId` | string \| null | null |
| `showHistory` | boolean | false |
| `panelWidth` | number | 420 |
| `panelHeight` | number | 600 |
| `pendingInput` | string \| null | null |
| `pendingContext` | AssistantPageContext \| null | null |

- `startNewSession()` — creates a new session, limit **20** sessions (`MAX_SESSIONS`)
- `appendMessage()` — preview from first user message (60 characters)
- `hydrateSession()` — adopt backend session into the store (for history loading)
- `pendingInput` / `pendingContext` — set by external pages, consumed on next send

### Session Management (`components/assistant/AssistantHistory.tsx`)

- Local store sessions take precedence when IDs match
- Backend sessions (`GET /sessions`) are added as "backend-only" entries
- Sorted by `startedAt` desc
- Click on backend-only session → on-demand `GET /sessions/{id}` → `hydrateSession()` into store
- Delete removes from store AND backend

### Page Suggestions

- `AssistantEmptyState` (`components/assistant/AssistantWelcome.tsx`): Displayed when no messages — MeerkatIcon + suggestion buttons
- `ContextChips` (same file): Horizontal scrollable pill bar above messages (dismissible)
- `SuggestionButton` in header (`components/layout/Header.tsx`): Sparkles icon, dropdown with max 4 suggestions
- `CommandPalette` (`components/palette/CommandPalette.tsx`): "Ask the assistant" line from 2 characters query
- Click on suggestion pre-fills input (no auto-send)

### WebSocket Integration (`hooks/useAssistantWebSocket.ts`)

- **Protocol**: STOMP over SockJS, URL `{origin}/ws?apiKey={key}`
- **Reconnect delay**: 5000ms

Two STOMP subscriptions:

1. **Status updates** (per request): `/topic/assistant/{requestId}/status`
   - Phases: `RECEIVED` → `BUILDING_CONTEXT` → `THINKING` → `FETCHING_DATA` → `EXECUTING_TOOLS` → `COMPLETED` | `ERROR`
   - Terminal phases (`COMPLETED`, `ERROR`) are cleared after 500ms
   - Drives `StatusIndicator` (phase label + elapsed time)

2. **Push events** (per session): `/topic/assistant/{sessionId}/events`
   - `AssistantPushEvent`: `eventKind`, `title`, `detail`, `linkTo`

### Additional Components

- **AssistantMessage** (`components/assistant/AssistantMessage.tsx`): Markdown rendering (react-markdown + remark-gfm), collapse at >300 characters, ActionButtons for `run_workflow`/`show_workflow`/`show_run`/`start_wish`/`ask_choice`, PendingToolCard with Confirm/Reject
- **StatusIndicator** (`components/assistant/AssistantWelcome.tsx`): WebSocket phases or rotating thinking phrases (2.5s interval)
- **assistantPanelUtils** (`components/assistantPanelUtils.ts`): `parsePageContext(pathname)` extracts structured context from URL

### External Triggers

| Source | Mechanism |
|--------|-----------|
| WorkflowDebug page | Diagnose button → `pendingContext` + new session + open panel |
| SimpleHome | `pendingInput` + open panel |
| CommandPalette | "Ask the assistant" → `pendingInput` + open panel |
| Header SuggestionButton | Dropdown selection → `pendingInput` + open panel |
