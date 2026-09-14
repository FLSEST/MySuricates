> *Zuletzt aktualisiert: [auto-generated]*

# Frontend

## Routing

| Pfad | Seite | Beschreibung |
|---|---|---|
| `/` | Dashboard | Statistiken, Charts, letzte Runs |
| `/projects` | Projects | Projektliste, Erstellen |
| `/projects/:id` | ProjectDetail | Projektdetails, Container, Workflows |
| `/wishes` | Wishes | Wunsch-System, iterative Erfüllung |
| `/workflows` | Workflows | Workflow-Liste, Erstellen mit Templates |
| `/workflows/:id` | WorkflowDetail | Block-Editor, Skills, Runs, Memory |
| `/workflows/:id/run/:runId` | WorkflowExecution | Live-Ausführung, Logs, Steps |
| `/factory` | WorkflowFactory | Workflow-Generator aus Topics |
| `/dialogs` | DialogImport | Claude-Sessions als Workflows importieren |
| `/chains` | Chains | Workflow-Chains, Split-Panel-Layout |
| `/chains/:id/run/:runId` | ChainExecution | Live Chain-Ausführung, Entry-Status |
| `/runs` | Runs | Alle Workflow-Runs (globale Liste) |
| `/skills` | Skills | Skill-Katalog mit Capabilities |
| `/schedules` | Schedules | Cron-basierte Zeitpläne |
| `/webhooks` | Webhooks | Webhook-Trigger-Verwaltung |
| `/todos` | Todos | TODO-Liste, Filter, Statusverwaltung |
| `/activity` | ActivityLog | System-Activity-Log mit Filtern |
| `/dlq` | DeadLetterQueue | Dead-Letter-Queue für fehlgeschlagene Events |
| `/debug` | WorkflowDebug | Workflows mit fehlgeschlagenen Runs analysieren |
| `/docs` | Docs | Markdown-Dokumentation (Split-Layout) |
| `/reports` | Reports | Generierte Berichte anzeigen |
| `/insights` | Insights | Plattform-Einblicke und Analysen |
| `/integrations` | Integrations | Integrations-Provider verwalten |
| `/runs/:runId` | RunRedirect | Redirect → `/workflows/:workflowId/run/:runId` (API-Lookup) |
| `/calendar` | CalendarPage | Aggregierter Kalender (Schedules, Runs, Integrationen) |
| `/cockpit` | ProjectCockpit | Projekt-Cockpit mit Todo-Übersicht |
| `/onboarding-profiles` | OnboardingProfiles | Onboarding-Profile verwalten |
| `/settings` | Settings | Sprache, Theme, API-Key, Tailscale, Staging, Testing |

Alle Routen sind in `src/App.tsx` definiert und von einem `<Layout>`-Wrapper umgeben (Sidebar + Header).

### Simple Mode

`useSettingsStore().simpleMode` schaltet neun Seiten zwischen Normal- und Simple-Variante um:

| Route | Normal | Simple |
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

Die Umschaltung erfolgt über Route-Wrapper-Komponenten (z.B. `DashboardRoute`, `WorkflowsRoute`, `RunsRoute`) in `App.tsx`, die je nach `simpleMode`-Flag die entsprechende Variante rendern.

## Seiten

### Dashboard (`/`)

Die Seite rendert die Widgets aus der Registry `components/dashboard/widgetRegistry.ts`: Kennzahlen, Diagramme, Todos, letzte Runs und Schnellaktionen.

- **Widget = Registry-Eintrag** `{ id, label, size, module?, multiple?, component }`. Die Komponente lädt ihre Daten selbst über Query-Hooks und bekommt `{ instanceId, config }` als Props. Prop-basierte Karten, die Insights mit eigenem Zeitraum nutzt, bekommen einen Wrapper (`DashboardDataWidgets.tsx`). Rendert ein Widget nichts, fällt sein Slot weg (`empty:hidden`).
- **Robust:** Jedes Widget liegt in einer Fehlergrenze (`WidgetFrame`) und zeigt beim Laden ein `WidgetSkeleton`. Scheitert `/learning/dashboard`, erscheint ein Banner mit Retry; die übrigen Widgets bleiben nutzbar.
- **Instanzen:** Ein Layout speichert `WidgetInstance { instanceId, type, hidden?, size?, config? }`. Singleton-Widgets (Default) sind automatisch da (`instanceId = type`); `multiple`-Widgets werden nur explizit mit Konfiguration angelegt. `config` enthält nur primitive Werte.
- **Größenklassen** `stat | chart | third | twoThirds | full` bestimmen die Flex-Basis pro Breakpoint (`WIDGET_SIZE_CLASSES`). Zeilen mit weniger Widgets verbreitern diese, statt Lücken zu lassen.
- **„Dashboard bearbeiten"**: Sortieren per Drag & Drop am Griff (auch per Tastatur), Ausblenden mit dem Auge, „+ Name" blendet wieder ein, „Zurücksetzen". Widgets abgeschalteter Module fehlen auch hier.
- **Neues Widget**: Komponente plus Registry-Eintrag. Singletons erscheinen in angepassten Layouts automatisch am Ende. Die `id` ist persistiert und darf nie umbenannt werden (`widgetRegistry.test.ts` prüft IDs, Labels, Module).

### Projects (`/projects`, `/projects/:id`)

- Suchfilter nach Name
- Grid-Layout (1/2/3 Spalten je nach Bildschirmbreite)
- Projekt-Cards mit Typ-Icon und Farbcodierung
- Start/Stop-Buttons für Docker-Container
- Detail-Seite: Projektinfo, Container-Status, zugehörige Workflows
- Modals: Erstellen, Bearbeiten, Löschen-Bestätigung

