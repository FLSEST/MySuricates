# Frontend

## Routing

| Path | Page | Description |
|---|---|---|
| `/` | Dashboard | Statistics, charts, recent runs |
| `/projects` | Projects | Project list, creation |
| `/projects/:id` | ProjectDetail | Project details, containers, workflows |
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
| `/dlq` | DeadLetterQueue | Dead letter queue for failed events |
| `/debug` | WorkflowDebug | Analyze workflows with failed runs |
| `/docs` | Docs | Markdown documentation (split layout) |
| `/reports` | Reports | View generated reports |
| `/insights` | Insights | Platform insights and analytics |
| `/integrations` | Integrations | Manage integration providers |
| `/runs/:runId` | RunRedirect | Redirect → `/workflows/:workflowId/run/:runId` (API lookup) |
| `/calendar` | CalendarPage | Aggregated calendar (schedules, runs, integrations) |
| `/cockpit` | ProjectCockpit | Project cockpit with todo overview |
| `/onboarding-profiles` | OnboardingProfiles | Manage onboarding profiles |
| `/settings` | Settings | Language, theme, API key, Tailscale, staging, testing |

All routes are defined in `src/App.tsx` and wrapped by a `<Layout>` wrapper (sidebar + header).

### Simple Mode

Simple Mode is an operating surface for newcomers who installed MySuricates themselves: start workflows, answer their questions, read results. Building and configuring happens in Pro. Wherever a path leads there, the hand-off is visible, never a silent jump.

`useSettingsStore().simpleMode` switches nine pages between Pro and Simple variants:

| Route | Pro | Simple |
|---|---|---|
| `/` | `Dashboard` | `SimpleHome` |
| `/projects` | `Projects` | `SimpleProjects` |
| `/workflows` | `Workflows` | `SimpleWorkflows` |
| `/workflows/:id` | `WorkflowDetail` | `SimpleWorkflowDetail` |
| `/workflows/:id/run/:runId` | `WorkflowExecution` | `SimpleWorkflowExecution` |
| `/runs` | `Runs` | `SimpleRuns` |
| `/schedules` | `Schedules` | `SimpleSchedules` |
| `/todos` | `Todos` | `SimpleTodos` |
| `/activity` | `ActivityLog` | `SimpleActivity` |

The switching is handled by route wrapper components (e.g. `DashboardRoute`, `WorkflowsRoute`, `RunsRoute`) in `App.tsx`, which render the corresponding variant based on the `simpleMode` flag.

- **Page kinds:** `simpleRouteKind(pathname)` (`utils/simpleRoutes.ts`) assigns every page: `simple` has a version of its own (the nine above, plus `/runs/:runId`), `shared` works unchanged in both modes (`/approvals`, `/settings`, `/docs`), everything else is `pro`. When Simple Mode lands on a Pro page, `ProPageNotice` says so below the header and offers “Switch to Pro” or “Back to Simple”. The Simple sidebar shows only the `simple` pages under their Simple titles, no “Edit menu”, and keeps a mode switch when collapsed.
- **Hand-off to Pro:** `ProHandoff` (reason plus button) and `ProBadge` (a “Pro” pill) in `components/simple/ProHandoff.tsx` mark what leads to Pro: changing the steps, the Pro tiles on the home page, building while creating, hidden settings, investigating a failure. `ProHandoff` switches the mode through `useOpenInPro(to?)` and optionally opens a target. Entries with a `ProBadge` (Pro tiles, project cards) open the Pro page without switching modes; there `ProPageNotice` offers the switch or the way back.
- **Result first:** While a run is going, the Simple run page shows a progress list, afterwards `RunResultCard` or `RunFailureCard`; the steps sit collapsed under “Details”. `utils/runResult.ts` reads everything from the run, without another request: the report from the `dashboard_widget` contract (from the `dashboard-report` block or a marker, checked like in the workflow result widget; invalid → a notice, never raw text), the gate as a banner, the last readable output (newest completed attempt per block, without approval, input, TODO, report and compression blocks, marker lines removed, Markdown without images), absolute file paths from the output (at most 10) and the number of TODOs created. `RunFailureCard` names reason and step in plain text, has exactly one button to run again and “Investigate in Pro mode”. `SimpleWorkflowDetail` shows the latest result of the newest successful run.
- **Starting:** `useSimpleRun` (`components/simple`) starts right away without inputs, otherwise `ExecuteDialog` asks with readable field names; then the run page opens. “Try again” in `SimpleRuns` uses `/executions/{id}/retry` and keeps the context.
- **Creating:** `TemplateSelector variant="simple"` (via `WorkflowCreateFlow`) starts at the templates, the pool stays; blueprints and “Blank Workflow” need the editor and are replaced by a `ProHandoff` row. Projects are created with the compact form (`ProjectFormFields compact`: name and folder, type `PERSONAL`). `/workflows?new=1` opens the create dialog.
- **Schedules:** `<CronPicker simple>` without “Advanced” and the cron line. The list shows `describeCron` (`utils/cronSchedule.ts`: “Daily at 09:00”, “Mon, Wed at 08:30” …); anything it cannot describe is “Custom timing” and carries the Pro badge.
- **Home page (`SimpleHome`):** With the assistant module the input is the assistant, without it (preset `core`) a search over one's own workflows (`WorkflowSearch`, `utils/matchWorkflows.ts`) with start chips. On first start an offer sits in place instead of a tour: without a project `FirstProjectActions` (example project first), with a project but no workflow “Start from a template”. `SimpleOverview` first says how many runs are waiting for an answer (→ `/approvals`).
- **Hints:** `simple-home` (what Simple is for), `simple-result` (run page), `simple-create` (templates while creating).
- **Mode choice:** `ModeChoiceCard` sits on the Pro dashboard next to “Getting started” while there is no project and `modeChoiceMade` is not set: “Use Simple” and “Use Pro” as equals, one sentence each. The card, the sidebar switches and the switch in the settings set `modeChoiceMade` (`chooseMode`). The default stays Pro; existing installations have projects and never see the card.

## Pages

### Dashboard (`/`)

The page renders the widgets from the registry `components/dashboard/widgetRegistry.ts`: stats, charts, todos, recent runs and quick actions.

