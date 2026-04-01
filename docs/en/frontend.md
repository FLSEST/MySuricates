> *Last updated: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

# Frontend

## Routing

| Path | Page | Description |
|---|---|---|
| `/` | Dashboard | Statistics, charts, recent runs |
| `/projects` | Projects | Project list, creation |
| `/projects/:id` | ProjectDetail | Project details, container, workflows |
| `/wishes` | Wishes | Wish system, iterative fulfillment |
| `/workflows` | Workflows | Workflow list, creation with templates |
| `/workflows/:id` | WorkflowDetail | Block editor, skills, runs, memory |
| `/workflows/:id/run/:runId` | WorkflowExecution | Live execution, logs, steps |
| `/factory` | WorkflowFactory | Workflow generator from topics |
| `/dialogs` | DialogImport | Import Claude sessions as workflows |
| `/chains` | Chains | Workflow chains, split-panel layout |
| `/chains/:id/run/:runId` | ChainExecution | Live chain execution, entry status |
| `/runs` | Runs | All workflow runs (global list) |
| `/skills` | Skills | Skill catalog with capabilities |
| `/schedules` | Schedules | Cron-based schedules |
| `/webhooks` | Webhooks | Webhook trigger management |
| `/todos` | Todos | TODO list, filters, status management |
| `/activity` | ActivityLog | System activity log with filters |
| `/docs` | Docs | Markdown documentation (split layout) |
| `/insights` | Insights | Platform insights and analytics |
| `/settings` | Settings | Language, theme, API key, Tailscale, staging, testing |

All routes are defined in `src/App.tsx` and wrapped by a `<Layout>` component (sidebar + header).

### Simple Mode

`useSettingsStore().simpleMode` switches four pages between normal and simple variants:

| Route | Normal | Simple |
|---|---|---|
| `/` | `Dashboard` | `SimpleDashboard` |
| `/workflows` | `Workflows` | `SimpleWorkflows` |
| `/workflows/:id` | `WorkflowDetail` | `SimpleWorkflowDetail` |
| `/workflows/:id/run/:runId` | `WorkflowExecution` | `SimpleWorkflowExecution` |

The switching is handled by route wrapper components (e.g. `DashboardRoute`, `WorkflowsRoute`) in `App.tsx`, which render the corresponding variant based on the `simpleMode` flag.

## Pages

### Dashboard (`/`)

- 4 stat cards: Active projects, workflows, runs today, success rate
- 2 Recharts diagrams: Runs over time (AreaChart), success rate (AreaChart)
- List of recent runs (clickable → execution page)
- Top workflows by execution count

### Projects (`/projects`, `/projects/:id`)

- Search filter by name
- Grid layout (1/2/3 columns depending on screen width)
- Project cards with type icon and color coding
- Start/stop buttons for Docker containers
- Detail page: Project info, container status, associated workflows
- Modals: Create, edit, delete confirmation

### Workflows (`/workflows`, `/workflows/:id`)

- 2-step creation: Template selection → form (project, name, description)
- 44 built-in templates (from DB, via `WorkflowTemplateSeeder`) plus custom templates
- Detail page: 2/3-column block editor (drag & drop), 1/3 sidebar (skills, runs, memory, info)
- Block editor with `@dnd-kit` for drag-and-drop sorting
- Modals: Add block (type + JSON config via Monaco Editor), activate skill

### WorkflowExecution (`/workflows/:id/run/:runId`)

- Live status badge (PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED)
- Split view: Steps panel (step indicators) + live logs (WebSocket)
- Pause/resume/cancel buttons during execution
- Star rating (1-5) after completion
- Error panel for failed runs

### Skills (`/skills`)

- Search filter + category filter
- Grid layout with skill cards
- Detail slide-over: Capabilities, async badges, AI context preview
- Category icons and colors

### Schedules (`/schedules`)

- Table: Workflow, cron expression, next/last execution, status toggle
- Create modal: Workflow selection + cron expression + description

### Webhooks (`/webhooks`)

- Table: Workflow, URL (copyable), token (masked + copyable), trigger count, status
- CopyButton component with success feedback
- Create modal: Workflow selection + description

### Chains (`/chains`, `/chains/:id/run/:runId`)

- Split-panel layout: Chain list on the left, detail/execution on the right
- Pipeline visualization of chain entries
- Create/edit modal with workflow search and sorting
- ChainExecution: Live polling for entry status, navigation to individual workflow runs

### Todos (`/todos`)