### Workflows (`/workflows`, `/workflows/:id`)

- 2-Schritt-Erstellung: Template-Auswahl → Formular (Projekt, Name, Beschreibung)
- 44 Built-in-Templates (aus DB, via `WorkflowTemplateSeeder`) plus benutzerdefinierte Templates
- Detail-Seite: 2/3-Spalte Block-Editor (Drag & Drop), 1/3-Sidebar (Skills, Runs, Memory, Info)
- Block-Editor mit `@dnd-kit` für Drag-and-Drop-Sortierung
- Modals: Block hinzufügen (Typ + JSON-Config via Monaco Editor), Skill aktivieren

### WorkflowExecution (`/workflows/:id/run/:runId`)

- Live-Status-Badge (PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED)
- Split-View: Steps-Panel (Schritt-Indikatoren) + Live-Logs (WebSocket)
- Pause/Resume/Cancel-Buttons während der Ausführung
- Sternebewertung (1-5) nach Abschluss
- Fehler-Panel bei fehlgeschlagenen Runs

### Skills (`/skills`)

- Suchfilter + Kategorie-Filter
- Grid-Layout mit Skill-Cards
- Detail-Slide-Over: Capabilities, Async-Badges, AI-Context-Vorschau
- Kategorie-Icons und -Farben

### Schedules (`/schedules`)

- Tabelle: Workflow, Cron-Ausdruck, nächste/letzte Ausführung, Status-Toggle
- Erstellen-Modal: Workflow-Auswahl + Cron-Ausdruck + Beschreibung

### Webhooks (`/webhooks`)

- Tabelle: Workflow, URL (kopierbar), Token (maskiert + kopierbar), Trigger-Count, Status
- CopyButton-Komponente mit Erfolgsfeedback
- Erstellen-Modal: Workflow-Auswahl + Beschreibung

### Chains (`/chains`, `/chains/:id/run/:runId`)

- Split-Panel-Layout: Chain-Liste links, Detail/Ausführung rechts
- Pipeline-Visualisierung der Chain-Entries
- Erstellen/Bearbeiten-Modal mit Workflow-Suche und Sortierung
- ChainExecution: Live-Polling für Entry-Status, Navigation zu einzelnen Workflow-Runs

### Todos (`/todos`)

- Liste aller TODOs mit Filter nach Status, Priorität, Projekt, Quelle, Tag
- Status-Workflow: OPEN → IN_PROGRESS → DONE / CANCELLED
- Erkennt automatisch von AI erstellte TODOs (source: AI, sourceAgent: claude-code)
- Badge-Anzeige in der Sidebar für offene TODOs

### ActivityLog (`/activity`)

- Paginierte Log-Tabelle mit Filter nach Level, Kategorie, Workflow
- Kategorien: WORKFLOW, SKILL, SYSTEM, SECURITY, u.a.

### Wishes (`/wishes`)

- Natürlichsprachige Wünsche eingeben und asynchron erfüllen lassen
- Iterationsanzeige, Feedback geben, Wunsch abbrechen oder kristallisieren

### WorkflowFactory (`/factory`)

- Workflow aus Topic-Kategorie generieren
- Vollständige Pipeline: Generieren → Test-Instanz → Verifizieren → Promoten
- Topic-Kategorien-Auswahl

### DialogImport (`/dialogs`)

- Claude-Sessions scannen und als Workflows importieren
- Session-Auswahl, Vorschau, Import mit Projekt-Zuordnung

### Docs (`/docs`)

- Split-Layout: Dokument-Liste links, Markdown-Rendering rechts
- Dokumente werden via `GET /api/v1/docs` aus `docs/`-Verzeichnis geladen
- Markdown-Rendering mit `react-markdown` + `remark-gfm` + `@tailwindcss/typography`

### Insights (`/insights`)

- Plattform-Analysen und Statistiken
- Visualisierung von Workflow-Performance und Nutzungsmustern

### WorkflowDetail (`/workflows/:id`) — Erweiterungen

- **Memory-Tab:** Workflow-Memory-Einträge anzeigen, aktivieren, löschen, neu extrahieren
- **Instructions-Tab:** Workflow-spezifische Agent-Instructions im Monaco-Editor bearbeiten
- **Gate-Status:** Anzeige des letzten Gate-Status im Run-Verlauf

### Settings (`/settings`)

- Allgemein: Sprache (EN/DE), Advanced Mode
- Aussehen (eigene, einklappbare Sektion): Theme-Auswahl (18 Themes nach bekannten Paletten, gruppiert Dunkel/Hell), eigene Farben pro Theme
- Sicherheit: API-Key-Eingabe (maskiert)
- Remote-Access: Tailscale-Status + Enable/Disable
- Ausführung: Claude-Concurrency, Reserved-for-Interactive, Timeouts
- Benachrichtigungen: Notification-Konfigurationen
- Backups: Datenbank-Backup-Liste
- Wishes: Wish-Einstellungen
- **Stable:** Versionsvergleich Dev/Stable, Sync-Preview, Sync-Definitions, Start/Stop/Promote
- **Testing:** Test-Instanz-Status, Start/Stop/Reset, Sync-Preview, Sync-Definitions, Pull-Changes
- System: Health-Status, Systeminfos, Docker-Status

## Komponenten

### `AssistantPanel.tsx` (`src/components/AssistantPanel.tsx`)

- Floating-Chat-Panel, global über dem Layout eingeblendet
- Kontext-sensitiver Chat mit Page Suggestions
- Persistierte Sessions, WebSocket-Integration
- Siehe [Assistant System](assistant-system.md) für Details