- **Widget = registry entry** `{ id, label, description, category, size, module?, multiple?, catalogOnly?, configForm?, component }` (type in `widgetDefinition.ts`). The component loads its own data via query hooks and receives `{ instanceId, config }` as props. Prop-based cards that Insights uses with its own time range get a wrapper (`DashboardDataWidgets.tsx`, `InsightsWidgets.tsx`). The Insights widgets (quality gates, failure analysis, cost by workflow, block types, trigger distribution, cost and duration over time, tokens) are `React.lazy` entries (`insightsWidgetDefs.ts`, one shared chunk). A widget that renders nothing drops out of the grid (`empty:hidden`).
- **AI providers:** above the widgets, `AiProviderStartCard` (also on the Simple home page) shows whether the AI CLIs are ready — while there is no project or the default provider is not ready, with the command for whatever is missing. In a container the card names no commands but the start path. "Hide" applies to exactly the status shown (`aiStatusDismissed` in the settings store); when it changes, the card comes back.
- **Robust:** every widget sits in an error boundary (`WidgetFrame`) with `Suspense` inside: lazy widgets show a skeleton until their code has loaded, a failed chunk shows the error card. The stat widgets, the widgets built on `/learning/dashboard`, "Recent Runs", todos, the todo chart and "Upcoming" show a `WidgetSkeleton` while loading. If `/learning/dashboard` fails, a banner with retry appears; the other widgets stay usable.
- **Widget kinds:** a layout stores `WidgetInstance { instanceId, type, hidden?, size?, config? }`. *Standard singletons* (default, `instanceId = type`) are in every view automatically and can only be hidden; their set is pinned in `widgetRegistry.test.ts` (`DEFAULT_WIDGET_IDS`). *Catalog singletons* (`catalogOnly`, e.g. all Insights widgets) only get into a view through the catalog. *Multi widgets* (`multiple`) are added with their own config. Catalog and multi widgets can be removed, not only hidden. `config` holds primitive values only; keys are sorted like in the backend.
- **Size classes** `stat | chart | third | twoThirds | full` set the flex basis per breakpoint (`WIDGET_SIZE_CLASSES`). Rows with fewer widgets widen them instead of leaving gaps. In edit mode the size menu on a widget sets `instance.size`; choosing the registry size ("default") removes the field again.
- **"Edit dashboard"**: every widget has a toolbar with a handle (drag and drop, keyboard works too), size, hide and – for catalog and multi widgets – remove. While dragging, the tiles stay in place and the tile under the pointer is highlighted as the target (`dashboardDnd.ts`, also with mixed sizes). "Add widget" opens the catalog (`WidgetCatalogDialog`): all widgets of enabled modules by category with a description. It shows hidden widgets again, adds catalog widgets and asks multi widgets for their config through their `configForm`; at most 100 widgets per view. "Reset" restores the default layout. Widgets of disabled modules are missing here as well.
- **History & sync:** When editing ends, `useDashboardLayoutSync` saves a snapshot of all views to `/api/v1/dashboard/layouts` (hash dedupe, retention as a setting). On load the dashboard takes a newer server state as long as nothing changed locally; otherwise a banner asks. "History" in edit mode: restore, name, take a single view, set the device name. Layouts from a newer app version are not loaded, and a newer layout stored in the browser is not overwritten on load.
- **Views:** `DashboardViewTabs` shows one tab per view above the grid; with a single view the strip only appears in edit mode. Outside edit mode it is an ARIA tab list (arrow keys, Home/End; the widget area is the tab panel), in edit mode a sortable list. On narrow screens the active tab is scrolled into the visible part. In edit mode a view can be added (empty, becomes active, the name field opens), created from a template ("Operations", "Cost & models", "Quality", "Planning"; `dashboardTemplates.ts`), renamed (inline, trimmed, at most 80 code points), duplicated (widgets and filter, name "… (copy)"), deleted (with confirmation; the last view stays) and reordered by its handle; at most 20 views. These changes go into the history with "Done".
- **Empty view and template:** `widgets: []` means the default layout (all standard singletons). A template stores an explicit list: its widgets visible, every other standard singleton hidden (`templateWidgets`). Since new widgets are never standard singletons, a template view keeps showing exactly its widgets after updates. Templates never contain multi or workflow widgets.
- **Workflow widgets (phase 5):** two multi widgets in the "Workflows" category, both configured with `config { workflowId }` from the catalog form (`WorkflowWidgetConfigForm`). *Workflow status* shows name, run status, time, duration, quality gate and a marker when the latest run failed — **no output text**. *Workflow result* shows what the workflow reported through the `dashboard_widget: {json}` marker: title, traffic light, up to 6 KPIs, up to 10 lines and a summary, with the provenance line "Reported by the run" (neutral token — the same result can come from a marker in AI output or from a deterministic `dashboard-report` block, and the response does not say which). Both open the run with `navigate()`, never with a link. `WorkflowWidgetProvider` loads the data of all workflow widgets of the view with **one** request to `/api/v1/dashboard/workflow-results` and subscribes **once** to `/topic/dashboard/runs`; when a run of a shown workflow ends, the data is reloaded (polling every 60 s only while the WebSocket is down). States with their own message: no workflow chosen, workflow no longer available, no run yet, load error, "No valid dashboard result" (missing or rejected marker) — never the raw text, never a substitute green. The widgets never start a run.
- **Why the contract is this narrow:** workflow output is AI text from external sources (prompt injection is possible) and the API key lives in this origin's `localStorage`. The contract is validated in the backend (`DashboardWidgetResultParser`) and again in the browser (`workflowWidgetContract.ts`): known fields only, fixed lengths and counts, strings without control, zero-width, bidi and private-use characters, status and trend as enums. It holds **no** id and **no** URL field — so AI text can never become a link or a navigation target. Markup is not filtered: `<script>` stays text and is harmless because everything is rendered as React text nodes only. Anything that does not match makes the whole contract fail.
- **Active view per device:** switching tabs is not a layout change: no `dirty`, no version, other devices keep their view. `applyRemoteLayout` keeps the local active view as long as the server state contains it. `?view=<id>` opens a view, switching writes the parameter with `replace` (`useDashboardViewParam`); an unknown id keeps the current view; if that view arrives shortly after via sync it still opens, unless the user switched meanwhile.
- **Filter per view:** `filter { projectId?, days? }` is set in edit mode in `DashboardViewFilterBar` (project, time range via `TimeRangeSelector`); outside edit mode an active filter is only shown as a note. `DashboardDataProvider` queries `/learning/dashboard` once per dashboard with that filter, widgets read the result via `useDashboardData()`. The project applies to every widget, including todos, schedules (`/schedules/remaining-*?projectId=`) and the overall numbers (workflows, runs today, success rate, top workflows); only "Active Projects" still counts every project and says so below its value, version and API latency are system-wide. The time range applies to charts, cost and statistics — the overall numbers count all time regardless of it. When the view or filter changes, the old data stays visible (`keepPreviousData`) and the widget area is dimmed until the answer arrives. `useDashboardData()` also provides `filter`, `isPlaceholderData` and `dataDays` (time range of the data shown, e.g. for the cost label). The Insights widgets follow the filter.
- **Sync safeguards:** while a version is restored, adopted or saved (`busy`), the edit toggle, grid, tabs and filter are locked. A layout over 256 KiB is not sent; a dedicated message says why. "Take a view" asks when the view exists locally and there are unsaved changes, and reports a missing view or the limit.
- **New widget**: a component plus a registry entry with category and description (DE+EN). New widgets are `catalogOnly` or `multiple` – as a standard singleton they would show up in every view, template views included (that would take a deliberate change of `DEFAULT_WIDGET_IDS` in the test). Heavy widgets as a `React.lazy` entry. The `id` is persisted and must never be renamed (`widgetRegistry.test.ts` checks ids, labels, descriptions, modules). Content is rendered as React text only: `dashboardMarkupGuard.test.ts` forbids `dangerouslySetInnerHTML`/`innerHTML`/`outerHTML`/`insertAdjacentHTML`, Markdown renderers, `<img>`, `<iframe>`, `href`, `src`, `srcSet`, `xlinkHref`, `setAttribute` with a URL and `window.open` in `components/dashboard` and `components/insights`; the workflow widget files are additionally pinned there by name.

