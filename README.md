[Deutsch](README.de.md) | **English**

# MySuricates

A research project exploring how far software development can be operated under control with AI agents — and a runnable system where this can be observed.

MySuricates is a local workflow platform: workflows consist of blocks that are either deterministic (shell, branch, loop) or call an AI. This combination has produced a control loop that generates findings about its own codebase, turns them into TODOs, triggers fix workflows, and checks the result again. The code was largely created in AI sessions, partly through this control loop itself.

**What this is not:** not a product, not a service, no support, no stability promise. The architectural debt is known and is tracked in the system itself as audit findings — see [Maturity](#maturity-and-known-debt).

## The questions behind it

Four mechanisms were built to investigate one question each. Built means: they run. It does not mean the question has been answered.

| Question | Mechanism in the code |
|---|---|
| Can a system identify and work through its own need for improvement without someone giving the initial push? | **Self-Improvement Loop**: audit workflow → gate assessment → automatically created TODO → `TodoTrigger` starts a fix workflow → verification. Three layers prevent the loop from chasing itself. |
| What does an agent need to know about a project, and where does that knowledge come from if nobody writes it down? | **Workflow Memory**: knowledge is extracted from completed runs and injected as context before the next run. |
| How do you keep control over an agent that plans multiple steps by itself? | **Agentic blocks** separate Reason and Act and run against hard budgets: iterations, time, cumulative tokens, repetition detection. |
| Can the quality of AI output be evaluated by machine instead of by reading it? | **Gates**: blocks output `gate_status: GREEN\|YELLOW\|RED`. RED creates a TODO, YELLOW colors the run. The evaluation lives in the workflow, not in the platform. |

The most honest answer after seven months: the mechanisms hold, but they need guardrails, and those guardrails are the real result. A gate that merely asks a language model whether something is good reliably produces findings — including invented ones. Deterministic measurement before AI evaluation is the recurring lesson.

## What is there

Status as of 2026-09-12. Commit count and line counts from the working tree, test numbers from a run on that day in an isolated worktree:

| | |
|---|---|
| Created since | 2026-02-15, 1 014 commits, one author |
| Backend | Java 21 / Spring Boot 3.4, roughly 52 000 lines, 38 REST controllers, 68 Flyway migrations |
| Frontend | React 19 / TypeScript, roughly 48 000 lines |
| Tests | Backend 7 633 (0 failures, 1 skipped), Frontend 496 |
| Building blocks | 17 block types, 66 workflow templates, 19 built-in skills, 7 integration providers |

The test numbers are a dated finding. Keeping them true is CI's job since 2026-09-12: it runs both suites on every push, in addition to the hygiene gate and the secret scan. An external CI run is still pending because the repository has not been pushed yet.

## Maturity and known debt

Experimental. The system runs daily on one machine and has accumulated roughly 2 400 runs — it has never been operated under load, with multiple users, or outside a trusted network.

The debt is not hidden; it is counted. The Audit Center currently tracks **89 findings**: 26 open (including 1 critical, 4 high), 25 awaiting verification, 38 done. In terms of content, they are almost entirely structural: services and UI components that are too large and too tightly coupled, blurred controller/repository boundaries, storage and retention topics. How these findings are created and what the statuses mean is documented in the [Audit Workflow Protocol](docs/en/audit-workflow-protocol.md).

Known hard limits:

- **SQLite with exactly one connection.** The connection pool is deliberately set to 1 because SQLite only has one writer. This serializes all database access and limits parallelism.
- **No multi-tenancy.** One API key, one user, no roles.
- **AI calls depend on locally installed CLIs** (`claude`, `codex`, `pi`) and their logins — there is no API-key path as an alternative.

## Before you start it

Shell blocks and AI calls run **on the host with your permissions**, not in a container; the Claude CLI does not ask by default before using tools. Backend and frontend listen on all network interfaces, and some endpoints do not require an API key. MySuricates is built for local operation by a trusted person — remote access belongs behind a VPN, not an open port. Full details: [Trust model and network exposure](docs/en/deployment.md#trust-model-and-network-exposure).

## Tech stack

| Component | Technology |
|---|---|
| Backend | Spring Boot 3.4, Java 21 |
| Frontend | React 19, TypeScript, Vite 7 |
| Database | SQLite (WAL mode) + Flyway |
| Container | Docker (Java Docker Client 3.4.1) — optional, only for container blocks |
| Styling | Tailwind CSS 4.2, CSS variables |
| State | Zustand 4.4 + TanStack Query 5 |
| WebSocket | STOMP via SockJS |
| Security | API key auth, rate limiting |
| i18n | German / English |
| PWA | Vite PWA Plugin |
| Integrations | Google Calendar, Gmail, Jira, Confluence, Trello, GitHub, Taskwarrior |

Execution happens **on the host**: shell blocks via the user's shell, AI blocks via the installed CLI. Docker is optional and is only needed when a shell block explicitly sets a `container_image`.

## Quickstart

> New machine (Linux), possibly without Java/Node/Docker? → **[docs/GETTING-STARTED.md](docs/en/GETTING-STARTED.md)**
> walks through the setup step by step. Short version: `git clone … && cd autotrigger && ./scripts/setup.sh`.

```bash
# 1. Repository klonen
git clone <repository-url> autotrigger
cd autotrigger

# 2. Backend bauen und starten
cd ai-automation-backend
mvn clean package -DskipTests
java -jar target/ai-automation-backend-*.jar

# 3. Frontend installieren und starten (neues Terminal)
cd ai-automation-frontend
npm install
npm run dev

# 4. Oeffnen
# Frontend: http://localhost:3000
# Backend API: http://localhost:9090/api/v1
```

Alternatively, the backend can run in a container — in that case, please first read the [Deployment](docs/en/deployment.md) section: the Compose file binds the host Docker socket and expects a self-set API key.

<!-- Screenshot-Platzhalter -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Documentation

| Document | Contents |
|---|---|
| [Getting Started](docs/en/GETTING-STARTED.md) | Initial installation on a new Linux machine (bootstrap script, prerequisites, AI setup) |
| [Architecture](docs/en/architektur.md) | System overview, layers, data flow, security |
| [API Reference](docs/en/api-referenz.md) | All REST endpoints with request/response |
| [Data Model](docs/en/datenmodell.md) | Entities, DB schema, migrations |
| [Frontend](docs/en/frontend.md) | Pages, components, hooks, design system |
| [Deployment](docs/en/deployment.md) | Docker, configuration, environment variables, trust model |
| [Development](docs/en/entwicklung.md) | Local setup, build, tests, conventions |
| [Integrations](docs/en/integrations.md) | Plugin system, OAuth, provider reference |
| [Workflow Guide](docs/en/WORKFLOW-GUIDE.md) | Block types, patterns, timeouts |
| [Assistant](docs/en/assistant-system.md) | Chat assistant, sessions, suggestions |
| [Audit Protocol](docs/en/audit-workflow-protocol.md) | How findings are created, gate status, TODO linkage |
| [Vision](docs/en/vision.md) | Product vision and roadmap |

For agents that access the REST API directly, [AGENTS.md](AGENTS.md) is the entry point.

## Todo workflow linkage

Todos can optionally be linked to a resolver workflow. The linkage is deliberately not global and does not start anything automatically.

- `linkedWorkflowId` on the todo is optional.
- A linked workflow is only started by the manual `Loesen` action.
- On start, the todo changes to `IN_PROGRESS` and `resolutionState=RUNNING`.
- After a successful workflow run, the todo is automatically set to `DONE`.
- After a failed run, the todo jumps back to its previous status and `resolutionState=FAILED`.

Scope:
- This is not the same as the existing `TodoTrigger` mechanism.
- `TodoTrigger` starts workflows based on rules/automatically.
- The todo-workflow linkage is currently a manual, optional resolve flow per todo.

---

> 🌍 This English README.md is generated from [README.de.md](README.de.md) by the "README DE↔EN Sync" workflow. For content changes, edit the German source and re-run the workflow rather than editing this file by hand.