### `src/components/settings/`

- Settings-Unterkomponenten für die modulare Settings-Seite
- Separate Komponenten: `GeneralSettingsCard`, `AppearanceCard`, `SecurityCard`, `ExecutionSettingsCard`, `NotificationsCard`, `BackupCard`, `WishSettingsCard`, `RemoteAccessCard`, `StagingCard`, `TestingCard`, `SystemInfoCard`
- Navigation über `SettingsNav`, gemeinsames Layout via `SettingsSection`

### Shared-Komponenten (`src/components/shared/`)

| Komponente | Beschreibung |
|---|---|
| `StatusBadge` | Mappt Status-Strings auf Badge-Varianten |
| `LogViewer` | Log-Anzeige im Terminal-Look: Level-Farben, Icons pro Live-Zeilenart, Step-Badge, Auto-Scroll nur solange man am Ende ist (sonst „Zu den neuesten Zeilen“) |
| `MonacoEditor` | JSON-Editor (Monaco, Dark Theme) |
| `ErrorState` | Fehlermeldung mit Icon |
| `EmptyState` | Leerer Zustand mit Beschreibung |
| `Pagination` | Seitennavigation für paginierte Listen |
| `CopyButton` | Kopier-Button mit Erfolgsfeedback |
| `GateStatusBadge` | Gate-Status-Anzeige (RED/YELLOW/GREEN) |
| `GateStatusBanner` | Gate-Status-Banner für Run-Details |
| `AgenticStepCard` | Darstellung von Agentic-Block-Steps |
| `ForEachStepCard` | Darstellung von ForEach-Block-Iterationen |
| `ApprovalDialog` | Dialog für Approval-Block-Interaktion |
| `HumanInputDialog` | Dialog für Human-Input-Block-Interaktion |
| `ExecutionBadges` | Status-Badges für Ausführungen |
| `ConversionSuggestion` | Vorschlag zur Workflow-Konvertierung |
| `InsightsPanel` | Insights-Anzeige für Runs |
| `PathLinkedOutput` | Output mit klickbaren Dateipfaden |
| `ProviderIcon` | Icon-Anzeige für AI-Provider |
| `RouteErrorFallback` | Error-Boundary für Route-Fehler |
| `SyncSelectModal` | Auswahl-Modal für Definition-Sync |
| `TerminalPanel` | Interaktives xterm.js-Terminal mit WebSocket-Anbindung |
| `TodoEditModal` | Modal zum Bearbeiten von TODOs |
| `ErrorBoundary` | React Error Boundary mit Fallback-UI |

## UI-Komponentenbibliothek

Alle Komponenten in `src/components/ui/`:

| Komponente | Props | Beschreibung |
|---|---|---|
| `Button` | `variant` (primary/secondary/ghost/danger), `size` (sm/md/lg), `loading`, `icon` | Standard-Button mit Loading-State |
| `Card` | `padding`, `className` | Container mit Border und Hintergrund |
| `CardHeader` | `title`, `subtitle`, `action` | Card-Header mit optionaler Action |
| `Modal` | `open`, `onClose`, `title`, `footer`, `size` (sm/md/lg/xl) | Overlay-Modal mit Backdrop-Blur |
| `Badge` | `variant` (default/success/warning/danger/accent), `dot` | Status-Badge mit optionalem Punkt |
| `Input` | `label`, `error`, `hint`, `icon` + HTML-Attribute | Formular-Input mit Validierung |
| `Select` | `label`, `options`, `error` | Dropdown-Auswahl |
| `Toggle` | `enabled`, `onChange`, `label`, `disabled` | Switch-Element |
| `Spinner` | `size` (sm/md/lg), `text` | Lade-Indikator |
| `Table<T>` | `columns`, `data`, `keyExtractor`, `onRowClick`, `emptyText` | Generische Tabelle |
| `ToastContainer` | (global) | Toast-Benachrichtigungen (max. 5) |
| `DirectoryPicker` | `value`, `onChange`, `label`, `placeholder`, `error` | Server-Dateisystem-Browser |
| `CronPicker` | `value`, `onChange`, `label` | Cron-Ausdruck-Editor mit Vorschau |
| `TagInput` | `tags`, `onChange`, `suggestions`, `label`, `hint`, `placeholder`, `compact` | Tag-Eingabe mit Autovervollständigung |
| `ModalFooter` | `onCancel`, `onAction`, `actionLabel`, `actionVariant`, `actionLoading`, `actionDisabled`, `cancelLabel`, `cancelVariant` | Standardisierter Modal-Footer |

## Design-System

### CSS-Variablen

Das Theme wird über CSS Custom Properties gesteuert (`src/theme/tokens.ts` für JS-Zugriff, `src/index.css` für CSS-Defaults):

**Dark Theme (Standard):**