### Projects (`/projects`, `/projects/:id`)

- Search filter by name
- Grid layout (1/2/3 columns depending on screen width)
- Project cards with type icon and color coding
- Start/stop buttons for Docker containers
- Detail page: project info, container status, associated workflows
- Modals: create, edit, delete confirmation

### Workflows (`/workflows`, `/workflows/:id`)

- 2-step creation: template selection → form (project, name, description)
- 66 built-in templates (from DB, via `WorkflowTemplateSeeder`) plus custom templates
- Detail page: 2/3-column block editor (drag & drop), 1/3 sidebar (skills, runs, memory, info)
- Block editor with `@dnd-kit` for drag-and-drop sorting
- Modals: add block (type + JSON config via Monaco Editor), activate skill

### WorkflowExecution (`/workflows/:id/run/:runId`)

- Live status badge (PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED)
- Split view: steps panel (step indicators) + live logs (WebSocket)
- Pause/resume/cancel buttons during execution
- Star rating (1-5) after completion
- Error panel for failed runs

### Skills (`/skills`)

- Search filter + category filter
- Grid layout with skill cards
- Detail slide-over: capabilities, async badges, AI context preview, collapsible history (`skill_versions`)
- Card and detail show the skill's icon (emoji or Lucide name via `WorkflowIcon`); without one, the category icon in the category colour
- "New skill" and "Edit" open `SkillEditorModal` (name, category, icon via `EmojiPicker` — curated grid, "None", own input —, description, AI context in the Monaco Markdown editor); "Import" reads an export file, "Export" downloads it
- Built-in skills are read-only and can be copied as a custom skill (without capabilities)
- If a sealed workflow uses the skill, editing and deleting are locked — regardless of the `sealing` module; the hint names the way out (unseal, unsealing switched off, or `seal-immutable`)

### Schedules (`/schedules`)

- Table: workflow, cron expression, next/last execution, status toggle
- Create modal: workflow selection + cron expression + description

### Webhooks (`/webhooks`)

- Table: workflow, URL (copyable), token (masked + copyable), trigger count, status
- CopyButton component with success feedback
- Create modal: workflow selection + description

### Chains (`/chains`, `/chains/:id/run/:runId`)

- Split-panel layout: chain list on the left, detail/execution on the right
- Pipeline visualization of chain entries
- Create/edit modal with workflow search and sorting
- ChainExecution: live polling for entry status, navigation to individual workflow runs

### Todos (`/todos`)

- List of all TODOs with filters by status, priority, project, source, tag
- Status workflow: OPEN → IN_PROGRESS → DONE / CANCELLED
- Automatically detects AI-created TODOs (source: AI, sourceAgent: claude-code)
- Badge display in the sidebar for open TODOs

### ActivityLog (`/activity`)

- Paginated log table with filters by level, category, workflow
- Categories: WORKFLOW, SKILL, SYSTEM, SECURITY, etc.

### Wishes (`/wishes`)

- Enter natural language wishes and have them fulfilled asynchronously
- Iteration display, provide feedback, cancel or crystallize wishes

### WorkflowFactory (`/factory`)

- Generate workflow from topic category
- Full pipeline: generate → test instance → verify → promote
- Topic category selection

### DialogImport (`/dialogs`)

- Scan Claude sessions and import as workflows
- Session selection, preview, import with project assignment

### Docs (`/docs`)

- Split layout: document list on the left, Markdown rendering on the right
- Documents are loaded via `GET /api/v1/docs` from the `docs/` directory
- Markdown rendering with `react-markdown` + `remark-gfm` + `@tailwindcss/typography`

### Insights (`/insights`)

- Platform analytics and statistics
- Visualization of workflow performance and usage patterns

### WorkflowDetail (`/workflows/:id`) — Extensions

- **Memory tab:** View, activate, delete, re-extract workflow memory entries
- **Instructions tab:** Edit workflow-specific agent instructions in Monaco Editor
- **Gate status:** Display of the last gate status in the run history
- **BPMN diagram:** `BpmnDiagramView.tsx` draws the BPMN export as SVG (zoom, click/hover per block, live status during a run). The pure logic — reading the XML, top-to-bottom layout, wrapping labels, element id → block selection — lives in `bpmnLayout.ts` (test `bpmnLayout.test.ts`), the shapes and markers per element type in `BpmnShapes.tsx`.
- **Dashboard report form:** For blocks of type `dashboard-report`, the block editor (add and edit) shows a form for status, title, summary, KPIs and items in addition to raw JSON editing (`DashboardReportFields.tsx`, model in `dashboardReport.ts`). Bounds and status values come from the single contract mirror `components/dashboard/workflowWidgetContract.ts`; warnings are hints, validation happens in the backend. Values with `{{ … }}` are not length-checked.
- **AI suggestion:** `DashboardReportAssist.tsx` offers "Suggestion from last run" when the module, setting and provider `CLAUDE` are available (`useDashboardReportAssist.ts`). The suggestion appears as text in a review area and is only written to config after "Apply". Guard: `components/workflow/__tests__/dashboardReportMarkupGuard.test.ts`. The three off-switches have different effects: if the module is off, the block type is not offered in the editor at all. If the setting is off, the block keeps working but the "Suggestion" button does not appear. If this host has no AI, the button is not offered either — that is checked against the provider's model catalog (`useProviderModels('CLAUDE')` → `available`), which for `CLAUDE` looks up whether the `claude` program resolves on the `PATH`; `GET /system/ai-providers` is no use here because it lists registered Spring beans and always contains `CLAUDE`. An installed but unauthenticated CLI cannot be told apart without a real call — that case only fails when the suggestion runs.

