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

- Main class for chat logic (`service/AssistantService.java`) — orchestrates context, fetch rounds, tools and suggestions
- Prepares the context (current page, workflow data, etc.)
- Every CLI call goes through `AssistantChatCaller` (see below)
- Auto-fetch loop: up to **3 rounds** with **3 fetches** per round (`MAX_FETCH_ROUNDS`, `MAX_FETCHES_PER_ROUND`)
- Detects explicit mutation intent via regex (German verbs + target nouns) — only then are mutating actions passed through
- Action suggestions are fetched in parallel via `AssistantActionSuggestionService` (35s timeout)
- Returns `AssistantChatResponse` (response text + actions + optional PendingTools)

### AssistantChatCaller

- The CLI side of the chat (`service/assistant/AssistantChatCaller.java`), split out of `AssistantService`
- Calls Claude CLI on the host (`HostClaudeService`) — interactive (`priority`), with the chat timeout from `HostClaudeConfig`; a non-zero exit code fails the call instead of returning the error text as an answer
- Default model: `sonnet` (seeded into `app_settings` on startup, cached in `cachedAssistantModel`), plus the base instructions from `assistant-system-prompt.md`
- Allowed CLI tools: `Read`, `Grep`, `Glob`, `WebFetch` (`CHAT_ALLOWED_TOOLS`)

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