| Variable | Wert | Verwendung |
|---|---|---|
| `--color-surface` | `#231e19` | Seiten-Hintergrund |
| `--color-surface-secondary` | `#2a241e` | Sekundärer Hintergrund |
| `--color-surface-raised` | `#2e2720` | Cards, erhöhte Flächen |
| `--color-surface-overlay` | `#332c24` | Modal-Hintergrund |
| `--color-surface-hover` | `#3a3128` | Hover-Hintergrund |
| `--color-border` | `#4a3f33` | Standard-Rahmen |
| `--color-border-strong` | `#5e5143` | Betonte Rahmen |
| `--color-text` | `#f7f3ee` | Primärtext |
| `--color-text-secondary` | `#b8aa9a` | Sekundärtext |
| `--color-text-tertiary` | `#8c7e6e` | Tertiärtext |
| `--color-text-inverse` | `#231e19` | Invertierter Text |
| `--color-accent` | `#eba44a` | Akzentfarbe (Amber) |
| `--color-accent-hover` | `#f2ba62` | Akzent-Hover |
| `--color-accent-subtle` | `#3d2e14` | Akzent-Hintergrund |
| `--color-success` | `#6cc66c` | Erfolg (Grün) |
| `--color-warning` | `#eba44a` | Warnung (Amber) |
| `--color-danger` | `#e07272` | Fehler (Rot) |
| `--color-success-subtle` | `#1e3320` | Erfolg-Hintergrund |
| `--color-warning-subtle` | `#3d2e14` | Warnung-Hintergrund |
| `--color-danger-subtle` | `#3d1f1f` | Fehler-Hintergrund |
| `--color-sidebar` | `#1a1614` | Sidebar-Hintergrund |
| `--color-sidebar-border` | `#342b23` | Sidebar-Rahmen |
| `--color-sidebar-hover` | `#252019` | Sidebar-Hover |
| `--color-sidebar-active` | `#332b22` | Sidebar-Aktiv |
| `--color-sidebar-text` | `#f7f3ee` | Sidebar-Text |
| `--color-sidebar-text-muted` | `#a6917f` | Sidebar-Text (gedämpft) |
| `--color-assistant` | `#1c1812` | Assistenten-Hintergrund |
| `--color-assistant-raised` | `#252019` | Assistenten-Card-Hintergrund |
| `--color-assistant-border` | `#3d3020` | Assistenten-Rahmen |

**Light Theme:** Warmes Farbschema mit `#fff8f3`-Hintergrund, `#3a2e22`-Text und `#f7bd54`-Akzentfarbe.

### Tailwind-Konfiguration (v4, Inline)

Kein `tailwind.config.js` — die Konfiguration erfolgt über `@theme inline` und `@custom-variant` direkt in `src/index.css` (Tailwind CSS v4).

- **Themes:** Farben sind CSS-Variablen (`src/theme/tokens.ts`), die Werte je Theme stehen in der Registry `src/theme/themes.ts` (`{ id, name, appearance, tokens }`). `themes.test.ts` prüft für jedes Theme die WCAG-Kontraste (Text, Akzent-/Statusfarben als Text, `text-inverse` auf `bg-accent`) — neue Themes müssen ihn bestehen. Auf `bg-accent` immer `text-text-inverse` statt `text-white`.
- **Dark Mode:** `class`-basiert via `@custom-variant dark (&:is(.dark *))`; die `.dark`-Klasse folgt der `appearance` des aktiven Themes (ebenso Monaco und xterm via `useThemeAppearance()`)
- **Plugin:** `@tailwindcss/typography` via `@plugin`
- **Schriftarten:**
  - Sans: Plus Jakarta Sans (System-UI Fallback)
  - Mono: JetBrains Mono (Fira Code Fallback)
- **Custom-Animationen:** `fade-in`, `slide-in`, `slide-up`, `pulse-subtle`, `slide-in-right`

### CSS-Utility-Klassen (`index.css`)

| Klasse | Beschreibung |
|---|---|
| `.glass` | Glasmorphism-Effekt (Blur + halbtransparent) |
| `.focus-ring` | Focus-State mit Akzent-Ring |
| `.input-base` | Standard-Input-Styling |
| `.btn-base` | Standard-Button-Styling |
| `.text-balance` | Ausgeglichener Textumbruch |
| `.animate-stagger` | Gestaffelte Animation (50ms pro Kind) |

## State Management

### Zustand-Stores (`src/stores/`)

| Store | State | Persistenz |
|---|---|---|
| `useSidebarStore` | `collapsed`, `mobileOpen`, `toggle()`, `setCollapsed()`, `setMobileOpen()` | `localStorage: sidebar-state` (nur `collapsed`) |
| `useThemeStore` | `themeId`, `lastLightThemeId`, `lastDarkThemeId`, `overrides` (je Theme-ID), `setTheme()`, `toggleAppearance()`, `setOverride()`, `resetOverrides()`, `apply()` | `localStorage: theme-settings` (Version 1; migriert das alte `mode`-Format) |
| `useSettingsStore` | `apiKey`, `advancedMode`, `simpleMode`, `autoTodoDefault`, `autoRepairDefault`, `autoRepairMode`, `defaultAiProvider`, `assistantModel`, `suggestionsModel`, `suggestionsEnabled`, `sidebarItemOrder`, `sidebarHiddenItems`, `pinnedWorkflowIds`, `simpleOnboardingSeen` | `localStorage: app-settings` |
| `useDashboardLayoutStore` | `activeViewId`, `views[]` (je Ansicht `widgets[]` als `WidgetInstance`, optional `filter { projectId?, days? }`; leere Liste = Standard-Layout), `setActiveViewWidgets()`, `resetActiveView()` | `localStorage: dashboard-layout` (Version 2; migriert v1, beim Laden per `sanitizeLayout` validiert) |
| `useAssistantStore` | `open`, `sessions[]`, `activeSessionId`, `showHistory`, `panelWidth`, `panelHeight`, `pendingInput`, `pendingContext` | `localStorage: assistant-sessions` (Sessions, Panel-Größe) |
| `useI18nStore` | `locale: 'en'\|'de'`, `setLocale()` | `localStorage: i18n-locale` |
| `useToastStore` | `toasts[]`, `addToast()`, `removeToast()` | Kein (flüchtig) |

`useI18nStore` ist in `src/i18n/index.ts` definiert (nicht in `src/stores/`).

### TanStack Query (Server State)

