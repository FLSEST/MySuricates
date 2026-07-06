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
| `/calendar` | Calendar | Aggregierter Kalender (Schedules, Runs, Integrationen) |
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

- 4 Stat-Cards: Aktive Projekte, Workflows, Runs heute, Erfolgsrate
- 2 Recharts-Diagramme: Runs-Verlauf (AreaChart), Erfolgsrate (AreaChart)
- Liste der letzten Runs (klickbar → Execution-Seite)
- Top-Workflows nach Ausführungsanzahl

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
- Darstellung: Theme-Toggle (Light/Dark), Farbauswahl
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
| `LogViewer` | Log-Anzeige mit Level-Farben und Auto-Scroll |
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
| `TerminalPanel` | Terminal-ähnliche Ausgabeansicht |
| `TodoEditModal` | Modal zum Bearbeiten von TODOs |
| `ErrorBoundary` | React Error Boundary mit Fallback-UI |

## UI-Komponentenbibliothek

Alle Komponenten in `src/components/ui/`:

| Komponente | Props | Beschreibung |
|---|---|---|
| `Button` | `variant` (primary/secondary/ghost/danger), `size` (sm/md/lg), `loading`, `icon` | Standard-Button mit Loading-State |
| `Card` | `padding`, `className` | Container mit Border und Hintergrund |
| `CardHeader` | `title`, `subtitle`, `action` | Card-Header mit optionaler Action |
| `Modal` | `open`, `onClose`, `title`, `footer`, `size` (sm/md/lg) | Overlay-Modal mit Backdrop-Blur |
| `Badge` | `variant` (default/success/warning/danger/accent), `dot` | Status-Badge mit optionalem Punkt |
| `Input` | `label`, `error`, `hint`, `icon` + HTML-Attribute | Formular-Input mit Validierung |
| `Select` | `label`, `options`, `error` | Dropdown-Auswahl |
| `Toggle` | `enabled`, `onChange`, `label`, `disabled` | Switch-Element |
| `Spinner` | `size` (sm/md/lg), `text` | Lade-Indikator |
| `Table<T>` | `columns`, `data`, `keyExtractor`, `onRowClick`, `emptyText` | Generische Tabelle |
| `ToastContainer` | (global) | Toast-Benachrichtigungen (max. 5) |
| `DirectoryPicker` | `value`, `onChange`, `label` | Server-Dateisystem-Browser |
| `CronPicker` | `value`, `onChange` | Cron-Ausdruck-Editor mit Vorschau |
| `TagInput` | `tags`, `onChange`, `suggestions` | Tag-Eingabe mit Autovervollständigung |
| `ModalFooter` | `onCancel`, `onConfirm`, `loading` | Standardisierter Modal-Footer |

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
| `--color-sidebar` | `#1a1614` | Sidebar-Hintergrund |
| `--color-assistant` | `#1c1812` | Assistenten-Hintergrund |

**Light Theme:** Warmes Farbschema mit `#fff8f3`-Hintergrund, `#3a2e22`-Text und `#f7bd54`-Akzentfarbe.

### Tailwind-Konfiguration

- **Dark Mode:** `class`-basiert (Toggle über Zustand-Store)
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
| `useThemeStore` | `mode: 'light'\|'dark'`, `overrides`, `toggleMode()`, `setOverride()`, `resetOverrides()`, `apply()` | `localStorage: theme-settings` |
| `useSettingsStore` | `apiKey`, `advancedMode`, `simpleMode`, `autoTodoDefault`, `autoRepairDefault`, `autoRepairMode`, `defaultAiProvider`, `assistantModel`, `suggestionsModel`, `suggestionsEnabled`, `sidebarItemOrder`, `sidebarHiddenItems`, `pinnedWorkflowIds`, `simpleOnboardingSeen` | `localStorage: app-settings` |
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
| `useSyncPreview()` | Query | Sync-Vorschau |
| `useSyncDefinitions()` | Mutation | Definitionen synchronisieren |

### `useTesting.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTestingStatus()` | Query | Test-Instanz-Status |
| `useTestingStart()` | Mutation | Test-Instanz starten |
| `useTestingStop()` | Mutation | Test-Instanz stoppen |
| `useTestingReset()` | Mutation | Test-Datenbank zurücksetzen |
| `useTestingSyncDefinitions()` | Mutation | Definitionen zu Test synchronisieren |

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
| `useDoc(name)` | Query | Inhalt eines Dokuments |

### `usePool.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `usePool()` | Query | Pool-Workflows |
| `useInstallPoolWorkflow()` | Mutation | Workflow aus Pool installieren |

### `useAssistant.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useAssistantChat()` | Mutation | Chat-Nachricht an den Assistenten senden |
| `useAssistantSuggestions(page)` | Query | Seitenspezifische Vorschläge laden |
| `useRefreshSuggestions()` | Mutation | Vorschläge neu generieren |

### `useChainTemplates.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useChainTemplates()` | Query | Verfügbare Chain-Templates |

### `useCompletionTriggers.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useCompletionTriggers(workflowId)` | Query | CompletionTrigger eines Workflows |
| `useCreateCompletionTrigger()` | Mutation | Trigger erstellen |
| `useDeleteCompletionTrigger()` | Mutation | Trigger löschen |

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
| `useDialogSessions()` | Query | Verfügbare Claude-Sessions |
| `useImportDialog()` | Mutation | Session als Workflow importieren |

