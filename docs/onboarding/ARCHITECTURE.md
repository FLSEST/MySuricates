# Architektur

Dieses Dokument beschreibt die Systemarchitektur, den Datenfluss und die zentralen Konzepte der AutoPilot-Plattform.

---

## Systemueberblick

```
+-------------------+     HTTP/WS      +--------------------+     Docker API     +------------------+
|                   | <-------------->  |                    | <--------------->  |                  |
|  React Frontend   |    /api/v1        | Spring Boot        |                    | Agent Container  |
|  (Port 3000)      |    /ws            | Backend            |                    | (Python/Node/Go) |
|                   |                   | (Port 9090)        |                    |                  |
+-------------------+                   +--------------------+                    +------------------+
                                              |         |
                                   SQLite     |         |    Claude CLI
                                   (Flyway)   |         |    (Host-Prozess)
                                              v         v
                                        +---------+  +----------+
                                        | .db     |  | claude   |
                                        | Datei   |  | (Host)   |
                                        +---------+  +----------+
```

### Kommunikationswege

1. **Frontend → Backend:** REST API (`/api/v1/*`) fuer CRUD, WebSocket (`/ws`) fuer Echtzeit-Updates
2. **Backend → SQLite:** JPA/Hibernate ueber JDBC (WAL-Modus, max. 3 Connections)
3. **Backend → Docker:** Docker Java Client ueber Unix Socket (`/var/run/docker.sock`)
4. **Backend → Claude CLI:** `ProcessBuilder` startet `claude` als Host-Prozess (stdin fuer Prompts)

---

## Backend-Architektur

### Schichtenmodell

```
Controller → Service → Repository → Entity (JPA/SQLite)
     ↕            ↕
  DTO/Mapper   Executor → BlockExecutor → HostClaudeService
                              ↕
                       ContainerService → Docker API
```

### Package-Struktur

| Package | Verantwortung |
|---|---|
| `controller/` | REST-Endpunkte, Request-Validierung, Response-Mapping |
| `entity/` | JPA-Entities, Enums, BlockDefinition (JSON-serialisiert) |
| `dto/request/` | Eingehende Request-Objekte (mit Validierung) |
| `dto/response/` | Ausgehende Response-Objekte (Projektion) |
| `mapper/` | MapStruct-Mapper: Entity ↔ DTO Konvertierung |
| `repository/` | Spring Data JPA Interfaces |
| `service/` | Business-Logik (41 Dateien in 12 Sub-Packages) |
| `executor/` | Workflow-Ausfuehrungsengine + Block-Executors |
| `config/` | Spring-Beans, Seeders, Built-in-Definitionen |
| `security/` | API-Key-Auth, Rate-Limiting, Tailscale-Integration |
| `websocket/` | WebSocket-Handler fuer Updates + Terminal |
| `event/` | Spring Application Events (async Verarbeitung) |
| `exception/` | Custom Exceptions + globaler Error-Handler |
| `util/` | JSON-Converter, Hilfsfunktionen |

### Controller-Uebersicht (13 REST-Controller)

| Controller | Pfad | Funktion |
|---|---|---|
| `ProjectController` | `/api/v1/projects` | Projekt-CRUD, Container-Start/Stop |
| `WorkflowController` | `/api/v1/workflows` | Workflow-CRUD, Ausfuehrung, Import/Export |
| `ExecutionController` | `/api/v1/executions` | Laufende Ausfuehrungen, Pause/Resume/Cancel |
| `SkillController` | `/api/v1/skills` | Skill-Katalog, Import |
| `ScheduleController` | `/api/v1/schedules` | Cron-Zeitplaene CRUD |
| `WebhookController` | `/api/v1/webhooks` | Webhook-Trigger CRUD + Ausloesung |
| `WorkflowTemplateController` | `/api/v1/workflow-templates` | Template-CRUD |
| `TodoController` | `/api/v1/todos` | Todo-CRUD |
| `ActivityLogController` | `/api/v1/activity-log` | Audit-Log abfragen |
| `ConversionController` | `/api/v1/conversions` | Agentisch → Deterministisch |
| `LearningController` | `/api/v1/learning` | Lern-Insights |
| `WorkflowMemoryController` | `/api/v1/workflow-memory` | Workflow-Konversationshistorie |
| `SystemController` | `/api/v1/system` | Health, Version, Metriken |