Alle API-Daten werden über React Query verwaltet:

- **Caching:** Automatisch mit Query-Keys
- **Invalidierung:** Nach Mutations werden zugehörige Queries invalidiert
- **Optimistic Updates:** Bei Drag-and-Drop-Sortierung der Blöcke
- **Auto-Refetch:** Health-Check alle 30 Sekunden

## API-Hooks (`src/hooks/`)

### `useProjects.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useProjects()` | Query | Alle Projekte |
| `useProject(id)` | Query | Einzelnes Projekt |
| `useCreateProject()` | Mutation | Projekt erstellen |
| `useUpdateProject()` | Mutation | Projekt aktualisieren |
| `useArchiveProject()` | Mutation | Projekt archivieren (Soft-Delete) |
| `useRestoreProject()` | Mutation | Archiviertes Projekt wiederherstellen |
| `useArchivedProjects()` | Query | Archivierte Projekte |
| `useStartContainer(id)` | Mutation | Container starten |
| `useStopContainer(id)` | Mutation | Container stoppen |
| `useGenerateProjectContext()` | Mutation | AI-Kontext für Projekt generieren |
| `useOnboardingStatus(id)` | Query | Onboarding-Status eines Projekts |
| `useEnableOnboarding()` | Mutation | Onboarding aktivieren |
| `useDisableOnboarding()` | Mutation | Onboarding deaktivieren |
| `useRefreshOnboarding()` | Mutation | Onboarding aktualisieren |
| `useOnboardingPreview(id)` | Query | Onboarding-Vorschau |

### `useWorkflows.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWorkflows()` | Query | Alle Workflows |
| `useWorkflow(id)` | Query | Einzelner Workflow |
| `useWorkflowRuns(workflowId)` | Query | Runs eines Workflows (paginiert, Auto-Refetch) |
| `useSimilarWorkflows(id)` | Query | Ähnliche Workflows finden |
| `useAllRuns(page, size, filters)` | Query | Alle Runs (paginiert) |
| `useCreateWorkflow()` | Mutation | Workflow erstellen |
| `useUpdateWorkflow()` | Mutation | Workflow aktualisieren |
| `useDeleteWorkflow()` | Mutation | Workflow löschen (Soft-Delete) |
| `useExecuteWorkflow()` | Mutation | Workflow ausführen |
| `usePauseExecution(runId)` | Mutation | Ausführung pausieren |
| `useResumeExecution(runId)` | Mutation | Ausführung fortsetzen |
| `useResumeFromStep()` | Mutation | Ab bestimmtem Step fortsetzen |
| `useCancelExecution(runId)` | Mutation | Ausführung abbrechen |
| `useRetryExecution()` | Mutation | Fehlgeschlagene Ausführung wiederholen |
| `useRunCommand()` | Mutation | Befehl an laufende Ausführung senden |
| `useSubmitApproval()` | Mutation | Approval-Antwort senden |
| `useApproveTodos()` | Mutation | TODOs für Ausführung genehmigen |
| `useImportWorkflow()` | Mutation | Workflow aus JSON importieren |
| `useOptimizeWorkflow()` | Mutation | Workflow per AI optimieren |
| `useRepairWorkflow()` | Mutation | Fehlgeschlagenen Workflow reparieren |
| `useUpdateRunNotes()` | Mutation | Notizen an Run aktualisieren |

### `useSkills.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useSkills()` | Query | Alle Skills |
| `useSkill(id)` | Query | Einzelner Skill |
| `useSkillCapabilities(skillId)` | Query | Capabilities eines Skills |
| `useActivateSkills()` | Mutation | Skills für Workflow aktivieren |
| `useDeactivateSkill()` | Mutation | Skill deaktivieren |

### `useSchedules.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useSchedules()` | Query | Alle Schedules |
| `useMaintenanceMode()` | Query | Maintenance-Mode-Status |
| `useSetMaintenanceMode()` | Mutation | Maintenance-Mode umschalten |
| `useCalendarData(start, end)` | Query | Kalender-Daten für Zeitraum |
| `useRemainingToday()` | Query | Verbleibende Runs heute |
| `useRemainingThisWeek()` | Query | Verbleibende Runs diese Woche |
| `useCronPreview(expr)` | Query | Vorschau nächster Ausführungen |
| `useScheduleRuns(id)` | Query | Runs eines Schedules |
| `useCreateSchedule()` | Mutation | Schedule erstellen |
| `useUpdateSchedule()` | Mutation | Schedule aktualisieren |
| `useToggleSchedule()` | Mutation | Schedule ein-/ausschalten |
| `useDeleteSchedule()` | Mutation | Schedule löschen |

### `useWebhooks.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWebhooks()` | Query | Alle Webhooks |
| `useCreateWebhook()` | Mutation | Webhook erstellen |
| `useToggleWebhook()` | Mutation | Webhook ein-/ausschalten |
| `useDeleteWebhook()` | Mutation | Webhook löschen |

