**Deutsch** | [English](README.md)

# AI Automation Platform

Eine Self-Hosted-Plattform zur Automatisierung von AI-gesteuerten Workflows. Projekte werden in isolierten Docker-Containern ausgeführt und über ein React-Frontend verwaltet.

## Tech-Stack

| Komponente | Technologie |
|---|---|
| Backend | Spring Boot 3.4, Java 21 |
| Frontend | React 19, TypeScript, Vite 5 |
| Datenbank | SQLite (WAL-Modus) + Flyway |
| Container | Docker (Java Docker Client 3.3.4) |
| Styling | Tailwind CSS 3.4, CSS-Variablen |
| State | Zustand 4.4 + TanStack Query 5 |
| WebSocket | STOMP über SockJS |
| Sicherheit | API-Key-Auth, Rate Limiting |
| i18n | Deutsch / Englisch |
| PWA | Vite PWA Plugin |
| Integrationen | Google Calendar, Gmail, Jira, Confluence, Trello |

## Quickstart

> Neuer Rechner (Linux), evtl. ohne Java/Node/Docker? → **[docs/GETTING-STARTED.md](docs/GETTING-STARTED.md)**
> führt Schritt für Schritt durch das Setup. Kurzform: `git clone … && cd autotrigger && ./scripts/setup.sh`.

```bash
# 1. Repository klonen
git clone <repository-url>
cd autotrigger

# 2. Backend bauen und starten
cd ai-automation-backend
mvn clean package -DskipTests
java -jar target/ai-automation-backend-*.jar

# 3. Frontend installieren und starten (neues Terminal)
cd ai-automation-frontend
npm install
npm run dev

# 4. Öffnen
# Frontend: http://localhost:3000
# Backend API: http://localhost:9090/api/v1
```

Alternativ mit Docker:

```bash
cd ai-automation-backend
mvn clean package -DskipTests
docker compose up --build
# → http://localhost:9090
```

<!-- Screenshot-Platzhalter -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Dokumentation

| Dokument | Inhalt |
|---|---|
| [Getting Started](docs/GETTING-STARTED.md) | Erstinstallation auf einem neuen Linux-Rechner (Bootstrap-Skript, Prereqs, KI-Setup) |
| [Architektur](docs/architektur.md) | Systemübersicht, Schichten, Datenfluss, Sicherheit |
| [API-Referenz](docs/api-referenz.md) | Alle REST-Endpunkte mit Request/Response |
| [Datenmodell](docs/datenmodell.md) | Entities, DB-Schema, Migrationen |
| [Frontend](docs/frontend.md) | Seiten, Komponenten, Hooks, Design-System |
| [Deployment](docs/deployment.md) | Docker, Konfiguration, Umgebungsvariablen |
| [Entwicklung](docs/entwicklung.md) | Lokales Setup, Build, Tests, Konventionen |
| [Integrations](docs/integrations.md) | Plugin-System, OAuth, Provider-Referenz |
| [Workflow-Guide](docs/WORKFLOW-GUIDE.md) | Block-Typen, Patterns, Timeouts |
| [Assistant](docs/assistant-system.md) | Chat-Assistent, Sessions, Suggestions |
| [Vision](VISION.md) | Produktvision und Roadmap |

## Todo-Workflow-Verknuepfung

Todos koennen optional mit einem Resolver-Workflow verknuepft werden. Die Verknuepfung ist bewusst nicht global und startet nichts automatisch.

- `linkedWorkflowId` am Todo ist optional.
- Ein verlinkter Workflow wird erst durch die manuelle Aktion `Loesen` gestartet.
- Beim Start wechselt das Todo auf `IN_PROGRESS` und `resolutionState=RUNNING`.
- Bei erfolgreichem Workflow-Run wird das Todo automatisch auf `DONE` gesetzt.
- Bei einem fehlgeschlagenen Run springt das Todo auf den vorherigen Status zurueck und `resolutionState=FAILED`.

Abgrenzung:
- Das ist nicht dasselbe wie der bestehende `TodoTrigger`-Mechanismus.
- `TodoTrigger` startet Workflows regelbasiert/automatisch.
- Die Todo-Workflow-Verknuepfung ist aktuell ein manueller, optionaler Resolve-Flow pro Todo.

---

> 🌍 Die englische `README.md` wird vom Workflow **„README DE↔EN Sync"** aus dieser Datei generiert. Bei inhaltlichen Änderungen diese deutsche Quelle bearbeiten und den Workflow laufen lassen.
