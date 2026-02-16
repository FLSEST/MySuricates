# Architektur

## Systemübersicht

```
┌─────────────────────────────────────────────────────────┐
│                      Browser                            │
│  React 18 PWA (Vite Dev-Server :3000 / statisch)        │
└──────────┬──────────────────────┬───────────────────────┘
           │ REST /api/v1/*       │ WebSocket /ws
           │ (Vite Proxy)         │ (STOMP/SockJS)
           ▼                      ▼
┌─────────────────────────────────────────────────────────┐
│              Spring Boot Backend (:9090)                 │
│                                                         │
│  ┌─────────┐  ┌──────────┐  ┌────────────┐             │
│  │Controller│→ │ Service  │→ │ Repository │             │
│  └─────────┘  └──────────┘  └──────┬─────┘             │
│       │                            │                    │
│       │  ┌──────────────┐    ┌─────▼─────┐             │
│       └→ │ WebSocket    │    │  SQLite   │             │
│          │ (STOMP)      │    │ (WAL)     │             │
│          └──────────────┘    └───────────┘             │
│                                                         │
│  ┌──────────────────────────────────────┐               │
│  │         Docker Client API            │               │
│  └──────────────┬───────────────────────┘               │
└─────────────────┼───────────────────────────────────────┘
                  │ /var/run/docker.sock
                  ▼
┌─────────────────────────────────────────────────────────┐
│                  Docker Engine                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐              │
│  │agent-node│  │agent-py  │  │agent-go  │              │
│  │(Node 21) │  │(Python   │  │(Go 1.22) │              │
│  │          │  │ 3.12)    │  │          │              │
│  └──────────┘  └──────────┘  └──────────┘              │
└─────────────────────────────────────────────────────────┘
```

## Backend-Architektur

### Schichtenmodell

```
Controller (REST API)
    ↓ DTO (Request/Response Records)
Service (Geschäftslogik)
    ↓ Entity (JPA)
Repository (Spring Data JPA)
    ↓ SQL
SQLite (Datei: data/automation.db)
```

### Packages

```
com.automation
├── controller/          8 REST-Controller
├── service/
│   ├── project/         Projekte + Container-Lifecycle
│   ├── workflow/        Workflows + Ausführung
│   ├── skill/           Skills + Import/Export
│   ├── scheduler/       Cron-basierte Ausführung
│   ├── webhook/         Webhook-Trigger
│   ├── learning/        Dashboard-Analytics
│   └── system/          Tailscale Remote-Access
├── entity/              9 JPA-Entities + 6 Enums
├── dto/
│   ├── request/         8 Request-Records
│   └── response/        9 Response-Records
├── repository/          9 Spring Data Repositories
├── mapper/              MapStruct-Mapper (Entity ↔ DTO)
├── executor/            Workflow-Ausführungs-Engine
│   └── blocks/          6 Block-Typen (Simple, Retry, ForEach, While, Conditional, SkillCapability)
├── config/              8 Konfigurationsklassen
├── security/            ApiKeyAuthFilter, RateLimitFilter
├── exception/           GlobalExceptionHandler + 5 Exception-Klassen
└── util/                JSON-Converter, Hilfklassen
```

### Workflow-Ausführung

Die Workflow-Engine führt Blöcke sequenziell in einem Docker-Container aus:

1. `WorkflowExecutor` erstellt einen `WorkflowRun` (Status: PENDING → RUNNING)
2. Für jeden `BlockDefinition` wird der passende `BlockExecutor` ausgewählt
3. Der Block sendet Befehle via Docker Exec in den Container
4. Ergebnisse werden als `StepResult` gespeichert
5. Live-Logs werden über WebSocket (STOMP) an das Frontend gesendet

**Block-Typen:**

| Typ | Beschreibung |
|---|---|
| `simple` | Einzelner Befehl |
| `retry` | Befehl mit Wiederholungsversuchen |
| `foreach` | Iteration über eine Liste |
| `while` | Wiederholung mit Bedingung |
| `conditional` | If/Else-Verzweigung |
| `skill-capability` | Aufruf einer Skill-Fähigkeit |

Template-Variablen (`{{ variable }}`, `{{ previous_output }}`) werden zur Laufzeit im `ExecutionContext` aufgelöst.

## Frontend-Architektur

### Komponentenstruktur