### `useSystem.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useSystemHealth()` | Query (30s) | Health-Check |
| `useSystemInfo()` | Query | Systeminfos |
| `useRemoteAccessStatus()` | Query | Tailscale-Status |
| `useEnableRemoteAccess()` | Mutation | Tailscale aktivieren |
| `useDisableRemoteAccess()` | Mutation | Tailscale deaktivieren |
| `useBackupList()` | Query | Datenbank-Backups |
| `useAiProviders()` | Query | AI-Provider und Default |
| `usePiCatalog()` | Query | Pi-Provider-Katalog |
| `useUpdateSystemSetting()` | Mutation | System-Einstellung ändern |
| `useAssistantModelSetting()` | Query | Assistenten-Modell-Einstellung |
| `useAutoRepairModeSetting()` | Query | Auto-Repair-Modus-Einstellung |
| `useSuggestionsModelSetting()` | Query | Suggestions-Modell-Einstellung |
| `useAssistantThinkingPhrases()` | Query | Lokalisierte Thinking-Phrasen |
| `useRepairAllFailed()` | Mutation | Alle fehlgeschlagenen Runs reparieren |
| `useExecutionConfig()` | Query | Ausführungs-Konfiguration |
| `useUpdateExecutionConfig()` | Mutation | Ausführungs-Konfiguration ändern |
| `useBrowseDirectory()` | Query | Server-Dateisystem durchsuchen |

### `useChains.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useChains()` | Query | Alle Chains |
| `useChain(id)` | Query | Einzelne Chain |
| `useChainRuns(chainId)` | Query | Runs einer Chain |
| `useChainRun(chainId, runId)` | Query | Einzelner Chain-Run |
| `useAllChainRuns(page, size, status)` | Query | Alle Chain-Runs (paginiert) |
| `useCreateChain()` | Mutation | Chain erstellen |
| `useUpdateChain()` | Mutation | Chain aktualisieren |
| `useDeleteChain()` | Mutation | Chain löschen |
| `useExecuteChain()` | Mutation | Chain ausführen |
| `useCancelChainRun()` | Mutation | Chain-Run abbrechen |

### `useTodos.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTodos(filters)` | Query | TODOs mit Filtern (paginiert, Auto-Refetch) |
| `useTodo(id)` | Query | Einzelnes TODO |
| `useTodoCount()` | Query | Anzahl TODOs nach Status |
| `useProjectActiveTodos(projectId)` | Query | Aktive TODOs eines Projekts |
| `useCreateTodo()` | Mutation | TODO erstellen |
| `useUpdateTodo()` | Mutation | TODO aktualisieren |
| `useUpdateTodoStatus()` | Mutation | Status ändern |
| `useBulkUpdateTodoStatus()` | Mutation | Mehrere TODOs gleichzeitig ändern |
| `useArchiveStale()` | Mutation | Veraltete TODOs archivieren |
| `useDeleteTodo()` | Mutation | TODO löschen |
| `useExportTodo()` | Mutation | TODO in externen Tracker exportieren |
| `useResolveTodo()` | Mutation | AI-Auflösung für TODO starten |

### `useActivityLog.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useActivityLog(page, size, filters)` | Query | Activity-Log-Einträge (paginiert) |

### `useWorkflowMemory.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWorkflowMemories(workflowId)` | Query | Memory-Einträge eines Workflows |
| `useExtractMemory()` | Mutation | Wissen aus Runs extrahieren |
| `useActivateMemory()` | Mutation | Memory-Eintrag aktivieren |
| `useUpdateMemory()` | Mutation | Memory-Eintrag bearbeiten |
| `useDeleteMemory()` | Mutation | Memory-Eintrag löschen |

### `useStaging.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useStagingStatus()` | Query | Stable-Status + Version |
| `useStagingStart()` | Mutation | Stable starten |
| `useStagingStop()` | Mutation | Stable stoppen |
| `useStagingPromote()` | Mutation | Promote ausführen |
| `useStagingCancel()` | Mutation | Laufende Promotion abbrechen |
| `useStagingSyncPreview(enabled)` | Query | Sync-Vorschau |
| `useStagingSync()` | Mutation | Definitionen synchronisieren |

### `useTesting.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTestingStatus()` | Query | Test-Instanz-Status |
| `useTestingStart()` | Mutation | Test-Instanz starten |
| `useTestingStop()` | Mutation | Test-Instanz stoppen |
| `useTestingReset()` | Mutation | Test-Datenbank zurücksetzen |
| `useTestingSyncPreview(enabled)` | Query | Sync-Vorschau für Test-Instanz |
| `useTestingSync()` | Mutation | Definitionen zu Test synchronisieren |
| `useTestingPullChanges()` | Mutation | Änderungen von Test nach Dev ziehen |

### `useWishes.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWishes()` | Query | Alle Wishes |
| `useWish(id)` | Query | Einzelner Wish |
| `useCreateWish()` | Mutation | Wish erstellen |
| `useCancelWish()` | Mutation | Wish abbrechen |
| `useRespondToWish()` | Mutation | Feedback geben |
| `useCrystallizeWish()` | Mutation | Wish kristallisieren |
| `useDeleteWish()` | Mutation | Wish löschen |

### `useDocs.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useDocsList()` | Query | Verfügbare Dokumente |
| `useGroupedDocs()` | Query (Wrapper) | Dokumente gruppiert nach Label mit DE/EN-Varianten |
| `useDocContent(name, lang)` | Query | Inhalt eines Dokuments (sprachspezifisch) |

### `usePool.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `usePoolWorkflows()` | Query | Pool-Workflows |
| `useInstallPoolWorkflow()` | Mutation | Workflow aus Pool installieren |
| `usePoolExport()` | Mutation | Workflow in den Pool exportieren |

### `useAssistant.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useAssistantChat()` | Mutation | Chat-Nachricht an den Assistenten senden (Abort-Support) |
| `useConfirmPendingTool()` | Mutation | Pending-Tool-Aktion bestätigen |
| `useRejectPendingTool()` | Mutation | Pending-Tool-Aktion ablehnen |
| `useAssistantSuggestions(page)` | Query | Seitenspezifische Vorschläge laden |
| `useAssistantSessions(enabled)` | Query | Session-Zusammenfassungen laden |
| `useAssistantSessionDetail(sessionId)` | Query | Vollständige Session-Details |
| `useDeleteAssistantSession()` | Mutation | Session löschen |