- List of all TODOs with filters by status, priority, project, source, tag
- Status workflow: OPEN → IN_PROGRESS → DONE / CANCELLED
- Automatically recognizes AI-created TODOs (source: AI, sourceAgent: claude-code)
- Badge display in the sidebar for open TODOs

### ActivityLog (`/activity`)

- Paginated log table with filters by level, category, workflow
- Categories: WORKFLOW, SKILL, SYSTEM, SECURITY, and others

### Wishes (`/wishes`)

- Enter natural-language wishes and have them fulfilled asynchronously
- Iteration display, provide feedback, cancel or crystallize a wish

### WorkflowFactory (`/factory`)

- Generate a workflow from a topic category
- Full pipeline: Generate → test instance → verify → promote
- Topic category selection

### DialogImport (`/dialogs`)

- Scan Claude sessions and import them as workflows
- Session selection, preview, import with project assignment

### Docs (`/docs`)

- Split layout: Document list on the left, Markdown rendering on the right
- Documents are loaded via `GET /api/v1/docs` from the `docs/` directory
- Markdown rendering with `react-markdown` + `remark-gfm` + `@tailwindcss/typography`

### Insights (`/insights`)

- Platform analytics and statistics
- Visualization of workflow performance and usage patterns

### WorkflowDetail (`/workflows/:id`) — Extensions

- **Memory tab:** Display, activate, delete, and re-extract workflow memory entries
- **Instructions tab:** Edit workflow-specific agent instructions in the Monaco editor
- **Gate status:** Display the latest gate status in the run history

### Settings (`/settings`)

- General: Language (EN/DE), Advanced Mode
- Appearance: Theme toggle (Light/Dark), color selection
- Security: API key input (masked)
- Remote Access: Tailscale status + enable/disable
- **Stable:** Version comparison Dev/Stable, sync preview, sync definitions, start/stop/promote
- **Testing:** Test instance status, start/stop/reset, sync preview, sync definitions
- System: Health status, system info, Docker status

## Components

### `AssistantPanel.tsx` (`src/components/AssistantPanel.tsx`)

- Floating chat panel, displayed globally above the layout
- Context-sensitive chat with page suggestions
- Persisted sessions, WebSocket integration
- See [Assistant System](assistant-system.md) for details

### `src/components/settings/`

- Settings sub-components for the modular settings page
- Separate components for General, Appearance, Security, Stable, Testing, System

### Shared Components (`src/components/shared/`)

| Component | Description |
|---|---|
| `StatusBadge` | Maps status strings to badge variants |
| `LogViewer` | Log display with level colors and auto-scroll |
| `MonacoEditor` | JSON editor (Monaco, dark theme) |
| `ErrorState` | Error message with icon |
| `EmptyState` | Empty state with description |

## UI Component Library

All components in `src/components/ui/`:

| Component | Props | Description |
|---|---|---|
| `Button` | `variant` (primary/secondary/ghost/danger), `size` (sm/md/lg), `loading`, `icon` | Standard button with loading state |
| `Card` | `padding`, `className` | Container with border and background |
| `CardHeader` | `title`, `subtitle`, `action` | Card header with optional action |
| `Modal` | `open`, `onClose`, `title`, `footer`, `size` (sm/md/lg) | Overlay modal with backdrop blur |
| `Badge` | `variant` (default/success/warning/danger/accent), `dot` | Status badge with optional dot |
| `Input` | `label`, `error`, `hint`, `icon` + HTML attributes | Form input with validation |
| `Select` | `label`, `options`, `error` | Dropdown select |
| `Toggle` | `enabled`, `onChange`, `label`, `disabled` | Switch element |
| `Spinner` | `size` (sm/md/lg), `text` | Loading indicator |
| `Table<T>` | `columns`, `data`, `keyExtractor`, `onRowClick`, `emptyText` | Generic table |
| `ToastContainer` | (global) | Toast notifications (max 5) |

## Design System

### CSS Variables

The theme is controlled via CSS custom properties (`src/index.css`, `src/theme/tokens.ts`):

**Dark Theme (Default):**

