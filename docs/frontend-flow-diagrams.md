# AutoPilot — Frontend Flow

Routing, State, Datenfluss, WebSockets und Cache-Invalidierung

## Routing — *App.tsx*

React Router mit Layout (Sidebar + Header) und 10 Hauptseiten.

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

## Datenfluss — *Client → API*

Fetches laufen ueber Vite-Proxy `/api` an das Backend; WebSocket unter `/ws`.

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

## Queries & Mutations — *Invalidierung*

| Ressource | Query Hook | Mutationen | Invalidiert |
|---|---|---|---|
| Projects | `useProjects` | create/update/delete/start/stop | projects, project detail |
| Workflows | `useWorkflows` | create/update/delete/execute | workflows, workflow detail, runs |
| Skills | `useSkills` | activate/deactivate | skills, workflow detail |
| Schedules | `useSchedules` | create/toggle/delete | schedules list |
| Webhooks | `useWebhooks` | create/toggle/delete | webhooks list |
| Settings | `useSystemHealth` | — | health |

## Zustand — *Zustand Stores*

### Client State

`useSidebarStore`: Collapse

`useThemeStore`: Light/Dark, Overrides

`useSettingsStore`: API-Key, Advanced Mode

`useI18nStore`: Locale

`useToastStore`: Toasts

### Persistenz

Sidebar, Theme, Settings, Locale → localStorage

Toasts sind flüchtig

## Live-Logs — *WebSocket /ws*

Ausführungs-Logs kommen via STOMP auf `/topic/executions/{runId}/logs`.

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