---

## Frontend-Architektur

### Komponentenhierarchie

```
App.tsx (Router)
  └── Layout (Sidebar + Header)
       ├── Dashboard         → QuickStats, RecentRuns, ExecutionInsights, Schedules, Todos
       ├── Projects          → Projektliste mit Container-Status
       ├── ProjectDetail     → Projekt-Workflows & Setup
       ├── Workflows         → Workflow-Liste + Template-Auswahl
       ├── WorkflowDetail    → Block-Editor (Drag-Drop) + Ausfuehrung
       ├── WorkflowExecution → Step-Timeline + Terminal
       ├── Runs              → Ausfuehrungshistorie mit Filtern
       ├── Skills            → Skill-Katalog
       ├── Schedules         → Cron-Zeitplaene
       ├── Webhooks          → Webhook-Konfiguration
       ├── Todos             → Todo-Liste
       ├── ActivityLog       → Audit-Trail
       └── Settings          → API-Key, Theme, Sprache
```

### State-Management

| Typ | Technologie | Verwendung |
|---|---|---|
| **Server-State** | TanStack Query | Alle API-Daten (Workflows, Projects, Skills, Runs...) |
| **Client-State** | Zustand | Settings, Theme, Sidebar, Toast-Notifications |

**TanStack Query Konfiguration:**
- `staleTime: 30s` — Daten gelten 30 Sekunden als frisch
- `retry: 1` — Ein Wiederholungsversuch bei Fehler
- `refetchOnWindowFocus: false` — Kein automatisches Neuladen bei Tab-Wechsel

**Zustand Stores:**
- `settingsStore` — API-Key, Advanced Mode (persistiert in localStorage)
- `themeStore` — Dark/Light Mode, System-Farben
- `sidebarStore` — Sidebar offen/geschlossen
- `toastStore` — Benachrichtigungs-Queue

### Routing (React Router v6)

| Pfad | Seite |
|---|---|
| `/` | Dashboard |
| `/projects` | Projektliste |
| `/projects/:id` | Projektdetail |
| `/workflows` | Workflowliste |
| `/workflows/:id` | Workflow-Editor |
| `/workflows/:id/run/:runId` | Ausfuehrungsansicht |
| `/runs` | Ausfuehrungshistorie |
| `/skills` | Skill-Katalog |
| `/schedules` | Zeitplaene |
| `/webhooks` | Webhooks |
| `/todos` | Todo-Liste |
| `/activity` | Aktivitaetslog |
| `/settings` | Einstellungen |

Alle Seiten werden per `lazy()` geladen (Code-Splitting).

---

## Datenmodell

### Entity-Beziehungen

```
Project (1) ←→ (1) Container
    |
    ↓ (1:N)
Workflow (N) ←→ (N) Skill      [via workflow_skills]
    |
    ↓ (1:N)
WorkflowRun
    |
    ↓ (1:N)
StepResult

ScheduledWorkflow → Workflow
WebhookTrigger → Workflow
WorkflowMemory → Workflow
WorkflowTemplate (unabhaengig)
Todo (unabhaengig)
ActivityLog (unabhaengig)
```

### Datenbank-Migrationen (Flyway, V1-V19)