| Variable | Value | Usage |
|---|---|---|
| `--color-surface` | `#0f172a` | Page background |
| `--color-surface-raised` | `#1e293b` | Cards, elevated surfaces |
| `--color-surface-overlay` | `#1e293b` | Modal background |
| `--color-border` | `#334155` | Standard border |
| `--color-border-strong` | `#475569` | Emphasized border |
| `--color-text` | `#f1f5f9` | Primary text |
| `--color-text-secondary` | `#94a3b8` | Secondary text |
| `--color-text-tertiary` | `#64748b` | Tertiary text |
| `--color-accent` | `#06b6d4` | Accent color (cyan) |
| `--color-accent-hover` | `#22d3ee` | Accent hover |
| `--color-accent-subtle` | `#083344` | Accent background |
| `--color-success` | `#34d399` | Success (emerald) |
| `--color-warning` | `#fbbf24` | Warning (amber) |
| `--color-danger` | `#f87171` | Error (red) |
| `--color-sidebar` | `#020617` | Sidebar background |

**Light Theme:** Inverted variants with white background and dark text.

### Tailwind Configuration

- **Dark Mode:** `class`-based (toggled via Zustand store)
- **Fonts:**
  - Sans: Plus Jakarta Sans (system-UI fallback)
  - Mono: JetBrains Mono (Fira Code fallback)
- **Custom Animations:** `fade-in`, `slide-in`, `slide-up`, `pulse-subtle`, `slide-in-right`

### CSS Utility Classes (`index.css`)

| Class | Description |
|---|---|
| `.glass` | Glassmorphism effect (blur + semi-transparent) |
| `.focus-ring` | Focus state with accent ring |
| `.input-base` | Standard input styling |
| `.btn-base` | Standard button styling |
| `.text-balance` | Balanced text wrapping |
| `.animate-stagger` | Staggered animation (50ms per child) |

## State Management

### Zustand Stores (`src/stores/`)

| Store | State | Persistence |
|---|---|---|
| `useSidebarStore` | `collapsed: boolean`, `toggle()`, `setCollapsed()` | `localStorage: sidebar-state` |
| `useThemeStore` | `mode: 'light'\|'dark'`, `overrides`, `toggleMode()`, `apply()` | `localStorage: theme-settings` |
| `useSettingsStore` | `apiKey: string`, `advancedMode: boolean`, `simpleMode: boolean` | `localStorage: app-settings` |
| `useI18nStore` | `locale: 'en'\|'de'`, `setLocale()` | `localStorage: i18n-locale` |
| `useToastStore` | `toasts[]`, `addToast()`, `removeToast()` | None (ephemeral) |

### TanStack Query (Server State)

All API data is managed via React Query:

- **Caching:** Automatic with query keys
- **Invalidation:** Related queries are invalidated after mutations
- **Optimistic Updates:** For drag-and-drop block sorting
- **Auto-Refetch:** Health check every 30 seconds

## API Hooks (`src/hooks/`)

### `useProjects.ts`

| Hook | Type | Description |
|---|---|---|
| `useProjects()` | Query | All projects |
| `useProject(id)` | Query | Single project |
| `useCreateProject()` | Mutation | Create project |
| `useUpdateProject()` | Mutation | Update project |
| `useDeleteProject()` | Mutation | Delete project |
| `useStartContainer(id)` | Mutation | Start container |
| `useStopContainer(id)` | Mutation | Stop container |

### `useWorkflows.ts`

| Hook | Type | Description |
|---|---|---|
| `useWorkflows()` | Query | All workflows |
| `useWorkflow(id)` | Query | Single workflow |
| `useWorkflowRuns(workflowId)` | Query | All runs of a workflow |
| `useCreateWorkflow()` | Mutation | Create workflow |
| `useUpdateWorkflow()` | Mutation | Update workflow |
| `useDeleteWorkflow()` | Mutation | Delete workflow |
| `useExecuteWorkflow()` | Mutation | Execute workflow |
| `usePauseExecution(runId)` | Mutation | Pause execution |
| `useResumeExecution(runId)` | Mutation | Resume execution |
| `useCancelExecution(runId)` | Mutation | Cancel execution |

### `useSkills.ts`

| Hook | Type | Description |
|---|---|---|
| `useSkills()` | Query | All skills |
| `useSkill(id)` | Query | Single skill |
| `useSkillCapabilities(skillId)` | Query | Capabilities of a skill |
| `useActivateSkills()` | Mutation | Activate skills for workflow |
| `useDeactivateSkill()` | Mutation | Deactivate skill |

### `useSchedules.ts`

| Hook | Type | Description |
|---|---|---|
| `useSchedules()` | Query | All schedules |
| `useCreateSchedule()` | Mutation | Create schedule |
| `useToggleSchedule()` | Mutation | Enable/disable schedule |
| `useDeleteSchedule()` | Mutation | Delete schedule |