### Settings (`/settings`)

- General: Language (EN/DE), Advanced Mode
- AI providers (`AiProviderStatusCard`, in Simple Mode too): per provider program and login found, or the command for it, "Check again" (`POST /system/ai-providers/status/refresh`)
- Appearance (own collapsible section): theme picker (18 themes based on well-known palettes, grouped dark/light), custom colors per theme
- Security: API key input (masked)
- Remote access: Tailscale status + enable/disable
- Execution: Claude concurrency, reserved-for-interactive, timeouts, switch for AI suggestion of dashboard reports
- Notifications: Notification configurations
- Backups: Database backup list
- Wishes: Wish settings
- **Stable:** Version comparison dev/stable, sync preview, sync definitions, start/stop/promote
- **Testing:** Test instance status, start/stop/reset, sync preview, sync definitions, pull changes
- System: Health status, system info, Docker status

## Components

### `AssistantPanel.tsx` (`src/components/AssistantPanel.tsx`)

- Floating chat panel, globally overlaid on the layout
- Context-sensitive chat with page suggestions
- Persisted sessions, WebSocket integration
- See [Assistant System](assistant-system.md) for details

### `src/components/settings/`

- Settings sub-components for the modular settings page
- Separate components: `GeneralSettingsCard`, `AppearanceCard`, `SecurityCard`, `ExecutionSettingsCard`, `NotificationsCard`, `BackupCard`, `WishSettingsCard`, `RemoteAccessCard`, `StagingCard`, `TestingCard`, `SystemInfoCard`
- Navigation via `SettingsNav`, shared layout via `SettingsSection`

### Shared Components (`src/components/shared/`)

| Component | Description |
|---|---|
| `StatusBadge` | Maps status strings to badge variants |
| `LogViewer` | Terminal-style log display: level colors, icons per live line kind, step badge, auto-scroll only while scrolled to the bottom (otherwise "Jump to latest") |
| `MonacoEditor` | Editor for JSON and Markdown (Monaco, follows the theme). `value` may hand every keystroke back through state: echoes of its own `onChange` are not written into the model (`monacoValueSync.ts`), any other value replaces the content. `prose` for running text (wrapping, no word suggestions, which would make Enter accept a suggestion); `jsonSchema` and `placeholders` for block configs; `path` gives each document its own model (its own undo history while switching, e.g. per onboarding profile and language), `lineNumbers="off"` hides the line numbers. No field uses `@monaco-editor/react` directly (guard in `MonacoEditor.test.tsx`) |
| `ErrorState` | Error message with icon |
| `EmptyState` | Empty state with description |
| `Pagination` | Page navigation for paginated lists |
| `CopyButton` | Copy button with success feedback |
| `GateStatusBadge` | Gate status display (RED/YELLOW/GREEN) |
| `GateStatusBanner` | Gate status banner for run details |
| `AgenticStepCard` | Display of agentic block steps |
| `ForEachStepCard` | Display of ForEach block iterations |
| `ApprovalDialog` | Dialog for approval block interaction |
| `HumanInputDialog` | Dialog for human input block interaction |
| `ExecutionBadges` | Status badges for executions |
| `ConversionSuggestion` | Suggestion for workflow conversion |
| `InsightsPanel` | Insights display for runs |
| `PathLinkedOutput` | Output with clickable file paths |
| `ProviderIcon` | Icon display for AI providers |
| `RouteErrorFallback` | Error boundary for route errors |
| `SyncSelectModal` | Selection modal for definition sync |
| `TerminalPanel` | Interactive xterm.js terminal with WebSocket connection |
| `TodoEditModal` | Modal for editing TODOs |
| `ErrorBoundary` | React error boundary with fallback UI |

## UI Component Library

All components in `src/components/ui/`:

| Component | Props | Description |
|---|---|---|
| `Button` | `variant` (primary/secondary/ghost/danger), `size` (sm/md/lg), `loading`, `icon` | Standard button with loading state |
| `Card` | `padding`, `className` | Container with border and background |
| `CardHeader` | `title`, `subtitle`, `action` | Card header with optional action |
| `Modal` | `open`, `onClose`, `title`, `footer`, `size` (sm/md/lg/xl) | Overlay modal with backdrop blur |
| `Badge` | `variant` (default/success/warning/danger/accent), `dot` | Status badge with optional dot |
| `Input` | `label`, `error`, `hint`, `icon` + HTML attributes | Form input with validation |
| `Select` | `label`, `options`, `error` | Dropdown selection |
| `Toggle` | `enabled`, `onChange`, `label`, `disabled` | Switch element |
| `Spinner` | `size` (sm/md/lg), `text` | Loading indicator |
| `Table<T>` | `columns`, `data`, `keyExtractor`, `onRowClick`, `emptyText` | Generic table |
| `ToastContainer` | (global) | Toast notifications (max 5) |
| `DirectoryPicker` | `value`, `onChange`, `label`, `placeholder`, `error` | Server filesystem browser |
| `CronPicker` | `value`, `onChange`, `label` | Cron expression editor with preview |
| `TagInput` | `tags`, `onChange`, `suggestions`, `label`, `hint`, `placeholder`, `compact` | Tag input with autocomplete |
| `ModalFooter` | `onCancel`, `onAction`, `actionLabel`, `actionVariant`, `actionLoading`, `actionDisabled`, `cancelLabel`, `cancelVariant` | Standardized modal footer |

## Design System

### CSS Variables

The theme is controlled via CSS custom properties (`src/theme/tokens.ts` for JS access, `src/index.css` for CSS defaults):

**Dark Theme (Default):**