| Migration | Tabelle | Beschreibung |
|---|---|---|
| V1 | `projects` | Projekte (Name, Typ, Pfad, Status) |
| V2 | `containers` | Docker-Container (Image, Status, Mounts) |
| V3 | `skills` | Skills (Name, Kategorie, AI-Kontext) |
| V4 | `capabilities` | Skill-Faehigkeiten |
| V5 | `workflows`, `workflow_skills` | Workflows + Skill-Zuordnung |
| V6 | `workflow_runs`, `workflow_steps` | Ausfuehrungen + Schritte |
| V7 | `scheduled_workflows` | Zeitgesteuerte Ausfuehrungen |
| V8 | `webhook_triggers` | HTTP-Trigger |
| V9 | `updated_at`-Spalten | Zeitstempel fuer alle Tabellen |
| V10 | Performance-Indizes | Datenbank-Optimierung |
| V11 | `container.last_activity` | Idle-Timeout Tracking |
| V12 | `app_settings` | Anwendungseinstellungen |
| V13 | `workflow.model`, `workflow.instructions` | KI-Modell & Instruktionen |
| V14 | `schedule.type`, `schedule.scheduled_at` | Erweiterte Zeitplanung |
| V15 | `activity_log` | Audit-Logging |
| V16 | `workflow_memory` | Konversationshistorie |
| V17 | `todos` | Todo-Verwaltung |
| V18 | `project.subtitle`, `project.description` | Projekt-Erweiterung |
| V19 | `workflow_templates` | Workflow-Vorlagen |

---

## Workflow-Ausfuehrung

### Ausfuehrungsfluss

```
1. Frontend: POST /api/v1/workflows/{id}/execute
2. WorkflowController → WorkflowExecutor.execute()
3. WorkflowRun wird mit Status RUNNING erstellt
4. Response wird sofort zurueckgegeben (async)
5. Virtual Thread fuehrt Bloecke sequenziell aus:
   ┌─────────────────────────────────┐
   │ for each BlockDefinition:       │
   │   1. BlockExecutor auswaehlen   │
   │   2. Block ausfuehren           │
   │   3. StepResult speichern       │
   │   4. WebSocket-Update senden    │
   │   5. previousOutput aktualisieren│
   └─────────────────────────────────┘
6. WorkflowRun Status → COMPLETED/FAILED
7. WorkflowRunCompletedEvent publizieren
```

### Block-Typen

| Typ | Executor | Beschreibung |
|---|---|---|
| `simple` | `SimpleBlockExecutor` | Claude CLI Aufruf mit Prompt |
| `shell` | `ShellBlockExecutor` | Shell-Befehl ausfuehren |
| `agentic` | `AgenticBlockExecutor` | Goal→Reason→Act Schleife (KI) |
| `conditional` | `ConditionalBlockExecutor` | If/Else Verzweigung |
| `foreach` | `ForEachBlockExecutor` | Iteration ueber Liste |
| `while` | `WhileBlockExecutor` | Bedingte Schleife |
| `retry` | `RetryBlockExecutor` | Wiederholung mit Backoff |
| `approval` | `ApprovalBlockExecutor` | Manueller Freigabe-Schritt |
| `sub-workflow` | `SubWorkflowBlockExecutor` | Verschachtelte Workflows |
| `skill-capability` | `SkillCapabilityBlockExecutor` | Skill-Faehigkeit aufrufen |

### Agentischer Block (Goal→Reason→Act)

Der `AgenticBlockExecutor` implementiert eine KI-gesteuerte Schleife:

```
Iteration 1..N:
  1. REASON: Claude analysiert Ziel + bisherige Ergebnisse
     → Gibt JSON mit {action, parameters, reasoning} zurueck
  2. ACT: Fuehrt die vorgeschlagene Aktion aus (Claude CLI)
     → Ergebnis wird als Beobachtung gespeichert
  3. CHECK: Ziel erreicht? → Wenn ja, beende Schleife
```

**Sicherheitslimits:**

| Limit | Standard | Beschreibung |
|---|---|---|
| `max_iterations` | 10 | Maximale Anzahl Iterationen |
| `iteration_timeout_seconds` | 300 | Timeout pro Iteration |
| `total_timeout_seconds` | 1800 | Gesamttimeout (30 Minuten) |
| `max_total_tokens` | 200000 | Token-Budget (kumulativ) |
| `max_repeated_actions` | 3 | Abbruch bei identischen Aktionen |
| `require_approval` | false | Freigabe vor jeder Aktion |

### ExecutionContext

Der `ExecutionContext` wird durch alle Bloecke weitergereicht:

```java
ExecutionContext {
  containerId      // Docker-Container ID
  workflowRunId    // Aktuelle Ausfuehrung
  projectPath      // Arbeitsverzeichnis
  previousOutput   // Ausgabe des letzten Blocks
  variables        // Template-Variablen {{ name }}
  stepCounter      // Schrittzaehler
  paused           // Pause-Status
  cancelled        // Abbruch-Status
  pendingApproval  // Wartende Freigabe
}
```

Template-Variablen werden mit `{{ variable_name }}` oder `{{variable_name}}` aufgeloest.

---

## KI-Integration (Claude CLI)

### HostClaudeService

Alle KI-Aufrufe laufen ueber den `HostClaudeService`, der die Claude CLI auf dem Host-System startet:

```
Backend → ProcessBuilder → claude (Host-Prozess)
```

**Wichtige Details:**
- Prompts werden ueber **stdin** geschrieben (nicht als CLI-Argument), um `E2BIG`-Fehler zu vermeiden
- `CLAUDE_CODE` und `CLAUDECODE` Umgebungsvariablen werden entfernt (verhindert Nested-Session-Erkennung)
- `--model` Flag fuer Modellauswahl (`claude-opus-4-6`, `claude-sonnet-4-6`, `claude-haiku-4-5`)
- `--max-tokens` Flag fuer optionales Token-Limit pro Aufruf
- Konfigurierbarer Timeout (Standard: 300 Sekunden)

### Zwei-Ebenen Instruktionssystem

1. **Basis-Instruktionen** (`agent-instructions.md`) — gelten fuer alle Workflows
2. **Workflow-spezifische Instruktionen** (`workflow.instructions`) — pro Workflow editierbar im Monaco-Editor

---

## WebSocket-Kommunikation

| Endpunkt | Zweck |
|---|---|
| `/ws` | Echtzeit-Updates fuer Workflow-Ausfuehrungen (STOMP) |
| `/ws/terminal` | Terminal-Zugriff auf Docker-Container (xterm.js) |

### WebSocket-Nachrichten

- **Step-Updates:** Status, Ausgabe, Fehler fuer jeden Ausfuehrungsschritt
- **Run-Updates:** Gesamtstatus der Ausfuehrung (RUNNING, COMPLETED, FAILED)
- **Approval-Requests:** Benachrichtigung ueber ausstehende Freigaben

---

## Security

| Feature | Konfiguration | Standard |
|---|---|---|
| API-Key Auth | `AUTOMATION_SECURITY_ENABLED` | `true` |
| Rate Limiting | `AUTOMATION_RATE_LIMIT_ENABLED` | `false` |
| Tailscale | `AUTOMATION_TAILSCALE_ENABLED` | `false` |
| Trusted Proxies | `AUTOMATION_TRUSTED_PROXIES` | `127.0.0.1,::1` |

Der API-Key wird im Header `X-API-Key` uebergeben. Im Frontend wird er im `settingsStore` (localStorage) gespeichert und automatisch bei jedem Request hinzugefuegt.

---

## Docker-Container Management

### Container-Lebenszyklus

```
CREATED → RUNNING → STOPPED
              ↑         |
              └─────────┘ (Restart)
```

- Container werden pro Projekt erstellt (1:1 Beziehung)
- Ressourcenlimits: 4 GB RAM, CPU-Quota, 100 PIDs
- Idle-Timeout: 60 Minuten (konfigurierbar)
- Docker Socket wird in den Backend-Container gemountet

### Agent-Container

| Dockerfile | Image | Zweck |
|---|---|---|
| `Dockerfile.agent-python` | `automation-agent-python:latest` | Python-basierte Skills |
| `Dockerfile.agent-node` | `automation-agent-node:latest` | Node.js-basierte Skills |
| `Dockerfile.agent-go` | `automation-agent-go:latest` | Go-basierte Skills |
| `Dockerfile.backend` | Backend-Image | Fuer Docker-basiertes Deployment |

Agent-Container werden NICHT ueber Docker Compose gestartet, sondern vom Backend dynamisch ueber die Docker API verwaltet.