### `useWebhooks.ts`

| Hook | Type | Description |
|---|---|---|
| `useWebhooks()` | Query | All webhooks |
| `useCreateWebhook()` | Mutation | Create webhook |
| `useToggleWebhook()` | Mutation | Enable/disable webhook |
| `useDeleteWebhook()` | Mutation | Delete webhook |

### `useSystem.ts`

| Hook | Type | Description |
|---|---|---|
| `useSystemHealth()` | Query (30s) | Health check |
| `useSystemInfo()` | Query | System info |
| `useRemoteAccessStatus()` | Query | Tailscale status |
| `useEnableRemoteAccess()` | Mutation | Enable Tailscale |
| `useDisableRemoteAccess()` | Mutation | Disable Tailscale |

### `useChains.ts`

| Hook | Type | Description |
|---|---|---|
| `useChains()` | Query | All chains |
| `useChain(id)` | Query | Single chain |
| `useChainRuns(chainId)` | Query | Runs of a chain |
| `useChainRun(chainId, runId)` | Query | Single chain run |
| `useAllChainRuns(page, size, status)` | Query | All chain runs (paginated) |
| `useCreateChain()` | Mutation | Create chain |
| `useUpdateChain()` | Mutation | Update chain |
| `useDeleteChain()` | Mutation | Delete chain |
| `useExecuteChain()` | Mutation | Execute chain |
| `useCancelChainRun()` | Mutation | Cancel chain run |

### `useTodos.ts`

| Hook | Type | Description |
|---|---|---|
| `useTodos(filters)` | Query | TODOs with filters |
| `useTodo(id)` | Query | Single TODO |
| `useTodoCount()` | Query | TODO count by status |
| `useCreateTodo()` | Mutation | Create TODO |
| `useUpdateTodo()` | Mutation | Update TODO |
| `useUpdateTodoStatus()` | Mutation | Change status |
| `useDeleteTodo()` | Mutation | Delete TODO |

### `useActivityLog.ts`

| Hook | Type | Description |
|---|---|---|
| `useActivityLog(page, size, filters)` | Query | Activity log entries (paginated) |

### `useWorkflowMemory.ts`

| Hook | Type | Description |
|---|---|---|
| `useWorkflowMemory(workflowId)` | Query | Memory entries of a workflow |
| `useExtractMemory()` | Mutation | Extract knowledge from runs |
| `useActivateMemory()` | Mutation | Activate memory entry |
| `useUpdateMemory()` | Mutation | Edit memory entry |
| `useDeleteMemory()` | Mutation | Delete memory entry |

### `useStaging.ts`

| Hook | Type | Description |
|---|---|---|
| `useStagingStatus()` | Query | Stable status + version |
| `useStagingStart()` | Mutation | Start Stable |
| `useStagingStop()` | Mutation | Stop Stable |
| `useStagingPromote()` | Mutation | Execute promote |
| `useSyncPreview()` | Query | Sync preview |
| `useSyncDefinitions()` | Mutation | Synchronize definitions |

### `useTesting.ts`

| Hook | Type | Description |
|---|---|---|
| `useTestingStatus()` | Query | Test instance status |
| `useTestingStart()` | Mutation | Start test instance |
| `useTestingStop()` | Mutation | Stop test instance |
| `useTestingReset()` | Mutation | Reset test database |
| `useTestingSyncDefinitions()` | Mutation | Synchronize definitions to test |

### `useWishes.ts`

| Hook | Type | Description |
|---|---|---|
| `useWishes()` | Query | All wishes |
| `useWish(id)` | Query | Single wish |
| `useCreateWish()` | Mutation | Create wish |
| `useCancelWish()` | Mutation | Cancel wish |
| `useRespondToWish()` | Mutation | Provide feedback |
| `useCrystallizeWish()` | Mutation | Crystallize wish |
| `useDeleteWish()` | Mutation | Delete wish |

### `useDocs.ts`

| Hook | Type | Description |
|---|---|---|
| `useDocsList()` | Query | Available documents |
| `useDoc(name)` | Query | Content of a document |

### `usePool.ts`

| Hook | Type | Description |
|---|---|---|
| `usePool()` | Query | Pool workflows |
| `useInstallPoolWorkflow()` | Mutation | Install workflow from pool |

### `useAssistant.ts`

