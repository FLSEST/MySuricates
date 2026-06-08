<!-- last-sync: 2026-04-14 from: docs/assistant-system.md -->

> ⚠️ **This translation may be outdated.** The German source (`docs/assistant-system.md`) was updated on 2026-04-19, after this translation was last synced (2026-04-14). Until it is re-translated, the German version is authoritative.

> *Last updated: April 14, 2026 — translated from German*

# Assistant System

The Assistant is a context-sensitive chat assistant available as a floating panel in the platform. It knows the current page, can create or explain workflows, and suggests context-sensitive actions.

## Overview

The Assistant system consists of three areas:

1. **Chat** — Freeform conversation via `POST /api/v1/assistant/chat`
2. **Sessions** — Persisted chat histories per page (V41 migration: `assistant_sessions`)
3. **Page Suggestions** — Pre-computed, page-specific action suggestions (V42 migration: `page_suggestions`)

## Backend Components

### AssistantService

- Main class for chat logic
- Prepares the context (current page, workflow data, etc.)
- Calls Claude CLI on the host (`HostClaudeService`)
- Default model: `claude-sonnet-4-6`
- Returns `AssistantChatResponse` (response text + optional actions)

### AssistantSessionService

- Persists chat histories in the `assistant_sessions` table (V41)
- Method `appendMessages(sessionId, request, response)` — appends user and assistant messages
- Sessions are maintained per `sessionId`; the session ID comes from the frontend

### PageSuggestionService

- Generates and caches context-sensitive suggestions per UI page (e.g. `"dashboard"`, `"workflows"`)
- Persisted in the `page_suggestions` table (V42)
- `getSuggestions(page)` — returns cached suggestions
- `refreshSuggestions(page)` — generates new suggestions with Claude and saves them

### AssistantSessionUpdatedEvent

- Spring Application Event that is fired after each chat call
- Carries the `page` value from the request context
- Can be used to update Page Suggestions in the background

## API Endpoints

### Chat

```
POST /api/v1/assistant/chat
```

Request body (`AssistantChatRequest`):
```json
{
  "message": "Explain the last workflow error to me",
  "sessionId": "optional-uuid",
  "context": {
    "page": "workflows",
    "workflowId": "abc-123"
  }
}
```

Response (`AssistantChatResponse`):
```json
{
  "reply": "The workflow failed because...",
  "actions": []
}
```

- `sessionId` is optional. If provided, the chat history is persisted.
- `context.page` controls which platform data is injected into the system prompt.

### Page Suggestions

```
GET  /api/v1/assistant/suggestions?page=workflows
POST /api/v1/assistant/suggestions/refresh?page=workflows
```

- `GET` returns cached suggestions as `List<String>` (immediately, no AI call)
- `POST /refresh` generates new suggestions via Claude and returns them (blocking)

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

Caches suggestions per UI page.

| Column | Type | Description |
|---|---|---|
| `id` | TEXT PK | UUID |
| `page` | TEXT UNIQUE | UI page (e.g. `"dashboard"`) |
| `suggestions` | TEXT | JSON array of suggestions |
| `generated_at` | TEXT | Timestamp of last generation |

## Controller Responsibilities

Both controllers are registered under the base path `/api/v1/assistant`:

| Controller | Endpoints | Description |
|---|---|---|
| `AssistantController` | `POST /chat` | Process chat request, persist session |
| `AssistantController` | `GET /sessions` | Last 50 sessions with preview (without messages) |
| `AssistantController` | `GET /sessions/{id}` | Single session with all messages |
| `AssistantController` | `DELETE /sessions/{id}` | Delete session |
| `PageSuggestionController` | `GET /suggestions`, `POST /suggestions/refresh` | Page-specific suggestions |

## Tools (Server-Side Actions)

The assistant can execute actions directly via `<tool type="...">JSON</tool>` tags. The tags are parsed by `AssistantToolExecutor` and executed server-side before the response is sent to the user.

### Available Tools

| Tool | Required Fields | Description |
|------|-----------------|-------------|
| `create_todo` | `title` | Create a todo (optional: description, priority, tags, projectId) |
| `update_todo_status` | `id`, `status` | Change todo status (OPEN/IN_PROGRESS/DONE/BACKLOG) |
| `create_workflow` | `projectId`, `name` | Create a workflow (optional: blocks, instructions, model, defaultContext) |
| `update_workflow` | `workflowId` | Update workflow fields (only provided fields are changed) |
| `delete_workflow` | `workflowId` | Soft-delete a workflow (user confirmation required per prompt) |
| `start_workflow` | `workflowId` | Start a workflow (optional: context for input variables) |
| `cancel_run` | `runId` | Cancel a running run (user confirmation required per prompt) |
| `create_chain` | `name`, `entries` | Create a workflow chain |

### JSON Sanitization

`AssistantToolExecutor.sanitizeJson()` normalizes fullwidth Unicode characters (e.g. `，` → `,`) before JSON parsing, as Claude occasionally generates such characters.

### Safety Rules

Destructive tools (`delete_workflow`, `cancel_run`) require explicit user confirmation before execution according to the system prompt.

## Fetch Mechanism (Dynamic Loading)

The assistant can load additional data via `<fetch type="..." id="..."/>` tags. `AssistantFetchService` resolves these and returns the data as supplemental context.

- **Max. 2 fetch rounds** per chat request (configured in `AssistantService`)
- Truncation markers show size info: `[Output truncated: 8000 of 23456 characters]`
- Available fetch types: `run_detail`, `step_output`, `workflow_detail`, `workflow_runs`, `wish_detail`, `docs`, `pool_workflows`, `pool_workflow`, `project_context`

## Context Injection

`AssistantContextBuilder` injects different platform data into the system prompt depending on `context.page`:

- **`workflows`** — List of current workflows for the project
- **`runs`** — Status and logs of recent runs
- **`todos`** — Open TODOs for the project
- Other pages: general platform context

Additionally on **all pages**:
- **Last 5 sessions** — First user message (150 characters) + first assistant response (100 characters) per session
- **Workflow Memory** — Up to 8 active memories (300 characters per memory)

## Model Selection

- Default: `claude-sonnet-4-6`
- Haiku (`claude-haiku-4-5`) is too imprecise for assistant tasks
- Opus (`claude-opus-4-6`) is too slow for interactive chats
- The model can be overridden via `workflow.model` or system configuration

## Frontend Integration

The assistant is implemented as a floating panel (`AssistantPanel.tsx`):
- Global overlay above the layout, independent of the current page
- Sends the `page` context from the current route
- Displays Page Suggestions as quick actions (clickable → sent as user message)
- Persists `sessionId` in the frontend state for coherent conversations
- Conversation history is loaded from the backend (`GET /sessions`), merged with localStorage (localStorage takes precedence for the same ID)
- When clicking on a backend-only session, the full history is loaded on-demand and hydrated into the state store
- WebSocket integration for live-streaming of the response (if implemented)