```
App.tsx (Router)
├── Layout
│   ├── Sidebar (Navigation, Collapse)
│   └── Header (Sprache, Theme)
│
├── Pages (10 Routen)
│   ├── Dashboard, Projects, ProjectDetail
│   ├── Workflows, WorkflowDetail, WorkflowExecution
│   ├── Skills, Schedules, Webhooks, Settings
│
├── Hooks (React Query + Custom)
│   ├── useProjects, useWorkflows, useSkills, ...
│   ├── useExecutionWebSocket
│   └── useToast
│
├── Stores (Zustand)
│   ├── sidebarStore, themeStore
│   ├── settingsStore, i18nStore, toastStore
│
└── API Client
    └── /api/v1/* → Vite Proxy → Backend :9090
```

### Datenfluss

```
User-Interaktion
    ↓
React-Komponente
    ↓
Hook (useMutation / useQuery)
    ↓
API Client (fetch + X-API-Key Header)
    ↓
Vite Proxy (:3000 → :9090)
    ↓
Spring Boot Controller
    ↓
Service → Repository → SQLite
    ↓
Response (DTO) → React Query Cache → UI-Update
```

## WebSocket (Live-Logs)

- **Protokoll:** STOMP über SockJS
- **Endpunkt:** `/ws` (SockJS Handshake)
- **Broker:** Simple Message Broker auf `/topic`
- **Topic:** `/topic/executions/{runId}/logs`
- **Nachrichtenformat:** `{ timestamp, level, message }`

Das Frontend verbindet sich bei geöffneter Workflow-Ausführung automatisch und zeigt Logs in Echtzeit an.

## Sicherheit

### API-Key-Authentifizierung

- **Header:** `X-API-Key`
- **Filter:** `ApiKeyAuthFilter` (vor Spring Security)
- **Vergleich:** Constant-Time (`MessageDigest.isEqual`)
- **Deaktivierbar:** `automation.security.enabled=false` (Entwicklung)

### Öffentliche Endpunkte (kein API-Key nötig)

- `GET /api/v1/system/health`
- `GET /api/v1/system/info`
- `POST /api/v1/webhook/trigger/{token}`
- `/ws/**` (WebSocket)
- `/actuator/**`

### Rate Limiting

- **Filter:** `RateLimitFilter`
- **Algorithmus:** Sliding Window (1 Minute)
- **Standard:** 120 Requests/Minute pro Client-IP
- **Response-Header:** `X-RateLimit-Remaining`
- **Deaktivierbar:** `automation.security.rate-limit.enabled=false`

### CORS

- Alle Origins erlaubt (`*`) mit Credentials
- Alle HTTP-Methoden (GET, POST, PUT, DELETE, OPTIONS)

## Verzeichnisstruktur

```
autotrigger/
├── README.md
├── docs/
│
├── ai-automation-backend/
│   ├── pom.xml
│   ├── docker-compose.yml
│   ├── docker/
│   │   ├── Dockerfile.backend
│   │   ├── Dockerfile.agent-node
│   │   ├── Dockerfile.agent-python
│   │   └── Dockerfile.agent-go
│   ├── data/                          ← SQLite-DB (gitignored)
│   └── src/main/
│       ├── java/com/automation/       ← Java-Quelldateien
│       └── resources/
│           ├── application.yml
│           └── db/migration/          ← 9 Flyway-Migrationen
│
└── ai-automation-frontend/
    ├── package.json
    ├── vite.config.ts
    ├── tailwind.config.ts
    ├── index.html
    └── src/
        ├── App.tsx                    ← Router
        ├── main.tsx                   ← Entry Point
        ├── index.css                  ← Globale Styles
        ├── api/client.ts              ← HTTP-Client
        ├── types/index.ts             ← TypeScript-Typen
        ├── pages/                     ← 10 Seiten
        ├── components/
        │   ├── ui/                    ← Wiederverwendbare UI-Komponenten
        │   ├── layout/                ← Sidebar, Header, Layout
        │   ├── shared/                ← StatusBadge, LogViewer, etc.
        │   ├── dashboard/             ← Chart-Komponenten
        │   └── workflow/              ← Block-Editor, Templates
        ├── hooks/                     ← React Query Hooks
        ├── stores/                    ← Zustand Stores
        ├── i18n/                      ← DE/EN Übersetzungen
        ├── theme/                     ← Theme-Tokens
        └── data/                      ← Workflow-Templates
```
