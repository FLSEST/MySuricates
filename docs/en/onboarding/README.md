<!-- last-sync: 2026-06-29 from: docs/onboarding/README.md -->

# AutoPilot - Developer Onboarding

## Welcome!

Welcome to the **AutoPilot** project (also known as AI Automation Platform). This document gives you a quick overview of the project, its purpose, and the technologies used.

---

## Project Overview

AutoPilot is an **AI-driven workflow automation platform** with a visual interface. It enables you to define, execute, and monitor complex automation flows — driven by a combination of deterministic blocks and AI-assisted (agentic) executions.

### Core Features

| Feature | Description |
|---|---|
| **Visual Workflow Editor** | Drag-and-drop block editor with 11 block types (Simple, Shell, Retry, Conditional, ForEach, While, Agentic, Approval, Sub-Workflow, Skill-Capability) |
| **AI Integration** | Claude CLI is executed directly on the host for intelligent decisions and code generation |
| **Docker-based Execution** | Isolated containers (Python, Node.js, Go) for secure skill execution |
| **Skill System** | 16 built-in skills + custom skills for reusable automation building blocks |
| **Scheduling & Webhooks** | Time-based (cron) and HTTP-triggered workflow executions |
| **Workflow Templates** | 28 pre-built templates as starting points for new workflows |
| **Real-time Updates** | WebSocket-based live updates during execution |
| **Approval Gates** | Manual approval mechanism for critical steps |
| **Workflow Conversion** | Successful agentic runs can be converted into deterministic workflows |

---

## Technology Stack

### Backend

| Technology | Version | Purpose |
|---|---|---|
| **Java** | 21 (LTS) | Programming language with virtual threads |
| **Spring Boot** | 3.2.2 | Web framework & dependency injection |
| **Spring Data JPA** | 3.2.x | Database abstraction (ORM) |
| **SQLite** | 3.44.1 | Embedded database (WAL mode) |
| **Flyway** | 9.x | Database migration management |
| **MapStruct** | 1.5.5 | Entity-DTO mapping (code generation) |
| **Lombok** | 1.18.x | Boilerplate reduction (getters, setters, builders) |
| **Docker Java Client** | 3.3.4 | Container management via Docker API |
| **Maven** | 3.9+ | Build tool & dependency management |

### Frontend

| Technology | Version | Purpose |
|---|---|---|
| **React** | 18.3.1 | UI framework (SPA) |
| **TypeScript** | 5.3.3 | Type-safe JavaScript development |
| **Vite** | 5.0.12 | Build tool & dev server |
| **TanStack Query** | 5.17.0 | Server-state management & caching |
| **Zustand** | 4.4.7 | Client-state management |
| **TailwindCSS** | 3.4.1 | Utility-first CSS framework |
| **Monaco Editor** | 4.6.0 | Code editor (VS Code engine) |
| **xterm.js** | 6.0.0 | Terminal emulator for WebSocket terminal |
| **Cypress** | 13.17.0 | End-to-end testing |
| **Vite PWA** | 0.17.4 | Progressive Web App support |

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
│   └── pom.xml                  # Maven build configuration
│
├── ai-automation-frontend/      # React SPA (Port 3000)
│   ├── src/                     # TypeScript source code
│   ├── cypress/                 # E2E tests
│   ├── public/                  # Static assets
│   └── package.json             # npm configuration
│
├── docs/                        # Documentation
│   └── onboarding/              # This onboarding package
│
├── docker-compose.yml           # Development environment
└── CLAUDE.md                    # AI agent context
```

---

## Quick Start

For the full setup guide see [SETUP.md](SETUP.md). Here is the short version:

```bash
# Start the backend
cd ai-automation-backend
mvn spring-boot:run

# Start the frontend (new terminal)
cd ai-automation-frontend
npm install && npm run dev
```

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
