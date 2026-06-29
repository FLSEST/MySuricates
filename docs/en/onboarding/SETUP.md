<!-- last-sync: 2026-06-29 from: docs/onboarding/SETUP.md -->

# Setup Guide

Step-by-step guide for setting up the local development environment.

---

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| **JDK** | 21+ | `java -version` |
| **Maven** | 3.9+ | `mvn -version` |
| **Node.js** | 18+ | `node -v` |
| **npm** | 9+ | `npm -v` |
| **Docker** | 24+ | `docker --version` |
| **Docker Compose** | 2.x | `docker compose version` |
| **Claude CLI** | current | `claude --version` |

### Installing the Claude CLI

The AI-driven blocks require a locally installed Claude CLI on the host:

```bash
# Installation (see official documentation)
# Claude CLI must be available in PATH
which claude
```

> **Important:** The Claude CLI always runs on the host system, NOT inside Docker containers.

---

## 1. Clone the Repository

```bash
git clone <repository-url>
cd autotrigger
```

---

## 2. Set Up the Backend

### 2.1 Build

```bash
cd ai-automation-backend
mvn clean package -DskipTests
```

### 2.2 Configuration

The default configuration in `src/main/resources/application.yml` works out-of-the-box for local development:

| Property | Default | Description |
|---|---|---|
| `server.port` | `9090` | Backend port |
| `AUTOMATION_DB_PATH` | `./data/automation.db` | SQLite database path |
| `AUTOMATION_SECURITY_ENABLED` | `true` | API key authentication |
| `AUTOMATION_API_KEY` | (empty) | API key (generated on first start) |
| `AUTOMATION_CORS_ORIGINS` | `localhost:3000,localhost:5173` | Allowed CORS origins |
| `AUTOMATION_MAX_CONCURRENT_RUNS` | `10` | Maximum parallel workflow executions |
| `AUTOMATION_HOST_CLAUDE_TIMEOUT` | `300` | Timeout for Claude CLI (seconds) |

### Environment Variables (optional)

```bash
# Disable security (development only!)
export AUTOMATION_SECURITY_ENABLED=false

# Set a custom API key
export AUTOMATION_API_KEY=my-secret-key

# Set workspace root (for project paths)
export AUTOMATION_WORKSPACE_ROOT=/path/to/projects
```

### 2.3 Start

```bash
mvn spring-boot:run
```

The backend starts at **http://localhost:9090**. On first start:
- Flyway runs all 19 migrations
- SQLite database is created at `data/automation.db`
- 16 built-in skills are seeded
- 28 workflow templates are seeded

### 2.4 Health Check

```bash
curl http://localhost:9090/api/v1/system/health
```

---

## 3. Set Up the Frontend

### 3.1 Install Dependencies

```bash
cd ai-automation-frontend
npm install
```

### 3.2 Configuration

The `.env` file contains the backend URLs:

```env
VITE_API_BASE_URL=http://localhost:9090
VITE_WS_BASE_URL=ws://localhost:9090
```

> **Note:** In dev mode, Vite automatically proxies `/api` and `/ws` to the backend (see `vite.config.ts`).

### 3.3 Start

```bash
npm run dev
```

The frontend starts at **http://localhost:3000**.

### 3.4 Available npm Scripts

| Script | Description |
|---|---|
| `npm run dev` | Vite dev server with HMR (port 3000) |
| `npm run build` | TypeScript check + production build |
| `npm run preview` | Test production build locally |
| `npm run lint` | Run ESLint |
| `npm run cy:open` | Open Cypress interactively |
| `npm run cy:run` | Run Cypress headless |
| `npm run test:e2e` | Alias for `cy:run` |

---

## 4. Build Docker Containers (optional)

The agent containers are required for skill execution:

```bash
cd ai-automation-backend

# Build all agent images
docker compose --profile build-only build

# Individual images
docker build -f docker/Dockerfile.agent-python -t automation-agent-python:latest .
docker build -f docker/Dockerfile.agent-node -t automation-agent-node:latest .
docker build -f docker/Dockerfile.agent-go -t automation-agent-go:latest .
```

### Docker-based Start (alternative)

```bash
cd ai-automation-backend

# Backend via Docker only
docker compose up

# Backend + build agent images
docker compose --profile build-only up --build
```

---

## 5. Running Tests

### Backend (JUnit)

```bash
cd ai-automation-backend
mvn test
```

There are 49 test files in the following categories:
- **Controller tests** (6): Integration tests for REST endpoints
- **Service tests** (20+): Unit tests for business logic
- **Executor tests** (7): Tests for block execution logic
- **Integration tests**: End-to-end tests

### Frontend (Cypress E2E)

```bash
cd ai-automation-frontend

# Prerequisite: backend must be running!
npm run test:e2e    # Headless
npm run cy:open     # Interactive
```

> **Note:** Cypress automatically detects the API key from the SQLite database (see `cypress.config.ts`).

---

## 6. First Steps After Setup

1. **Open browser:** http://localhost:3000
2. **Check dashboard:** Shows system status and current statistics
3. **Create a project:** Under "Projects", create a new project (name, type, path)
4. **Create a workflow:** Under "Workflows", start a new workflow from a template
5. **Run a workflow:** Click "Execute" in the workflow editor

### API Key (when security is enabled)

If `AUTOMATION_SECURITY_ENABLED=true`:
1. The API key is generated on first start
2. Enter the API key in the frontend under "Settings"
3. Alternatively: set `AUTOMATION_API_KEY` as an environment variable

---

## Common Setup Issues

| Problem | Solution |
|---|---|
| `Port 9090 already in use` | Stop the other process on port 9090 or set `AUTOMATION_PORT` |
| `Port 3000 already in use` | Vite automatically picks the next available port |
| `SQLite BUSY` | Only start one backend instance at a time |
| `Claude CLI not found` | Ensure `claude` is in PATH |
| `Docker socket permission denied` | Add user to the `docker` group: `sudo usermod -aG docker $USER` |
| `CORS error in browser` | Check `AUTOMATION_CORS_ORIGINS` or disable security |
| `mvn: command not found` | Install Maven or use the Maven wrapper: `./mvnw` |
