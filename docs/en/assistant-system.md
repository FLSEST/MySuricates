> *Last updated: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

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
- Sessions are managed per `sessionId`; the session ID comes from the frontend

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
| `messages` | TEXT | JSON array of chat messages |
| `created_at` | TEXT | Creation timestamp |
| `updated_at` | TEXT | Last update |

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
| `PageSuggestionController` | `GET /suggestions`, `POST /suggestions/refresh` | Page-specific suggestions |

## Context Injection

`AssistantService` injects different platform data into the system prompt depending on `context.page`:

- **`workflows`** — List of current workflows for the project
- **`runs`** — Status and logs of recent runs
- **`todos`** — Open TODOs for the project
- Other pages: general platform context

## Model Selection

- Default: `claude-sonnet-4-6`
- Haiku (`claude-haiku-4-5`) is too imprecise for assistant tasks
- Opus (`claude-opus-4-6`) is too slow for interactive chats
- The model can be overridden via `workflow.model` or system configuration

## Frontend Integration

The assistant is implemented as a floating panel (`AssistantPanel.tsx`):
- Global overlay above the layout, independent of the current page
- Sends the `page` context from the current route
- Displays Page Suggestions as quick actions (clickable — sent as a user message)
- Persists `sessionId` in frontend state for continuous conversations
- WebSocket integration for live streaming of the response (if implemented)