### `useInsights.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useInsights()` | Query | Plattform-Einblicke und Statistiken |

### `useNotifications.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useNotifications()` | Query | Benachrichtigungen laden |

### `usePersistedState.ts`

Generischer Hook für persistierten State via `localStorage`.

### `useWorkflowFactory.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useGenerateWorkflow()` | Mutation | Workflow aus Topic generieren |
| `useFactoryStatus()` | Query | Status der Factory-Pipeline |

### `useWebSocket.ts`

```typescript
useWebSocket(runId: string | null) → {
  logs: Array<{ timestamp, level, message }>,
  connected: boolean,
  clearLogs: () => void
}
```

Verbindet sich über SockJS/STOMP mit dem Backend und empfängt Live-Logs.

### `useToast.ts`

```typescript
useToast() → {
  success(message), error(message), info(message), warning(message)
}
```

### `useAssistantWebSocket.ts`

WebSocket-Verbindung für Echtzeit-Status-Updates des Assistenten (Typing-Indicator, Session-Updates).

### `useIntegrations.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useIntegrations()` | Query | Alle Provider mit Verbindungsstatus |
| `useConnectIntegration()` | Mutation | OAuth-Verbindung herstellen |
| `useDisconnectIntegration()` | Mutation | OAuth-Verbindung trennen |
| `useTestIntegration()` | Mutation | Provider-Verbindung testen |

### `useWorkflowVersions.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWorkflowVersions(id)` | Query | Versionshistorie eines Workflows |
| `useRestoreWorkflowVersion()` | Mutation | Version wiederherstellen |

### `useOnboardingProfiles.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useOnboardingProfiles()` | Query | Alle Onboarding-Profile |
| `useUpdateOnboardingProfile()` | Mutation | Profil aktualisieren |

### `useCalendarAggregated.ts`

Aggregiert Events aus mehreren Quellen (Google Calendar, Trello, Schedules, Runs) über das Calendar-Adapter-Pattern.

### `useCalendarColors.ts`

Verwaltet benutzerdefinierte Farben pro Kalender-Quelle (persistiert in `localStorage`).

### `useConversion.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useConversionSuggestions()` | Query | Vorschläge für Workflow-Konvertierungen |
| `useCreateConversion()` | Mutation | Agentic-Run in deterministischen Workflow konvertieren |

### `useLearning.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useLearningDashboard()` | Query | Lern-Dashboard-Daten |
| `useLearningStats()` | Query | Erfolgsraten und Statistiken |

### `useReports.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useReports()` | Query | Alle generierten Reports |

### `useTemplates.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useTemplates()` | Query | Alle Workflow-Templates |
| `useCreateFromTemplate()` | Mutation | Workflow aus Template erstellen |

### `useIsMobile.ts`

Responsive-Hook, erkennt Mobile-Breakpoint für adaptive UI-Anpassungen.

## Internationalisierung (i18n)

- **Sprachen:** Deutsch (`de`), Englisch (`en`)
- **Dateien:** `src/i18n/de.ts`, `src/i18n/en.ts`
- **Hook:** `useT()` → gibt das aktuelle Übersetzungsobjekt zurück
- **Store:** `useI18nStore()` → `locale`, `setLocale()`
- **Umschalten:** Button im Header

### Übersetzungsstruktur

```
common (24 Keys) ─── Allgemeine Labels (Erstellen, Löschen, Suchen, ...)
nav (7 Keys) ─────── Navigation (Dashboard, Projekte, ...)
dashboard (8 Keys) ─ Dashboard-Texte
projects (11 Keys) ─ Projekte + verschachtelte Typen & Status
workflows (18 Keys)  Workflows + Block-Typen & Ausführung
skills (10 Keys) ─── Skills + Kategorien
schedules (7 Keys) ─ Zeitpläne
webhooks (7 Keys) ── Webhooks
settings (16 Keys) ─ Einstellungen
toast (26 Keys) ──── Toast-Nachrichten
templates (13 Keys)  Workflow-Templates
charts (5 Keys) ──── Chart-Labels
```

## PWA-Konfiguration

Konfiguriert über `vite-plugin-pwa` in `vite.config.ts`:

- **Name:** MySuricates
- **Kurzname:** AutoPilot
- **Display:** Standalone
- **Theme-Color:** `#0f172a`
- **Service Worker:** Auto-Update
- **Icon:** `/icons/icon.svg` (SVG, maskierbar)

## API-Client (`src/api/client.ts`)

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

Fehler werden als `ApiError` geworfen mit `{ error, timestamp, fieldErrors? }`.

Der API-Key wird aus dem `settingsStore` gelesen und automatisch an alle Requests angehängt.

## Proxy-Konfiguration (Vite)

In `vite.config.ts`:

| Pfad | Ziel | Beschreibung |
|---|---|---|
| `/api/*` | `http://localhost:9090` | REST-API |
| `/ws/*` | `ws://localhost:9090` | WebSocket (STOMP/SockJS) |

Der Dev-Server läuft auf Port 3000 und proxied alle API-Anfragen an das Backend auf Port 9090.