| Variable | Value | Usage |
|---|---|---|
| `--color-surface` | `#231e19` | Page background |
| `--color-surface-secondary` | `#2a241e` | Secondary background |
| `--color-surface-raised` | `#2e2720` | Cards, elevated surfaces |
| `--color-surface-overlay` | `#332c24` | Modal background |
| `--color-surface-hover` | `#3a3128` | Hover background |
| `--color-border` | `#4a3f33` | Standard border |
| `--color-border-strong` | `#5e5143` | Emphasized border |
| `--color-text` | `#f7f3ee` | Primary text |
| `--color-text-secondary` | `#b8aa9a` | Secondary text |
| `--color-text-tertiary` | `#8c7e6e` | Tertiary text |
| `--color-text-inverse` | `#231e19` | Inverse text |
| `--color-accent` | `#eba44a` | Accent color (amber) |
| `--color-accent-hover` | `#f2ba62` | Accent hover |
| `--color-accent-subtle` | `#3d2e14` | Accent background |
| `--color-success` | `#6cc66c` | Success (green) |
| `--color-warning` | `#eba44a` | Warning (amber) |
| `--color-danger` | `#e07272` | Error (red) |
| `--color-success-subtle` | `#1e3320` | Success background |
| `--color-warning-subtle` | `#3d2e14` | Warning background |
| `--color-danger-subtle` | `#3d1f1f` | Error background |
| `--color-sidebar` | `#1a1614` | Sidebar background |
| `--color-sidebar-border` | `#342b23` | Sidebar border |
| `--color-sidebar-hover` | `#252019` | Sidebar hover |
| `--color-sidebar-active` | `#332b22` | Sidebar active |
| `--color-sidebar-text` | `#f7f3ee` | Sidebar text |
| `--color-sidebar-text-muted` | `#a6917f` | Sidebar text (muted) |
| `--color-assistant` | `#1c1812` | Assistant background |
| `--color-assistant-raised` | `#252019` | Assistant card background |
| `--color-assistant-border` | `#3d3020` | Assistant border |

**Light Theme:** Warm color scheme with `#fff8f3` background, `#3a2e22` text and `#f7bd54` accent color.

### Tailwind Configuration (v4, Inline)

No `tailwind.config.js` — configuration is done via `@theme inline` and `@custom-variant` directly in `src/index.css` (Tailwind CSS v4).

