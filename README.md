[Deutsch](README.de.md) | **English**

# AI Automation Platform

A self-hosted platform for automating AI-driven workflows. Projects run in isolated Docker containers and are managed through a React frontend.

## Tech Stack

| Component | Technology |
|---|---|
| Backend | Spring Boot 3.4, Java 21 |
| Frontend | React 19, TypeScript, Vite 5 |
| Database | SQLite (WAL mode) + Flyway |
| Container | Docker (Java Docker Client 3.3.4) |
| Styling | Tailwind CSS 3.4, CSS variables |
| State | Zustand 4.4 + TanStack Query 5 |
| WebSocket | STOMP over SockJS |
| Security | API key auth, rate limiting |
| i18n | German / English |
| PWA | Vite PWA Plugin |
| Integrations | Google Calendar, Gmail, Jira, Confluence, Trello |

## Quickstart

> New machine (Linux), possibly without Java/Node/Docker? → **[docs/en/GETTING-STARTED.md](docs/en/GETTING-STARTED.md)**
> walks you through the setup step by step. Short version: `git clone … && cd autotrigger && ./scripts/setup.sh`.

```bash
# 1. Clone the repository
git clone <repository-url>
cd autotrigger

# 2. Build and start the backend
cd ai-automation-backend
mvn clean package -DskipTests
java -jar target/ai-automation-backend-*.jar

# 3. Install and start the frontend (new terminal)
cd ai-automation-frontend
npm install
npm run dev

# 4. Open
# Frontend: http://localhost:3000
# Backend API: http://localhost:9090/api/v1
```

Alternatively with Docker:

```bash
cd ai-automation-backend
mvn clean package -DskipTests
docker compose up --build
# → http://localhost:9090
```

<!-- Screenshot placeholder -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Documentation

| Document | Content |
|---|---|
| [Getting Started](docs/en/GETTING-STARTED.md) | First-time installation on a new Linux machine (bootstrap script, prereqs, AI setup) |
| [Architecture](docs/en/architektur.md) | System overview, layers, data flow, security |
| [API Reference](docs/en/api-referenz.md) | All REST endpoints with request/response |
| [Data Model](docs/en/datenmodell.md) | Entities, DB schema, migrations |
| [Frontend](docs/en/frontend.md) | Pages, components, hooks, design system |
| [Deployment](docs/en/deployment.md) | Docker, configuration, environment variables |
| [Development](docs/en/entwicklung.md) | Local setup, build, tests, conventions |
| [Integrations](docs/en/integrations.md) | Plugin system, OAuth, provider reference |
| [Workflow Guide](docs/en/WORKFLOW-GUIDE.md) | Block types, patterns, timeouts |
| [Assistant](docs/en/assistant-system.md) | Chat assistant, sessions, suggestions |
| [Vision](docs/en/vision.md) | Product vision and roadmap |

## Todo Workflow Linking

Todos can optionally be linked to a resolver workflow. The link is deliberately not global and does not start anything automatically.

- `linkedWorkflowId` on the todo is optional.
- A linked workflow is only started by the manual `Resolve` action.
- On start, the todo switches to `IN_PROGRESS` and `resolutionState=RUNNING`.
- On a successful workflow run, the todo is automatically set to `DONE`.
- On a failed run, the todo reverts to its previous status and `resolutionState=FAILED`.

Distinction:
- This is not the same as the existing `TodoTrigger` mechanism.
- `TodoTrigger` starts workflows rule-based/automatically.
- The todo workflow link is currently a manual, optional resolve flow per todo.

---

> 🌍 This English README.md is generated from [README.de.md](README.de.md) by the "README DE↔EN Sync" workflow. For content changes, edit the German source and re-run the workflow rather than editing this file by hand.