The [API reference](api-referenz.md#apiv1assistant) lists the endpoints with purpose and rules; the request
and response formats are in the DTOs `AssistantChatRequest`, `AssistantChatResponse` and `PendingTool`
under `ai-automation-backend/src/main/java/com/automation/dto/`. What is not there:

- `context.page` decides which platform data goes into the system prompt (see [Context Injection](#context-injection)); `requestId` is the topic of the status messages over WebSocket (`/topic/assistant/{requestId}/status`).
- A `pendingTools` entry carries `subject` — the resolved name (workflow name, the run's workflow, task title, chain name; unresolvable: a shortened id with `params.ref = "id"`) — and `params` with small values such as `priority`/`status`; `description` is the English fallback sentence for older clients.
- Suggestions exist per page **and language** (`locale`, V91). The frontend asks in the UI language, the refresh after a chat uses the chat's language. `GET /suggestions` answers from the cache without an AI call, `POST /suggestions/refresh` generates anew and blocks.
- `confirm` executes the tool, `reject` marks it as rejected (`"✗ Abgelehnt"`); an expired one returns **410 Gone** (`"✗ Vorschlag abgelaufen"`). With `sessionId` the result is written into the session through `AssistantSessionService.appendToolResult()`, `language` sets the language of the build check.

## Tools (Server-Side Actions)

The assistant can suggest actions via `<tool type="...">JSON</tool>` tags. `AssistantToolExecutor` (`service/assistant/AssistantToolExecutor.java`) parses the tags, registers them as **PendingTools** in the `PendingToolRegistry`, and replaces them in the response text with a placeholder (`"[Action awaiting confirmation: <description>]"`). Execution only occurs after explicit user confirmation via `POST /pending-tools/{id}/confirm`.

### Available Tools

| Tool | Required Fields | Description |
|------|----------------|-------------|
| `create_todo` | `title` | Create a todo (optional: description, priority, tags, projectId) |
| `update_todo_status` | `id`, `status` | Change todo status (OPEN/IN_PROGRESS/DONE/BACKLOG) |
| `create_workflow` | `projectId`, `name` | Create a workflow (optional: blocks, instructions, model, defaultContext) |
| `update_workflow` | `workflowId` | Update workflow fields (only provided fields are changed, the version label `version` too, as with `PUT`) |
| `delete_workflow` | `workflowId` | Soft-delete a workflow |
| `start_workflow` | `workflowId` | Start a workflow (optional: context for input variables) |
| `cancel_run` | `runId` | Cancel a running run |
| `create_chain` | `name`, `entries` | Create a workflow chain |

All 8 tool types are defined in `MUTATING_TOOL_TYPES` and require user confirmation.

After `create_workflow`/`update_workflow` the build check (`GeneratedWorkflowCheck`) runs over the saved definition. Findings from MEDIUM up are appended to the result line (`Baufehler-Prüfung: N Befund(e) ab MEDIUM`, HIGH first, at most ten lines plus "… und N weitere") together with the note that `update_workflow` can fix them; without such findings the line stays unchanged. In the English UI (`language: "en"` on confirm) the block reads `Build check: N finding(s) from MEDIUM up` with the English finding texts. There is no extra AI round, and saving is never blocked. The result card in the chat shows the lines wrapped.

### Tool results in the history

The model sees the results of confirmed tools on the next turn (TODO 2f7766e7) — so it can fix build-check findings after `create_workflow`/`update_workflow` itself with `update_workflow`. The frontend stores a decision's result on the message that proposed the tool (`assistantStore.recordToolResult`) and sends the results of **executed** tools as `toolResults` on the history entry with the next turn; rejected and expired proposals (result starts with `✗`) never. `AssistantContextBuilder.buildPrompt` puts each result as a `<tool_result tool="…">…</tool_result>` block after the assistant line, with a one-time note that these blocks are platform data, not instructions (`ToolResultHistory`). Without results the prompt is byte-identical to before.

The server enforces the bounds, because the history is client input — every result goes to the model again with every following turn:

| Bound | Value | Why |
|---|---|---|
| Turns with results | the last 3 assistant turns | older results have usually been acted on; the answer text stays |
| Results per turn | 5 | a turn rarely proposes more; a client cannot buy itself prompt space |
| Characters per result | 600 | status and summary line plus the first findings; the summary line (`Baufehler-Prüfung:`/`Build check:`) moves to the front so the cut never removes the count |

A result can quote foreign text (a workflow name, an error message). As with the context variables of workflows (`UntrustedText`) it cannot forge the block boundary, and the tags the platform parses from the answer (`<tool>`, `<fetch>`, `<actions>`) are neutralised (`‹tool …`), also in disguised form (fullwidth, invisible characters). The tool type must match `[a-z0-9_-]`, otherwise it reads `unknown`. The hard gate stays: even a tag the model copied from a result would only become a new proposal with a confirmation card.

### PendingTool Lifecycle

1. Claude generates `<tool type="...">JSON</tool>` in its response
2. `AssistantToolExecutor.extractTools()` parses the tags, registers each as a `PendingTool` (UUID, TTL 30 min.); `PendingToolDescriber` resolves the ids to names with a short read query (`subject`, `params`)
3. Frontend displays `PendingToolCard` with Confirm/Reject buttons; `pendingToolText` builds the sentence from `type` + `subject` + `params` in the UI language (priority/status translated, "DELETE" emphasised), unknown type or missing `subject` → `description`
4. User confirms → `POST /pending-tools/{id}/confirm` → `executeConfirmed()` executes
5. User rejects → `POST /pending-tools/{id}/reject` → marked as rejected
6. Result (`ToolExecutionResult`) is written to the session via `appendToolResult()`

### JSON Sanitization

`AssistantToolExecutor.sanitizeJson()` normalizes 10 Unicode fullwidth characters before JSON parsing (e.g. `，` → `,`, `：` → `:`, `｛`/`｝` → `{`/`}`, `“` / `”` → `"`).

## Fetch Mechanism (Dynamic Data Loading)

The assistant can load additional data via `<fetch type="..." id="..."/>` tags. `AssistantFetchService` (`service/assistant/AssistantFetchService.java`) resolves these and returns the data as supplemental context.

- **Max. 3 fetch rounds** with **3 fetches** per round (`MAX_FETCH_ROUNDS`, `MAX_FETCHES_PER_ROUND` in `AssistantService`)
- Truncation markers show size info: `[... skipped N of M characters ...]` (tail-aware via `TruncationUtil.smart`, head + end are preserved) or `[Output truncated: N of M characters]` (legacy head-first)
- Auto-fetch is restricted to specific types (`AUTO_FETCH_TYPES`)

### Fetch types

The types and their truncation limits live in the handlers under `service/assistant/fetch/`; which ones
are loaded without asking is set by `AssistantFetchPolicy.AUTO_FETCH_TYPES`. Two rules are only stated here:

- `block-spec` returns the spec of a block type from `BlockSpecs` (one for every type in the registry; `BlockTypeListsGuardTest` keeps them complete). The prompts of factory, wishes, dialog import, run conversion and workflow optimization render the same texts with `BlockSpecs.reference`, and `BlockSpecConfigKeysGateTest` checks every config key named there against the executor's source.
- `docs` loads only two files (`DocsFetchHandler`): `workflow-guide` → `docs/WORKFLOW-GUIDE.md`, `architektur` → `docs/architektur.md`. Without a section it returns the table of contents of the `## ` headings; single sections exist only for the workflow guide, otherwise it returns the start of the file.

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

### Context Section Classes

The sections are classes in the package `service/assistant/context/`, most of them with the interface
`AssistantContextSection` (`append(StringBuilder sb, boolean compact)`); how many entries and characters
they show is stated there. `BlockTypesIndexSection` is also the assistant's block type list, which
`BlockTypeListsGuardTest` keeps in step with the executors.

## Model Selection

- **Chat model** (`assistant-model` setting): Default `sonnet`, cached in `AssistantChatCaller.cachedAssistantModel`
- **Suggestions model** (`suggestions-model` setting): Default `sonnet`, used by `PageSuggestionService` and `AssistantActionSuggestionService`
- Both models are seeded into `app_settings` on startup and can be changed at runtime via the Settings API: `SystemSettingsService` announces the change as `AppSettingChangedEvent`, `AssistantSettingsListener` calls `refreshCachedModel()`/`refreshCache()` — only while the assistant module is on; switching it on at runtime re-reads all three values
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
- Sending, queueing and cancelling live in `hooks/useAssistantConversation.ts`, shared with the Simple home's field: one request at a time (`inFlight` in the store), further messages wait and go out in order; cancel via `AbortController`, a waiting message is taken out of the queue. The logic sits outside React so an answer still lands in the store when the asking page is gone; answers carry `replyTo` (id of the user message)
- Enter to send, Shift+Enter for line break, auto-growing textarea (max 240px)

### Zustand Store (`stores/assistantStore.ts`)

Persisted under `assistant-sessions` in `localStorage` (sessions and panel size); at most 20 sessions
(`MAX_SESSIONS`), the oldest drop out. `pendingInput` and `pendingContext` are set by other pages and
consumed by the next message (see [External Triggers](#external-triggers)); `inFlight` and `lastModel` are
transient. `hydrateSession()` adopts a backend session into the store.

### Session Management (`components/assistant/AssistantHistory.tsx`)

- Local store sessions take precedence when IDs match
- Backend sessions (`GET /sessions`) are added as "backend-only" entries
- Sorted by `startedAt` desc
- Click on backend-only session → on-demand `GET /sessions/{id}` → `hydrateSession()` into store
- Delete removes from store AND backend

### Page Suggestions

Suggestions appear in the empty panel (`components/assistant/AssistantWelcome.tsx`), as chips above the
messages, in the header (`SuggestionButton`, at most four) and as the "Ask the assistant" row in the
command palette. A click only fills the input field, nothing is sent automatically.

### WebSocket Integration (`hooks/useAssistantWebSocket.ts`)

- **Protocol**: STOMP over SockJS, URL `{origin}/ws?ticket={ticket}` — `beforeConnect` fetches the single-use ticket via `api.fetchWsTicket()` before every connect attempt (including every reconnect); the API key stays in the header
- **Reconnect delay**: 5000ms

Two STOMP subscriptions:

1. **Status updates** (per request): `/topic/assistant/{requestId}/status`
   - Phases: `RECEIVED` → `BUILDING_CONTEXT` → `THINKING` → `FETCHING_DATA` → `EXECUTING_TOOLS` → `COMPLETED` | `ERROR`
   - Terminal phases (`COMPLETED`, `ERROR`) are cleared after 500ms
   - Drives `StatusIndicator` (phase label + elapsed time)

2. **Push events** (per session): `/topic/assistant/{sessionId}/events`
   - `AssistantPushEvent`: `eventKind`, `title`, `detail`, `linkTo`

### Additional Components

The page context (`context.page`, ids) is derived from the URL by `parsePageContext(pathname)` in
`components/assistantPanelUtils.ts`. Message, Markdown, action buttons and the confirmation card live under
`components/assistant/`.

### External Triggers

| Source | Mechanism |
|--------|-----------|
| WorkflowDebug page | Diagnose button → `pendingContext` + new session + open panel |
| SimpleHome | asks itself (`useHomeAssistant`), answer above the instant hits; “Continue in chat” → `loadSession` + open panel |
| CommandPalette | "Ask the assistant" → `pendingInput` + open panel |
| Header SuggestionButton | Dropdown selection → `pendingInput` + open panel |