### `useChainTemplates.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useChainTemplates()` | Query | Verfügbare Chain-Templates |
| `useInstallChainTemplate()` | Mutation | Chain-Template installieren |

### `useCompletionTriggers.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useCompletionTriggersAsSource(workflowId)` | Query | Trigger, bei denen dieser Workflow Quelle ist |
| `useCompletionTriggersAsTarget(workflowId)` | Query | Trigger, bei denen dieser Workflow Ziel ist |
| `useCreateCompletionTrigger()` | Mutation | Trigger erstellen |
| `useToggleCompletionTrigger(workflowId)` | Mutation | Trigger ein-/ausschalten |
| `useDeleteCompletionTrigger(workflowId)` | Mutation | Trigger löschen |

### `useTodoTriggers.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTodoTriggers()` | Query | Alle TodoTrigger |
| `useCreateTodoTrigger()` | Mutation | TodoTrigger erstellen |
| `useToggleTodoTrigger()` | Mutation | TodoTrigger aktivieren/deaktivieren |
| `useDeleteTodoTrigger()` | Mutation | TodoTrigger löschen |

### `useDialog.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useDialogSessionCounts()` | Query | Session-Anzahl pro Projekt |
| `useDialogSessions(projectPath?, limit)` | Query | Claude-Sessions (optional nach Projekt gefiltert) |
| `useDialogProjects()` | Query | Projektpfade mit Dialog-Sessions |
| `useAnalyzeDialog()` | Mutation | Session analysieren für Workflow-Vorschläge |
| `useCreateWorkflowFromDialog()` | Mutation | Workflow aus Dialog-Analyse erstellen |

### `useInsights.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useRunInsights(runId)` | Query | AI-generierte Insights für einen spezifischen Run |

### `useNotifications.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useNotifications()` | Query | Alle Notification-Konfigurationen |
| `useCreateNotification()` | Mutation | Notification erstellen |
| `useToggleNotification()` | Mutation | Notification ein-/ausschalten |
| `useDeleteNotification()` | Mutation | Notification löschen |

### `usePersistedState.ts`

Generischer Hook für persistierten State via `localStorage`.

### `useWorkflowFactory.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useFactoryTopics()` | Query | Factory-Topic-Kategorien |
| `useFactoryGenerate()` | Mutation | Workflow aus Topic generieren |
| `useFactoryRun()` | Mutation | Factory-Request End-to-End ausführen |
| `useFactoryVerify()` | Mutation | Factory-Workflow verifizieren |
| `useFactoryPromote()` | Mutation | Factory-Workflow promoten |

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

Verbindet sich über SockJS/STOMP mit dem Backend und empfängt Live-Logs, Approval-Prompts, Human-Input-Prompts, Agentic-States und ForEach-States. Mit `liveOutput` (Default `true`) kommen `live_output`-Batches dazu; beim (Re-)Connect lädt der Hook den Puffer über `GET /executions/{id}/live-log` nach und dedupliziert über `bufferId` + `seq` (Lücken werden als Marker angezeigt). Die Abbildung Nachricht → Eintrag liegt rein funktional in `hooks/executionLogEntries.ts`.

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

WebSocket-Verbindung für Echtzeit-Status-Updates und Push-Events des Assistenten.

### `useIntegrations.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useIntegrations()` | Query | Alle Provider mit Verbindungsstatus |
| `useIntegration(providerId)` | Query | Einzelner Integration-Provider |
| `useTaskTargets(providerId, enabled)` | Query | Task-Export-Ziele eines Providers |
| `useConnectIntegration()` | Mutation | OAuth-Verbindung herstellen |
| `useDisconnectIntegration()` | Mutation | OAuth-Verbindung trennen |
| `useSaveCredentials()` | Mutation | OAuth-Credentials speichern |
| `useDeleteCredentials()` | Mutation | Credentials löschen |
| `useTestIntegrationAction()` | Mutation | Provider-Aktion testen |

### `useWorkflowVersions.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWorkflowVersions(id)` | Query | Versionshistorie eines Workflows |
| `useRestoreWorkflowVersion()` | Mutation | Version wiederherstellen |

### `useOnboardingProfiles.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useOnboardingProfiles()` | Query | Alle Onboarding-Profile |
| `useOnboardingProfile(id)` | Query | Einzelnes Profil |
| `useCreateOnboardingProfile()` | Mutation | Profil erstellen |
| `useUpdateOnboardingProfile()` | Mutation | Profil aktualisieren |
| `useDeleteOnboardingProfile()` | Mutation | Profil löschen |
| `useResetOnboardingProfile()` | Mutation | Profil auf Built-in-Defaults zurücksetzen |
| `useOnboardingProfileHistory(profileId)` | Query | Versionshistorie eines Profils |

### `useCalendarAggregated.ts`

Aggregiert Events aus mehreren Quellen (Google Calendar, Trello, Taskwarrior, Schedules, Runs) über das Calendar-Adapter-Pattern. Gibt `{ events, sources, isLoading, setSourceColor }` zurück.

### `useCalendarColors.ts`

Verwaltet benutzerdefinierte Farben pro Kalender-Quelle (persistiert in `localStorage`).

### `useConversion.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useConversionSuggestion(runId, enabled)` | Query | AI-generierter Workflow-Vorschlag aus einem Run |
| `useCreateWorkflowFromSuggestion()` | Mutation | Workflow aus Konvertierungs-Vorschlag erstellen |

