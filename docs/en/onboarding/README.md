<!-- last-sync: 2026-06-29 from: docs/onboarding/README.md -->

# MySuricates - Developer Onboarding

## Welcome!

Welcome to **MySuricates**. This document gives you a quick overview of the project, its purpose, and the technologies used.

> **About the names:** The product is called **MySuricates**. The git directory is historically named `autotrigger`, and **AutoPilot** is the name of the project under which MySuricates tracks its own workflows and TODOs. All three refer to the same thing from different angles.

---

## Project Overview

MySuricates is an **AI-driven workflow automation platform** with a visual interface. It enables you to define, execute, and monitor complex automation flows — driven by a combination of deterministic blocks and AI-assisted (agentic) executions.

### Core Features

| Feature | Description |
|---|---|
| **Visual Workflow Editor** | Drag-and-drop block editor with 20 block types (full list and configuration: [WORKFLOW-GUIDE.md](../WORKFLOW-GUIDE.md)) |
| **AI Integration** | Claude CLI is executed directly on the host for intelligent decisions and code generation |
| **Docker-based Execution** | Isolated containers (Python, Node.js, Go) for secure skill execution |
| **Skill System** | 19 built-in skills + custom skills for reusable automation building blocks |
| **Scheduling & Webhooks** | Time-based (cron) and HTTP-triggered workflow executions |
| **Workflow Templates** | 67 pre-built templates as starting points for new workflows |
| **Real-time Updates** | WebSocket-based live updates during execution |
| **Approval Gates** | Manual approval mechanism for critical steps |
| **Workflow Conversion** | Successful agentic runs can be converted into deterministic workflows |

---

## Technology Stack

### Backend

| Technology | Version | Purpose |
|---|---|---|
| **Java** | 21 (LTS) | Programming language with virtual threads |
| **Spring Boot** | 3.5.16 | Web framework & dependency injection |
| **Spring Data JPA** | 3.4.x (via Spring Boot BOM) | Database abstraction (ORM) |
| **SQLite** | sqlite-jdbc 3.50.3.0 | Embedded database (WAL mode, HikariCP pool size 1) |
| **Flyway** | 10.20.1 (via Spring Boot BOM) | Database migration management |
| **MapStruct** | 1.5.5 | Entity-DTO mapping (code generation) |
| **Lombok** | 1.18.x | Boilerplate reduction (getters, setters, builders) |
| **Docker Java Client** | 3.4.1 | Container management via Docker API |
| **Maven** | 3.9+ | Build tool & dependency management |

### Frontend

| Technology | Version | Purpose |
|---|---|---|
| **React** | 19.3 | UI framework (SPA) |
| **TypeScript** | 6.0 | Type-safe JavaScript development |
| **Vite** | 8.3 | Build tool & dev server |
| **TanStack Query** | 5.17.0 | Server-state management & caching |
| **Zustand** | 4.4.7 | Client-state management |
| **TailwindCSS** | 4.3 | Utility-first CSS framework (CSS-first configuration in `src/index.css`, no `tailwind.config.*`) |
| **Monaco Editor** | 4.6.0 | Code editor (VS Code engine) |
| **xterm.js** | 6.0.0 | Terminal emulator for WebSocket terminal |
| **Cypress** | 16.1 | End-to-end testing |
| **Vite PWA** | 1.3 | Progressive Web App support |

### Infrastructure

| Technology | Purpose |
|---|---|
| **Docker** | Container runtime for skill execution |
| **Docker Compose** | Local development environment |
| **WebSocket** | Real-time communication (updates + terminal) |
| **Claude CLI** | AI execution on the host system |

---

## Project Structure (High-Level)

```
autotrigger/
├── ai-automation-backend/       # Spring Boot REST API (Port 9090)
│   ├── src/main/java/           # Java source code
│   ├── src/main/resources/      # Configuration & migrations
│   ├── src/test/                # Unit & integration tests
│   ├── docker/                  # Dockerfiles for agent containers
│   ├── docker-compose.yml       # Development environment (+ .stable.yml, .test.yml)
│   └── pom.xml                  # Maven build configuration
│
├── ai-automation-frontend/      # React SPA (Port 3000)
│   ├── src/                     # TypeScript source code
│   ├── cypress/                 # E2E tests
│   ├── public/                  # Static assets
│   └── package.json             # npm configuration
│
├── docs/                        # Documentation (DE) -- docs/en/ mirrors it in English
│   └── onboarding/              # This onboarding package
│
├── scripts/                     # Start, staging and CI scripts
└── CLAUDE.md                    # AI agent context
```

---

## Quick Start

For the full setup guide see [SETUP.md](SETUP.md). Here is the short version:

```bash
# Start the backend (builds the JAR and starts it from a copy)
./scripts/start-autotrigger-dev.sh start

# Start the frontend (new terminal)
cd ai-automation-frontend
npm install && npm run dev
```

> Do **not** use `mvn spring-boot:run`: the `RuntimeClasspathGuard` aborts a start from `target/classes` with exit code 2.

The application is then available at **http://localhost:3000**.

---

## Further Documentation

| Document | Contents |
|---|---|
| [SETUP.md](SETUP.md) | Step-by-step setup guide |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Architecture, data flow & core concepts |
| [CODE_WALKTHROUGH.md](CODE_WALKTHROUGH.md) | The 10 most important files explained |
| [GLOSSARY.md](GLOSSARY.md) | Project-specific terms & abbreviations |
| [FAQ.md](FAQ.md) | Common problems and solutions |
| [WORKFLOW-GUIDE.md](../WORKFLOW-GUIDE.md) | Practical guide for creating workflows (block types, patterns, timeouts) |

---

## Contacts & Resources

- **Project Repository:** Local git repository
- **API Documentation:** Available via the REST endpoints at `/api/v1`
- **System Health:** `GET /api/v1/system/health`
- **Frontend Dev Server:** http://localhost:3000
- **Backend Dev Server:** http://localhost:9090
