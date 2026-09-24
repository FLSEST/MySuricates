<!-- Generated from docs/frontend-flow-diagrams.md and docs/i18n/frontend-flow-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# MySuricates — Frontend Flow

Routing, state, data flow, WebSockets and cache invalidation

## Routing — *App.tsx*

React Router with a layout (sidebar + header) and 10 main pages.

```mermaid
flowchart LR
    APP["App.tsx Router"] --> DASH["/ Dashboard"]
    APP --> PROJ["/projects + /projects/:id"]
    APP --> WF["/workflows + /workflows/:id"]
    APP --> RUN["/workflows/:id/run/:runId"]
    APP --> SK["/skills"]
    APP --> SCH["/schedules"]
    APP --> WH["/webhooks"]
    APP --> ST["/settings"]
```

## Data Flow — *Client → API*

Fetches go through the Vite proxy `/api` to the backend; the WebSocket lives at `/ws`.

```mermaid
sequenceDiagram
    participant UI as Component
    participant HOOK as useXxx (React Query)
    participant API as fetch /api/v1/...
    participant CACHE as Query Cache

    UI->>HOOK: useQuery/useMutation
    HOOK->>CACHE: return cached data (if present)
    HOOK->>API: HTTP request
    API-->>HOOK: JSON DTO
    HOOK-->>CACHE: set + update
    CACHE-->>UI: re-render with fresh data
```

## Queries & Mutations — *Invalidation*

| Resource | Query Hook | Mutations | Invalidates |
|---|---|---|---|
| Projects | `useProjects` | create/update/delete/start/stop | projects, project detail |
| Workflows | `useWorkflows` | create/update/delete/execute | workflows, workflow detail, runs |
| Skills | `useSkills` | activate/deactivate | skills, workflow detail |
| Schedules | `useSchedules` | create/toggle/delete | schedules list |
| Webhooks | `useWebhooks` | create/toggle/delete | webhooks list |
| Settings | `useSystemHealth` | — | health |

## State — *Zustand Stores*

### Client State

`useSidebarStore`: collapse

`useThemeStore`: light/dark, overrides

`useSettingsStore`: API key, advanced mode

`useI18nStore`: locale

`useToastStore`: toasts

### Persistence

Sidebar, theme, settings, locale → localStorage

Toasts are transient

## Live Logs — *WebSocket /ws*

Execution logs arrive via STOMP on `/topic/executions/{runId}/logs`.

```mermaid
sequenceDiagram
    participant UI as WorkflowExecution Page
    participant WS as useExecutionWebSocket
    participant ST as STOMP Broker (:9090)
    participant BE as WorkflowExecutor

    UI->>WS: subscribe(runId)
    WS->>ST: CONNECT /ws
    ST-->>WS: CONNECTED
    ST-->>WS: messages (step logs, status, approvals)
    WS-->>UI: append logs, update status
    BE->>ST: convertAndSend("/topic/executions/{runId}/logs", payload)
```