### `useLearning.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useDashboard(days, projectId?)` | Query | Lern-Dashboard-Daten (Auto-Poll 30s) |
| `usePlatformStats()` | Query | Plattform-weite Statistiken |
| `useRateRun()` | Mutation | Workflow-Run bewerten |

### `useReports.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useReportsList()` | Query | Liste verfügbarer Reports |
| `useReportContent(name)` | Query | Inhalt eines spezifischen Reports |

### `useTemplates.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTemplates()` | Query | Alle Workflow-Templates |
| `useCreateTemplate()` | Mutation | Workflow-Template erstellen |
| `useSaveWorkflowAsTemplate()` | Mutation | Bestehenden Workflow als Template speichern |

### `useDeadLetters.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useDeadLetters(status, page, size)` | Query | Paginierte Dead-Letter-Queue-Einträge |
| `useRetryDeadLetter()` | Mutation | Dead-Letter-Eintrag erneut versuchen |
| `useDiscardDeadLetter()` | Mutation | Dead-Letter-Eintrag verwerfen |

### `useLatestRunStatuses.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useLatestRunStatuses(size)` | Hook | Map von Workflow-ID → letzter Run-Status (abgeleitet aus `useAllRuns`) |

### `usePaletteHistory.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `usePaletteHistory(contextId)` | Hook | Recent/Pinned-IDs für die Command-Palette (localStorage-basiert) |

### `useTodoOverview.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTodoOverview()` | Query | Projektübergreifende Cockpit-Übersicht mit per-Projekt-Rollups |

### `useWorkflowDebug.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useBrokenWorkflows()` | Query | Workflows mit fehlgeschlagenem letztem Run (Auto-Poll 30s) |

### `useIsMobile.ts`

Responsive-Hook, erkennt Mobile-Breakpoint (max-width: 1023px) für adaptive UI-Anpassungen.

## Internationalisierung (i18n)

- **Sprachen:** Deutsch (`de`), Englisch (`en`)
- **Dateien:** `src/i18n/de.ts`, `src/i18n/en.ts`
- **Hook:** `useT()` → gibt das aktuelle Übersetzungsobjekt zurück
- **Store:** `useI18nStore()` → `locale`, `setLocale()`
- **Umschalten:** Button im Header

### Übersetzungsstruktur

~1.250 Leaf-Strings in 34+ Top-Level-Keys (Auszug):

```
common (~46 Keys) ──── Allgemeine Labels (Erstellen, Löschen, Suchen, ...)
nav (~27 Keys) ──────── Navigation (Dashboard, Projekte, ...)
simple (~95 Keys) ───── Simple-Mode-Texte (Home, Workflows, Runs, ...)
dashboard (~26 Keys) ── Dashboard-Texte
projects (~52 Keys) ─── Projekte + Onboarding, Typen, Status
workflows (~155 Keys)   Workflows + Block-Typen, Ausführung, Gate, Agentic
skills (~13 Keys) ───── Skills + Kategorien
schedules (~55 Keys) ── Zeitpläne + Wochentage
webhooks (8 Keys) ───── Webhooks
integrations (~40 Keys) Integrations-Provider
calendar (~8 Keys) ──── Kalender + Quellen
notifications (~16 Keys) Benachrichtigungen
todoTriggers (~17 Keys) Todo-Trigger
completionTriggers (~15 Keys) Completion-Trigger
settings (~150 Keys) ── Einstellungen (General, Execution, Staging, Testing)
toast (~60 Keys) ────── Toast-Nachrichten
templates (~14 Keys) ── Workflow-Templates
pool (7 Keys) ───────── Workflow-Pool
insights (~75 Keys) ─── Plattform-Insights
runs (~16 Keys) ──────── Run-Übersicht
activityLog (~12 Keys)  Activity-Log
todos (~38 Keys) ────── Todos + Status, Prioritäten, Quellen
wishes (~55 Keys) ───── Wishes + Status, Iteration
chains (~27 Keys) ───── Chains + Ausführung
factory (~40 Keys) ──── Workflow-Factory
assistant (~22 Keys) ── Assistent
commandPalette (~28 Keys) Command-Palette
charts (~10 Keys) ───── Chart-Labels
```

## PWA-Konfiguration

Konfiguriert über `vite-plugin-pwa` in `vite.config.ts`:

- **Name:** MySuricates
- **Kurzname:** MySuricates
- **Display:** Standalone
- **Theme-Color:** `#231e19`
- **Service Worker:** Auto-Update
- **Icon:** `/icons/icon.svg` (SVG, maskierbar)

## API-Client (`src/api/client.ts`)

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

Fehler werden als `ApiError` geworfen mit `{ error, timestamp, fieldErrors? }`. Requests haben ein 300-Sekunden-Timeout via `AbortController`.

Standalone-Exports: `openPath(path)` öffnet einen Dateipfad via Backend, `apiErrorMessage(err, fallback)` extrahiert Fehlermeldungen aus API-Responses.

Der API-Key wird aus dem `settingsStore` gelesen und automatisch an alle Requests angehängt.

## Proxy-Konfiguration (Vite)

In `vite.config.ts`:

| Pfad | Ziel | Beschreibung |
|---|---|---|
| `/api` | `http://localhost:9090` | REST-API |
| `/ws` | `ws://localhost:9090` | WebSocket (STOMP/SockJS) |

Der Dev-Server läuft auf Port 3000 (`strictPort`) und proxied alle API-Anfragen an das Backend auf Port 9090. Via `CYPRESS_TARGET=test` werden die Proxy-Ziele auf Port 8090 umgeleitet. HTTPS wird automatisch aktiviert wenn `../certs/dev.crt` und `../certs/dev.key` existieren.