| Hook | Type | Description |
|---|---|---|
| `useAssistantChat()` | Mutation | Send chat message to the assistant |
| `useAssistantSuggestions(page)` | Query | Load page-specific suggestions |
| `useRefreshSuggestions()` | Mutation | Regenerate suggestions |

### `useChainTemplates.ts`

| Hook | Type | Description |
|---|---|---|
| `useChainTemplates()` | Query | Available chain templates |

### `useCompletionTriggers.ts`

| Hook | Type | Description |
|---|---|---|
| `useCompletionTriggers(workflowId)` | Query | CompletionTriggers of a workflow |
| `useCreateCompletionTrigger()` | Mutation | Create trigger |
| `useDeleteCompletionTrigger()` | Mutation | Delete trigger |

### `useTodoTriggers.ts`

| Hook | Type | Description |
|---|---|---|
| `useTodoTriggers()` | Query | All TodoTriggers |
| `useCreateTodoTrigger()` | Mutation | Create TodoTrigger |
| `useToggleTodoTrigger()` | Mutation | Enable/disable TodoTrigger |
| `useDeleteTodoTrigger()` | Mutation | Delete TodoTrigger |

### `useDialog.ts`

| Hook | Type | Description |
|---|---|---|
| `useDialogSessions()` | Query | Available Claude sessions |
| `useImportDialog()` | Mutation | Import session as workflow |

### `useInsights.ts`

| Hook | Type | Description |
|---|---|---|
| `useInsights()` | Query | Platform insights and statistics |

### `useNotifications.ts`

| Hook | Type | Description |
|---|---|---|
| `useNotifications()` | Query | Load notifications |

### `usePersistedState.ts`

Generic hook for persisted state via `localStorage`.

### `useWorkflowFactory.ts`

| Hook | Type | Description |
|---|---|---|
| `useGenerateWorkflow()` | Mutation | Generate workflow from topic |
| `useFactoryStatus()` | Query | Status of the factory pipeline |

### `useWebSocket.ts`

```typescript
useWebSocket(runId: string | null) → {
  logs: Array<{ timestamp, level, message }>,
  connected: boolean,
  clearLogs: () => void
}
```

Connects to the backend via SockJS/STOMP and receives live logs.

### `useToast.ts`

```typescript
useToast() → {
  success(message), error(message), info(message), warning(message)
}
```

## Internationalization (i18n)

- **Languages:** German (`de`), English (`en`)
- **Files:** `src/i18n/de.ts`, `src/i18n/en.ts`
- **Hook:** `useT()` → returns the current translation object
- **Store:** `useI18nStore()` → `locale`, `setLocale()`
- **Toggle:** Button in the header

### Translation Structure

```
common (24 keys) ─── General labels (Create, Delete, Search, ...)
nav (7 keys) ─────── Navigation (Dashboard, Projects, ...)
dashboard (8 keys) ─ Dashboard texts
projects (11 keys) ─ Projects + nested types & statuses
workflows (18 keys)  Workflows + block types & execution
skills (10 keys) ─── Skills + categories
schedules (7 keys) ─ Schedules
webhooks (7 keys) ── Webhooks
settings (16 keys) ─ Settings
toast (26 keys) ──── Toast messages
templates (13 keys)  Workflow templates
charts (5 keys) ──── Chart labels
```

## PWA Configuration

Configured via `vite-plugin-pwa` in `vite.config.ts`:

- **Name:** MySuricates
- **Short Name:** AutoPilot
- **Display:** Standalone
- **Theme Color:** `#0f172a`
- **Service Worker:** Auto-update
- **Icon:** `/icons/icon.svg` (SVG, maskable)

## API Client (`src/api/client.ts`)

```typescript
class ApiClient {
  BASE_URL = '/api/v1'

  getHeaders() → { Content-Type: application/json, X-API-Key: ... }

  get<T>(path) → Promise<T>
  post<T>(path, body?) → Promise<T>
  put<T>(path, body?) → Promise<T>
  delete<T>(path) → Promise<T>
}
```

Errors are thrown as `ApiError` with `{ error, timestamp, fieldErrors? }`.

The API key is read from the `settingsStore` and automatically attached to all requests.

## Proxy Configuration (Vite)

In `vite.config.ts`:

| Path | Target | Description |
|---|---|---|
| `/api/*` | `http://localhost:9090` | REST API |
| `/ws/*` | `ws://localhost:9090` | WebSocket (STOMP/SockJS) |

The dev server runs on port 3000 and proxies all API requests to the backend on port 9090.
