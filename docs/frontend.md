> *Zuletzt aktualisiert: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

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
| `/docs` | Docs | Markdown-Dokumentation (Split-Layout) |
| `/insights` | Insights | Plattform-Einblicke und Analysen |
| `/settings` | Settings | Sprache, Theme, API-Key, Tailscale, Staging, Testing |

Alle Routen sind in `src/App.tsx` definiert und von einem `<Layout>`-Wrapper umgeben (Sidebar + Header).

### Simple Mode

`useSettingsStore().simpleMode` schaltet vier Seiten zwischen Normal- und Simple-Variante um:

| Route | Normal | Simple |
|---|---|---|
| `/` | `Dashboard` | `SimpleDashboard` |
| `/workflows` | `Workflows` | `SimpleWorkflows` |
| `/workflows/:id` | `WorkflowDetail` | `SimpleWorkflowDetail` |
| `/workflows/:id/run/:runId` | `WorkflowExecution` | `SimpleWorkflowExecution` |

Die Umschaltung erfolgt über Route-Wrapper-Komponenten (z.B. `DashboardRoute`, `WorkflowsRoute`) in `App.tsx`, die je nach `simpleMode`-Flag die entsprechende Variante rendern.

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
- **Stable:** Versionsvergleich Dev/Stable, Sync-Preview, Sync-Definitions, Start/Stop/Promote
- **Testing:** Test-Instanz-Status, Start/Stop/Reset, Sync-Preview, Sync-Definitions
- System: Health-Status, Systeminfos, Docker-Status

## Komponenten

### `AssistantPanel.tsx` (`src/components/AssistantPanel.tsx`)

- Floating-Chat-Panel, global über dem Layout eingeblendet
- Kontext-sensitiver Chat mit Page Suggestions
- Persistierte Sessions, WebSocket-Integration
- Siehe [Assistant System](assistant-system.md) für Details

### `src/components/settings/`

- Settings-Unterkomponenten für die modulare Settings-Seite
- Separate Komponenten für Allgemein, Darstellung, Sicherheit, Stable, Testing, System

### Shared-Komponenten (`src/components/shared/`)

| Komponente | Beschreibung |
|---|---|
| `StatusBadge` | Mappt Status-Strings auf Badge-Varianten |
| `LogViewer` | Log-Anzeige mit Level-Farben und Auto-Scroll |
| `MonacoEditor` | JSON-Editor (Monaco, Dark Theme) |
| `ErrorState` | Fehlermeldung mit Icon |
| `EmptyState` | Leerer Zustand mit Beschreibung |

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

## Design-System

### CSS-Variablen

Das Theme wird über CSS Custom Properties gesteuert (`src/index.css`, `src/theme/tokens.ts`):

**Dark Theme (Standard):**

| Variable | Wert | Verwendung |
|---|---|---|
| `--color-surface` | `#0f172a` | Seiten-Hintergrund |
| `--color-surface-raised` | `#1e293b` | Cards, erhöhte Flächen |
| `--color-surface-overlay` | `#1e293b` | Modal-Hintergrund |
| `--color-border` | `#334155` | Standard-Rahmen |
| `--color-border-strong` | `#475569` | Betonte Rahmen |
| `--color-text` | `#f1f5f9` | Primärtext |
| `--color-text-secondary` | `#94a3b8` | Sekundärtext |
| `--color-text-tertiary` | `#64748b` | Tertiärtext |
| `--color-accent` | `#06b6d4` | Akzentfarbe (Cyan) |
| `--color-accent-hover` | `#22d3ee` | Akzent-Hover |
| `--color-accent-subtle` | `#083344` | Akzent-Hintergrund |
| `--color-success` | `#34d399` | Erfolg (Emerald) |
| `--color-warning` | `#fbbf24` | Warnung (Amber) |
| `--color-danger` | `#f87171` | Fehler (Rot) |
| `--color-sidebar` | `#020617` | Sidebar-Hintergrund |

**Light Theme:** Invertierte Varianten mit weißem Hintergrund und dunklem Text.

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
| `useSidebarStore` | `collapsed: boolean`, `toggle()`, `setCollapsed()` | `localStorage: sidebar-state` |
| `useThemeStore` | `mode: 'light'\|'dark'`, `overrides`, `toggleMode()`, `apply()` | `localStorage: theme-settings` |
| `useSettingsStore` | `apiKey: string`, `advancedMode: boolean`, `simpleMode: boolean` | `localStorage: app-settings` |
| `useI18nStore` | `locale: 'en'\|'de'`, `setLocale()` | `localStorage: i18n-locale` |
| `useToastStore` | `toasts[]`, `addToast()`, `removeToast()` | Kein (flüchtig) |

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
| `useDeleteProject()` | Mutation | Projekt löschen |
| `useStartContainer(id)` | Mutation | Container starten |
| `useStopContainer(id)` | Mutation | Container stoppen |

### `useWorkflows.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWorkflows()` | Query | Alle Workflows |
| `useWorkflow(id)` | Query | Einzelner Workflow |
| `useWorkflowRuns(workflowId)` | Query | Alle Runs eines Workflows |
| `useCreateWorkflow()` | Mutation | Workflow erstellen |
| `useUpdateWorkflow()` | Mutation | Workflow aktualisieren |
| `useDeleteWorkflow()` | Mutation | Workflow löschen |
| `useExecuteWorkflow()` | Mutation | Workflow ausführen |
| `usePauseExecution(runId)` | Mutation | Ausführung pausieren |
| `useResumeExecution(runId)` | Mutation | Ausführung fortsetzen |
| `useCancelExecution(runId)` | Mutation | Ausführung abbrechen |

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
| `useCreateSchedule()` | Mutation | Schedule erstellen |
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
| `useTodos(filters)` | Query | TODOs mit Filtern |
| `useTodo(id)` | Query | Einzelnes TODO |
| `useTodoCount()` | Query | Anzahl TODOs nach Status |
| `useCreateTodo()` | Mutation | TODO erstellen |
| `useUpdateTodo()` | Mutation | TODO aktualisieren |
| `useUpdateTodoStatus()` | Mutation | Status ändern |
| `useDeleteTodo()` | Mutation | TODO löschen |

### `useActivityLog.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useActivityLog(page, size, filters)` | Query | Activity-Log-Einträge (paginiert) |

### `useWorkflowMemory.ts`

| Hook | Typ | Beschreibung |
|---|---|---|
| `useWorkflowMemory(workflowId)` | Query | Memory-Einträge eines Workflows |
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