- **Themes:** colors are CSS variables (`src/theme/tokens.ts`). MySuricates Dark/Light have fixed values in `src/theme/themes.ts`; the 16 palette themes are derived at runtime from the official palette and the theme authors' roles (`palettes.ts` → `derive.ts`; sources: style guides or the official VS Code themes). Rules from a blind review (2026-09-14): surfaces/borders/text = author roles; accent/status/role colors are split — `--color-<name>` is the fill in the palette's own tone (≥ 3:1), `--color-<name>-text` the readable text variant (≥ 4.5:1), `bg-accent` uses `--color-accent-emphasis` (carries `text-text-inverse`); status colors are functional (the hue is only rotated when the palette tone doesn't read as success/warning/danger); card borders (`--color-card-border`) only for themes whose authors use borders; chart series `--color-chart-1..8` in a fixed order, red/yellow last. Palette themes also remap Tailwind's palette classes (`blue-500` …) via CSS variables (`tailwindPaletteVars`). `themes.test.ts` checks contrasts, status hues, distinguishable chart series and the remapped Tailwind shades — new themes must pass it.
- **Token usage:** components keep writing `text-success`/`bg-success`; `index.css` maps `text-*` to the text variant via `--text-color-*`. No hex colors in charts (`var(--color-chart-N)`), framed cards use `border-card-border`.
- **Dark Mode:** `class`-based via `@custom-variant dark (&:is(.dark *))`; the `.dark` class follows the active theme's `appearance` (as do Monaco and xterm via `useThemeAppearance()`)
- **Plugin:** `@tailwindcss/typography` via `@plugin`
- **Fonts:**
  - Sans: Plus Jakarta Sans (system-UI fallback)
  - Mono: JetBrains Mono (Fira Code fallback)
- **Custom animations:** `fade-in`, `slide-in`, `slide-up`, `pulse-subtle`, `slide-in-right`

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
| `useSidebarStore` | `collapsed`, `mobileOpen`, `toggle()`, `setCollapsed()`, `setMobileOpen()` | `localStorage: sidebar-state` (only `collapsed`) |
| `useThemeStore` | `themeId`, `lastLightThemeId`, `lastDarkThemeId`, `overrides` (per theme id), `setTheme()`, `toggleAppearance()`, `setOverride()`, `resetOverrides()`, `apply()` | `localStorage: theme-settings` (version 1; migrates the old `mode` format) |
| `useSettingsStore` | `apiKey`, `advancedMode`, `simpleMode`, `autoTodoDefault`, `autoRepairDefault`, `autoRepairMode`, `defaultAiProvider`, `assistantModel`, `suggestionsModel`, `suggestionsEnabled`, `sidebarItemOrder`, `sidebarHiddenItems`, `pinnedWorkflowIds`, `modeChoiceMade`, `hintsEnabled`, `dismissedHints` (hint id → dismissed version) | `localStorage: app-settings` (version 2; v2 turns `simpleOnboardingSeen` into the dismissed hint `simple-home`) |
| `useDashboardLayoutStore` | `activeViewId`, `views[]` (each view holds `widgets[]` as `WidgetInstance`, optional `filter { projectId?, days? }`; empty list = default layout), `setActiveViewWidgets()`, `resetActiveView()`, `applyRemoteLayout()` (keeps the local active view), `upsertView()`, `setActiveView()` (not a local change), `addView(name?, widgets?)` (empty or with template widgets), `renameView()`, `duplicateView()`, `removeView()`, `moveView()`, `setViewFilter()` ; selector `selectActiveView` | `localStorage: dashboard-layout` (version 2; migrates v1, validated on load via `sanitizeLayout`) |
| `useDashboardSyncStore` | `deviceLabel`, `baseVersionId`, `dirty`, `setDeviceLabel()`, `markDirty()`, `markSynced()` | `localStorage: dashboard-sync` (version 1) |
| `useAssistantStore` | `open`, `sessions[]`, `activeSessionId`, `showHistory`, `panelWidth`, `panelHeight`, `pendingInput`, `pendingContext` | `localStorage: assistant-sessions` (sessions, panel size) |
| `useI18nStore` | `locale: 'en'\|'de'`, `setLocale()` | `localStorage: i18n-locale` |
| `useToastStore` | `toasts[]`, `addToast()`, `removeToast()` | None (ephemeral) |

`useI18nStore` is defined in `src/i18n/index.ts` (not in `src/stores/`).

### TanStack Query (Server State)

All API data is managed via React Query:

- **Caching:** Automatic with query keys
- **Invalidation:** Related queries are invalidated after mutations
- **Optimistic updates:** For drag-and-drop block sorting
- **Auto-refetch:** Health check every 30 seconds

## API Hooks (`src/hooks/`)

### `useProjects.ts`

| Hook | Type | Description |
|---|---|---|
| `useProjects()` | Query | All projects |
| `useProject(id)` | Query | Single project |
| `useCreateProject()` | Mutation | Create project |
| `useUpdateProject()` | Mutation | Update project |
| `useArchiveProject()` | Mutation | Archive project (soft delete) |
| `useRestoreProject()` | Mutation | Restore archived project |
| `useArchivedProjects()` | Query | Archived projects |
| `useStartContainer(id)` | Mutation | Start container |
| `useStopContainer(id)` | Mutation | Stop container |
| `useGenerateProjectContext()` | Mutation | Generate AI context for project |
| `useOnboardingStatus(id)` | Query | Onboarding status of a project |
| `useEnableOnboarding()` | Mutation | Enable onboarding |
| `useDisableOnboarding()` | Mutation | Disable onboarding |
| `useRefreshOnboarding()` | Mutation | Refresh onboarding |
| `useOnboardingPreview(id)` | Query | Onboarding preview |

### `useWorkflows.ts`

| Hook | Type | Description |
|---|---|---|
| `useWorkflows()` | Query | All workflows |
| `useWorkflow(id)` | Query | Single workflow |
| `useWorkflowRuns(workflowId)` | Query | Runs of a workflow (paginated, auto-refetch) |
| `useSimilarWorkflows(id)` | Query | Find similar workflows |
| `useAllRuns(page, size, filters)` | Query | All runs (paginated) |
| `useCreateWorkflow()` | Mutation | Create workflow |
| `useUpdateWorkflow()` | Mutation | Update workflow |
| `useDeleteWorkflow()` | Mutation | Delete workflow (soft delete) |
| `useExecuteWorkflow()` | Mutation | Execute workflow |
| `usePauseExecution(runId)` | Mutation | Pause execution |
| `useResumeExecution(runId)` | Mutation | Resume execution |
| `useResumeFromStep()` | Mutation | Resume from specific step |
| `useCancelExecution(runId)` | Mutation | Cancel execution |
| `useRetryExecution()` | Mutation | Retry failed execution |
| `useRunCommand()` | Mutation | Send command to running execution |
| `useSubmitApproval()` | Mutation | Submit approval response |
| `useApproveTodos()` | Mutation | Approve TODOs for execution |
| `useImportWorkflow()` | Mutation | Import workflow from JSON |
| `useOptimizeWorkflow()` | Mutation | Optimize workflow via AI |
| `useRepairWorkflow()` | Mutation | Repair failed workflow |
| `useUpdateRunNotes()` | Mutation | Update notes on a run |

### `useSkills.ts`

| Hook | Type | Description |
|---|---|---|
| `useSkills()` | Query | All skills |
| `useSkill(id)` | Query | Single skill |
| `useSkillCapabilities(skillId)` | Query | Capabilities of a skill |
| `useActivateSkills()` | Mutation | Activate skills for workflow |
| `useDeactivateSkill()` | Mutation | Deactivate skill |
| `useSkillVersions(skillId, enabled)` | Query | Change history of a skill |
| `useCreateSkill()` / `useUpdateSkill()` / `useDeleteSkill()` / `useImportSkill()` | Mutation | Create, edit, delete, import custom skills; `isSkillPinnedBySealError` detects 409 `SKILL_PINNED_BY_SEAL` |

### `useSchedules.ts`

| Hook | Type | Description |
|---|---|---|
| `useSchedules()` | Query | All schedules |
| `useMaintenanceMode()` | Query | Maintenance mode status and an active `skip-until` pause (`pausedUntil`) |
| `useSetMaintenanceMode()` | Mutation | Toggle maintenance mode |
| `useCalendarData(start, end)` | Query | Calendar data for time period |
| `useRemainingToday()` | Query | Remaining runs today |
| `useRemainingThisWeek()` | Query | Remaining runs this week |
| `useCronPreview(expr)` | Query | Preview of next executions |
| `useScheduleRuns(id)` | Query | Runs of a schedule |
| `useCreateSchedule()` | Mutation | Create schedule |
| `useUpdateSchedule()` | Mutation | Update schedule |
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
| `useBackupList()` | Query | Database backups |
| `useAiProviders()` | Query | AI providers and default |
| `usePiCatalog()` | Query | Pi provider catalog |
| `useAiProviderStatus()` | Query (60 s stale) | Readiness of the AI CLIs per provider |
| `useRefreshAiProviderStatus()` | Mutation | Check readiness again at once |
| `useUpdateSystemSetting()` | Mutation | Change system setting |
| `useAssistantModelSetting()` | Query | Assistant model setting |
| `useAutoRepairModeSetting()` | Query | Auto-repair mode setting |
| `useSuggestionsModelSetting()` | Query | Suggestions model setting |
| `useAssistantThinkingPhrases()` | Query | Localized thinking phrases |
| `useRepairAllFailed()` | Mutation | Repair all failed runs |
| `useExecutionConfig()` | Query | Execution configuration |
| `useUpdateExecutionConfig()` | Mutation | Change execution configuration |
| `useBrowseDirectory()` | Query | Browse server filesystem |

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
| `useTodos(filters)` | Query | TODOs with filters (paginated, auto-refetch) |
| `useTodo(id)` | Query | Single TODO |
| `useTodoCount()` | Query | TODO count by status |
| `useProjectActiveTodos(projectId)` | Query | Active TODOs of a project |
| `useCreateTodo()` | Mutation | Create TODO |
| `useUpdateTodo()` | Mutation | Update TODO |
| `useUpdateTodoStatus()` | Mutation | Change status |
| `useBulkUpdateTodoStatus()` | Mutation | Change multiple TODOs at once |
| `useArchiveStale()` | Mutation | Archive stale TODOs |
| `useDeleteTodo()` | Mutation | Delete TODO |
| `useExportTodo()` | Mutation | Export TODO to external tracker |
| `useResolveTodo()` | Mutation | Start AI resolution for TODO |

### `useActivityLog.ts`

| Hook | Type | Description |
|---|---|---|
| `useActivityLog(page, size, filters)` | Query | Activity log entries (paginated) |

### `useWorkflowMemory.ts`

| Hook | Type | Description |
|---|---|---|
| `useWorkflowMemories(workflowId)` | Query | Memory entries of a workflow |
| `useExtractMemory()` | Mutation | Extract knowledge from runs |
| `useActivateMemory()` | Mutation | Activate memory entry |
| `useUpdateMemory()` | Mutation | Edit memory entry |
| `useDeleteMemory()` | Mutation | Delete memory entry |

### `useStaging.ts`

| Hook | Type | Description |
|---|---|---|
| `useStagingStatus()` | Query | Stable status + version |
| `useStagingStart()` | Mutation | Start stable |
| `useStagingStop()` | Mutation | Stop stable |
| `useStagingPromote()` | Mutation | Execute promote |
| `useStagingCancel()` | Mutation | Cancel running promotion |
| `useStagingSyncPreview(enabled)` | Query | Sync preview |
| `useStagingSync()` | Mutation | Synchronize definitions |

### `useTesting.ts`

| Hook | Type | Description |
|---|---|---|
| `useTestingStatus()` | Query | Test instance status |
| `useTestingStart()` | Mutation | Start test instance |
| `useTestingStop()` | Mutation | Stop test instance |
| `useTestingReset()` | Mutation | Reset test database |
| `useTestingSyncPreview(enabled)` | Query | Sync preview for test instance |
| `useTestingSync()` | Mutation | Synchronize definitions to test |
| `useTestingPullChanges()` | Mutation | Pull changes from test to dev |

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
| `useGroupedDocs()` | Query (wrapper) | Documents grouped by label with DE/EN variants |
| `useDocContent(name, lang)` | Query | Content of a document (language-specific) |

### `usePool.ts`

| Hook | Type | Description |
|---|---|---|
| `usePoolWorkflows()` | Query | Pool workflows |
| `useInstallPoolWorkflow()` | Mutation | Install workflow from pool |
| `usePoolExport()` | Mutation | Export workflow to pool |

### `useAssistant.ts`

| Hook | Type | Description |
|---|---|---|
| `useAssistantChat()` | Mutation | Send chat message to assistant (abort support) |
| `useConfirmPendingTool()` | Mutation | Confirm pending tool action |
| `useRejectPendingTool()` | Mutation | Reject pending tool action |
| `useAssistantSuggestions(page)` | Query | Load page-specific suggestions |
| `useAssistantSessions(enabled)` | Query | Load session summaries |
| `useAssistantSessionDetail(sessionId)` | Query | Full session details |
| `useDeleteAssistantSession()` | Mutation | Delete session |

### `useChainTemplates.ts`

| Hook | Type | Description |
|---|---|---|
| `useChainTemplates()` | Query | Available chain templates |
| `useInstallChainTemplate()` | Mutation | Install chain template |

### `useCompletionTriggers.ts`

| Hook | Type | Description |
|---|---|---|
| `useCompletionTriggersAsSource(workflowId)` | Query | Triggers where this workflow is the source |
| `useCompletionTriggersAsTarget(workflowId)` | Query | Triggers where this workflow is the target |
| `useCreateCompletionTrigger()` | Mutation | Create trigger |
| `useToggleCompletionTrigger(workflowId)` | Mutation | Enable/disable trigger |
| `useDeleteCompletionTrigger(workflowId)` | Mutation | Delete trigger |

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
| `useDialogSessionCounts()` | Query | Session count per project |
| `useDialogSessions(projectPath?, limit)` | Query | Claude sessions (optionally filtered by project) |
| `useDialogProjects()` | Query | Project paths with dialog sessions |
| `useAnalyzeDialog()` | Mutation | Analyze session for workflow suggestions |
| `useCreateWorkflowFromDialog()` | Mutation | Create workflow from dialog analysis |

### `useInsights.ts`

| Hook | Type | Description |
|---|---|---|
| `useRunInsights(runId)` | Query | AI-generated insights for a specific run |

### `useNotifications.ts`

| Hook | Type | Description |
|---|---|---|
| `useNotifications()` | Query | All notification configurations |
| `useCreateNotification()` | Mutation | Create notification |
| `useToggleNotification()` | Mutation | Enable/disable notification |
| `useDeleteNotification()` | Mutation | Delete notification |

### `usePersistedState.ts`

Generic hook for persisted state via `localStorage`.

### `useWorkflowFactory.ts`

| Hook | Type | Description |
|---|---|---|
| `useFactoryTopics()` | Query | Factory topic categories |
| `useFactoryGenerate()` | Mutation | Generate workflow from topic |
| `useFactoryRun()` | Mutation | Execute factory request end-to-end |
| `useFactoryVerify()` | Mutation | Verify factory workflow |
| `useFactoryPromote()` | Mutation | Promote factory workflow |

### `useWebSocket.ts`

```typescript
useExecutionWebSocket(runId: string | null, workflowId?: string, options?: { liveOutput?: boolean }) → {
  logs: ExecutionLogEntry[],   // { id, timestamp, level, message, kind?, stepIndex?, stepLabel?, skipped? }
  connected: boolean,
  clearLogs: () => void,
  pendingApproval: PendingApproval | null,
  pendingHumanInput: object | null,
  agenticStates: Map<number, object>,
  forEachStates: Map<number, ForEachState>
}
```

Connects to the backend via SockJS/STOMP and receives live logs, approval prompts, human input prompts, agentic states, and ForEach states. With `liveOutput` (default `true`) `live_output` batches are added; on (re)connect the hook loads the buffer via `GET /executions/{id}/live-log` and de-duplicates by `bufferId` + `seq` (gaps are shown as a marker). The message → entry mapping lives, purely functional, in `hooks/executionLogEntries.ts`.

### `useToast.ts`

```typescript
useToast() → {
  success(message), error(message), info(message), warning(message)
}
```

### `useAssistantWebSocket.ts`

```typescript
useAssistantWebSocket(requestId: string | null, sessionId: string | null) → {
  currentPhase: string | null,
  events: AssistantPushEvent[],
  connected: boolean,
  clearEvents: () => void
}
```

WebSocket connection for real-time status updates and push events from the assistant.

### `useIntegrations.ts`

| Hook | Type | Description |
|---|---|---|
| `useIntegrations()` | Query | All providers with connection status |
| `useIntegration(providerId)` | Query | Single integration provider |
| `useTaskTargets(providerId, enabled)` | Query | Task export targets of a provider |
| `useConnectIntegration()` | Mutation | Establish OAuth connection |
| `useDisconnectIntegration()` | Mutation | Disconnect OAuth connection |
| `useSaveCredentials()` | Mutation | Save OAuth credentials |
| `useDeleteCredentials()` | Mutation | Delete credentials |
| `useTestIntegrationAction()` | Mutation | Test provider action |

### `useWorkflowVersions.ts`

| Hook | Type | Description |
|---|---|---|
| `useWorkflowVersions(id)` | Query | Version history of a workflow |
| `useRestoreWorkflowVersion()` | Mutation | Restore version |

### `useOnboardingProfiles.ts`

| Hook | Type | Description |
|---|---|---|
| `useOnboardingProfiles()` | Query | All onboarding profiles |
| `useOnboardingProfile(id)` | Query | Single profile |
| `useCreateOnboardingProfile()` | Mutation | Create profile |
| `useUpdateOnboardingProfile()` | Mutation | Update profile |
| `useDeleteOnboardingProfile()` | Mutation | Delete profile |
| `useResetOnboardingProfile()` | Mutation | Reset profile to built-in defaults |
| `useOnboardingProfileHistory(profileId)` | Query | Version history of a profile |

### `useCalendarAggregated.ts`

Aggregates events from multiple sources (Google Calendar, Trello, Taskwarrior, schedules, runs) via the calendar adapter pattern. Returns `{ events, sources, isLoading, setSourceColor }`.

### `useCalendarColors.ts`

Manages custom colors per calendar source (persisted in `localStorage`).

### `useConversion.ts`

| Hook | Type | Description |
|---|---|---|
| `useConversionSuggestion(runId, enabled)` | Query | AI-generated workflow suggestion from a run |
| `useCreateWorkflowFromSuggestion()` | Mutation | Create workflow from conversion suggestion |

### `useLearning.ts`

| Hook | Type | Description |
|---|---|---|
| `useDashboard(days, projectId?)` | Query | Learning dashboard data (auto-poll 30s) |
| `usePlatformStats()` | Query | Platform-wide statistics |
| `useRateRun()` | Mutation | Rate workflow run |

### `useReports.ts`

| Hook | Type | Description |
|---|---|---|
| `useReportsList()` | Query | List of available reports |
| `useReportContent(name)` | Query | Content of a specific report |

### `useTemplates.ts`

| Hook | Type | Description |
|---|---|---|
| `useTemplates()` | Query | All workflow templates |
| `useCreateTemplate()` | Mutation | Create workflow template |
| `useSaveWorkflowAsTemplate()` | Mutation | Save existing workflow as template |

### `useDeadLetters.ts`

| Hook | Type | Description |
|---|---|---|
| `useDeadLetters(status, page, size)` | Query | Paginated dead letter queue entries |
| `useRetryDeadLetter()` | Mutation | Retry dead letter entry |
| `useDiscardDeadLetter()` | Mutation | Discard dead letter entry |

### `useLatestRunStatuses.ts`

| Hook | Type | Description |
|---|---|---|
| `useLatestRunStatuses(size)` | Hook | Map of workflow ID → latest run status (derived from `useAllRuns`) |

### `usePaletteHistory.ts`

| Hook | Type | Description |
|---|---|---|
| `usePaletteHistory(contextId)` | Hook | Recent/pinned IDs for the command palette (localStorage-based) |

### `useTodoOverview.ts`

| Hook | Type | Description |
|---|---|---|
| `useTodoOverview()` | Query | Cross-project cockpit overview with per-project rollups |

### `useWorkflowDebug.ts`

| Hook | Type | Description |
|---|---|---|
| `useBrokenWorkflows()` | Query | Workflows with failed last run (auto-poll 30s) |

### `useIsMobile.ts`

Responsive hook, detects mobile breakpoint (max-width: 1023px) for adaptive UI adjustments.

## Internationalization (i18n)

- **Languages:** German (`de`), English (`en`)
- **Files:** `src/i18n/de.ts`, `src/i18n/en.ts`
- **Hook:** `useT()` → returns the current translation object
- **Store:** `useI18nStore()` → `locale`, `setLocale()`
- **Toggle:** Button in the header
- **Dates and numbers:** only through `src/utils/localeFormat.ts` (`formatDate`, `formatTimeOfDay`, `formatTimestamp`, `formatNumber`, `formatRelativeUnit`) or `src/utils/formatTime.ts`, always with `locale` from `useI18nStore` — without a locale the browser writes in its own language. `src/__tests__/localeFormat.guard.test.ts` allows `Intl` and the `toLocale…String` methods nowhere else
- **`<html lang>`:** follows the app language (`syncDocumentLanguage()` in `main.tsx`), for screen readers and hyphenation

### Translation Structure

~1,250 leaf strings in 34+ top-level keys (excerpt):

```
common (~46 keys) ──── General labels (create, delete, search, ...)
nav (~27 keys) ──────── Navigation (dashboard, projects, ...)
simple (~125 keys) ──── Simple mode texts (home, workflows, runs, ...)
dashboard (~26 keys) ── Dashboard texts
projects (~52 keys) ─── Projects + onboarding, types, status
workflows (~155 keys)   Workflows + block types, execution, gate, agentic
skills (~13 keys) ───── Skills + categories
schedules (~55 keys) ── Schedules + weekdays
webhooks (8 keys) ───── Webhooks
integrations (~40 keys) Integration providers
calendar (~8 keys) ──── Calendar + sources
notifications (~16 keys) Notifications
todoTriggers (~17 keys) Todo triggers
completionTriggers (~15 keys) Completion triggers
settings (~150 keys) ── Settings (general, execution, staging, testing)
toast (~60 keys) ────── Toast messages
templates (~14 keys) ── Workflow templates
pool (7 keys) ───────── Workflow pool
insights (~75 keys) ─── Platform insights
runs (~16 keys) ──────── Run overview
activityLog (~12 keys)  Activity log
todos (~38 keys) ────── Todos + status, priorities, sources
wishes (~55 keys) ───── Wishes + status, iteration
chains (~27 keys) ───── Chains + execution
factory (~40 keys) ──── Workflow factory
assistant (~22 keys) ── Assistant
commandPalette (~28 keys) Command palette
charts (~10 keys) ───── Chart labels
```

## PWA Configuration

Configured via `vite-plugin-pwa` in `vite.config.ts`:

- **Name:** MySuricates
- **Short name:** MySuricates
- **Display:** Standalone
- **Theme color:** `#231e19`
- **Service worker:** Auto-update
- **Icon:** `/icons/icon.svg` (SVG, maskable)

## API Client (`src/api/client.ts`)

```typescript
class ApiClient {
  BASE_URL = '/api/v1'

  getHeaders() → { Content-Type: application/json, X-API-Key: ... }

  get<T>(path) → Promise<T>
  post<T>(path, body?, options?) → Promise<T>
  put<T>(path, body?) → Promise<T>
  patch<T>(path, body?) → Promise<T>
  delete<T>(path) → Promise<T>
}
```

Errors are thrown as `ApiError` with `{ error, timestamp, fieldErrors? }`. Requests have a 300-second timeout via `AbortController`.

Standalone exports: `openPath(path)` opens a file path via the backend, `apiErrorMessage(err, fallback)` extracts error messages from API responses.

The API key is read from the `settingsStore` and automatically appended to all requests.

## Proxy Configuration (Vite)

In `vite.config.ts`:

| Path | Target | Description |
|---|---|---|
| `/api` | `http://localhost:9090` | REST API |
| `/ws` | `ws://localhost:9090` | WebSocket (STOMP/SockJS) |

The dev server runs on port 3000 (`strictPort`) and proxies all API requests to the backend on port 9090. Via `CYPRESS_TARGET=test`, the proxy targets are redirected to port 8090. HTTPS is automatically enabled when `../certs/dev.crt` and `../certs/dev.key` exist.
