<!-- Generated from docs/frontend-flow-diagrams.md and docs/i18n/frontend-flow-diagrams.en.json by scripts/docs-diagrams.py — do not edit by hand. -->

# MySuricates — Frontend Flow

Routing, state, data flow, WebSockets and cache invalidation

## Routing — *App.tsx*

`createBrowserRouter` with one layout (sidebar + header) around all pages. Pages of optional modules sit in `ModuleRoute` and, without their module, explain how to switch it on; several core pages switch to their own, simpler version in Simple Mode (`useSettingsStore`). Every page is `simple` (own version), `shared` (both modes) or `pro` (`utils/simpleRoutes.ts`); when Simple Mode lands on a Pro page, `ProPageNotice` says so below the header and offers the switch.

```mermaid
flowchart LR
    APP["App.tsx Router + Layout"] --> CORE
    APP --> MOD
    subgraph CORE["Core pages"]
        DASH["/ Dashboard<br/>/projects + /projects/:id<br/>/workflows + /workflows/:id"]
        RUN["/workflows/:id/run/:runId<br/>/runs (/runs/:runId redirects)<br/>/approvals"]
        SCH["/schedules + /calendar<br/>/todos, /cockpit"]
        GOAL["/goals overview<br/>/goals/:goalId with relation map"]
        ACT["/activity + /dlq<br/>/docs<br/>/settings"]
    end
    subgraph MOD["ModuleRoute (optional modules)"]
        SK["/skills, /webhooks, /integrations<br/>/chains + /chains/:id/run/:runId"]
        MORE["/wishes, /factory, /dialogs<br/>/debug, /reports, /insights<br/>/onboarding-profiles"]
    end
```

## Data Flow — *Client → API*

All hooks call the `api` client (`src/api/client.ts`): `fetch` on `/api/v1/...` with the `X-API-Key` from `useSettingsStore`. In development the Vite proxy forwards `/api` and `/ws` to the backend; otherwise the backend serves the built frontend itself (`automation.serve-frontend`).

```mermaid
sequenceDiagram
    participant UI as Component
    participant HOOK as useXxx (TanStack Query)
    participant API as api client (fetch /api/v1/...)
    participant CACHE as Query Cache

    UI->>HOOK: useQuery/useMutation
    HOOK->>CACHE: return cached data (if present)
    HOOK->>API: HTTP request with X-API-Key
    alt 429 Too Many Requests
        API-->>HOOK: error, path locked until Retry-After
    else ok
        API-->>HOOK: JSON DTO
        HOOK-->>CACHE: set + update
        CACHE-->>UI: re-render with fresh data
    end
```

## Queries & Mutations — *Invalidation*

A key such as `['workflows']` matches all queries below it as a prefix — detail, runs, versions.

| Resource | Query Hook | Mutations | Invalidates |
|---|---|---|---|
| Projects | `useProjects`, `useProject` | create/update/archive/restore, container start/stop | `projects` (list, detail, archive) |
| Workflows | `useWorkflows`, `useWorkflow` | create/update/delete/import/seal | `workflows` |
| Runs | `useWorkflowRuns`, `useAllRuns` | execute/pause/resume/cancel/retry/delete | `workflows` and `learning`, depending on the action also `runs` and `dlq` |
| Skills | `useSkills` | create/update/delete/import | `skills`; delete also `workflows` |
| Skill activation | `useWorkflow` | activate/deactivate | `workflows` |
| Schedules | `useSchedules` | create/update/toggle/delete | `schedules` (list, calendar, preview) |
| Webhooks | `useWebhooks` | create/toggle/delete, token and secret | `webhooks` |
| Goals | `useGoals`, `useGoal`, `useGoalRelations` | create/update/delete, assign and order TODOs, assessment, milestones, goal guard | `goals`; whatever touches TODOs also `todos`; the guard also `schedules` and `workflows` |
| System | `useSystemHealth`, `useSystemInfo` | — | none; health refetches every 30 s |

## State — *Zustand Stores*

### Client State

`useSidebarStore`: collapsed, open on mobile

`useThemeStore`: theme, last chosen light and dark themes, overrides

`useSettingsStore`: API key, Simple and Advanced Mode, sidebar order, hints, model defaults

`useI18nStore` (`src/i18n`): locale

`useToastStore`: toasts

`useAssistantStore`: assistant sessions, panel size

`useDashboardLayoutStore`, `useDashboardSyncStore`: dashboard views and their sync state

### Persistence

All stores except `useToastStore` → localStorage (Zustand `persist`)

The sidebar only stores "collapsed", the assistant only sessions and panel size

Toasts are transient

## Live Logs — *WebSocket /ws*

Execution logs arrive via STOMP on `/topic/executions/{runId}/logs`. A single-use ticket from `POST /api/v1/ws-ticket` opens the handshake — the API key never appears in the URL.

```mermaid
sequenceDiagram
    participant UI as WorkflowExecution Page
    participant WS as useExecutionWebSocket
    participant API as REST /api/v1
    participant ST as STOMP Broker (:9090)
    participant BE as ExecutionBroadcaster / block executors

    UI->>WS: useExecutionWebSocket(runId)
    WS->>API: POST /ws-ticket (with X-API-Key)
    API-->>WS: single-use ticket
    WS->>ST: CONNECT /ws?ticket=… (SockJS, WebSocket transport only)
    ST-->>WS: CONNECTED
    WS->>ST: SUBSCRIBE /topic/executions/{runId}/logs
    WS->>API: GET /executions/{runId}/live-log (replay buffered lines)
    BE->>ST: convertAndSend("/topic/executions/{runId}/logs", payload)
    ST-->>WS: messages (steps, status, approvals, human input, agentic, foreach, live_output)
    WS-->>UI: append logs, update status
    WS->>WS: on step and final status: reload the workflow's runs (invalidate)
```
