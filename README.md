[Deutsch](README.de.md) | **English**

# MySuricates

A research project on the question of how far software development can be operated in a controlled way with AI agents — and a runnable system in which that can be observed.

MySuricates is a local workflow platform: workflows consist of blocks that are either deterministic (shell, branch, loop) or call an AI. This mix has produced a feedback loop that generates findings about its own codebase, turns them into TODOs, triggers fix workflows, and then checks the result again. The code was created largely in AI sessions, part of it through this feedback loop itself.

**What this is not:** not a product, not a service, no support, no stability promise. The architectural debt is known and is tracked in the system itself as audit findings — see [Maturity](#maturity-and-known-debt).

## The questions behind it

Four mechanisms were built to investigate one question each. Built here means: they run. It does not mean the question has been answered.

| Question | Mechanism in the code |
|---|---|
| Can a system identify and work through its own need for improvement without someone giving it the initial push? | **Self-Improvement Loop**: audit workflow → gate evaluation → automatically generated TODO → `TodoTrigger` starts a fix workflow → verification. Three layers prevent the loop from chasing itself. |
| What does an agent need to know about a project, and where does that knowledge come from if nobody writes it down? | **Workflow Memory**: knowledge is extracted from completed runs and injected as context before the next run. |
| How do you stay in control of an agent that plans several steps on its own? | **Agentic blocks** separate Reason and Act — first one call decides what to do, a second carries it out — and run against hard budgets: iterations, time, cumulative tokens, repetition detection. |
| Can the quality of AI output be evaluated by machine instead of by reading it? | **Gates**: blocks output `gate_status: GREEN\|YELLOW\|RED`. RED creates a high-priority TODO, YELLOW a medium-priority one. The evaluation lives in the workflow, not in the platform. |

The most honest answer after seven months: the mechanisms hold, but they need guardrails, and those guardrails are the actual result. A gate that only asks a language model whether something is good reliably produces findings — including fabricated ones. Deterministic measurement before AI evaluation is the recurring lesson.

## What is there

As of 2026-09-19, commit counts and lines from the working tree on that day (lines via `wc -l`, including blank and comment lines). Test numbers from a run in an isolated worktree, dated in the table:

| | |
|---|---|
| In development since | 2026-02-15, about 1,600 commits, one author |
| Backend | Java 21 / Spring Boot 3.4, about 67,000 lines, 47 REST controllers, 77 Flyway migrations |
| Frontend | React 19 / TypeScript, about 66,000 lines |
| Tests | Backend 9,197 (0 failures, 1 skipped), Frontend 1,367 — run from 2026-09-19 |
| Building blocks | 20 block types, 66 workflow templates, 19 built-in skills, 7 integration providers |

The test numbers are a dated finding from a local run. Since 2026-09-12, the CI definition (`.github/workflows/ci.yml`) has run both suites on every push, in addition to a hygiene gate and secret scan; however, the numbers above do not come from a CI run.

## Maturity and known debt

Experimental. The system runs daily on one machine and had about 2,400 runs behind it on 2026-09-12 — it has never been operated under load, with multiple users, or outside a trusted network.

The debt is not hidden; it is counted. The Audit Center — a page in the application where the findings from the audit workflows are tracked over time — counted **89 findings** on 2026-09-12:
26 open (1 critical, 4 high), 25 waiting for verification, 38 completed. In terms of content, they are almost entirely structural: services and UI components that are too large and too tightly coupled, fuzzy controller/repository boundaries, storage and retention topics. How these findings are produced and what the statuses mean is described in the
[Audit Workflow Protocol](docs/en/audit-workflow-protocol.md).

Known hard limits:

- **SQLite with exactly one connection.** The connection pool is deliberately set to 1 because SQLite only has one writer. This serializes all database access and limits parallelism.
- **No multi-tenancy.** One API key, one user, no roles.
- **The AI calls depend on locally installed CLIs** (`claude`, `codex`, `pi`) and their respective logins — there is no API-key path as an alternative.

## Before you start it

Shell blocks and AI calls run **on the host with your permissions**, not in a container; the Claude CLI does not ask for confirmation before tool use by default. Backend and frontend listen on all network interfaces, and some endpoints do not require an API key. MySuricates is built for local operation by a trusted person — for remote access, put a VPN in front of it, not an open port. Full details:
[Trust model and network exposure](docs/en/deployment.md#trust-model-and-network-exposure).

## Tech stack

| Component | Technology |
|---|---|
| Backend | Spring Boot 3.4, Java 21 |
| Frontend | React 19, TypeScript, Vite 7 |
| Database | SQLite (WAL mode) + Flyway |
| Container | Docker (Java Docker Client 3.4.1) — optional, only for shell blocks with `container_image` |
| Styling | Tailwind CSS 4.2, CSS variables |
| State | Zustand 4.4 + TanStack Query 5 |
| WebSocket | STOMP over SockJS |
| Security | API key auth, rate limiting |
| i18n | German / English |
| PWA | Vite PWA Plugin |
| Integrations | Google Calendar, Gmail, Jira, Confluence, Trello, GitHub, Taskwarrior |

Execution happens **on the host**: shell blocks through the user's shell, AI blocks through the installed CLI. Docker is optional and is only needed if a shell block explicitly sets a `container_image`.

## Quickstart

> New machine (Linux), possibly without Java/Node/Docker? → **[docs/GETTING-STARTED.md](docs/en/GETTING-STARTED.md)**
> walks you through the setup step by step. Short version: `git clone https://github.com/FLSEST/MySuricates.git autotrigger && cd autotrigger && ./scripts/setup.sh`.

Java 21, Maven, Node 20+ and npm are required. AI blocks additionally require a logged-in AI CLI (`claude`, `codex` or `pi`) — without one, only the deterministic block types run. `./scripts/setup.sh` checks all of this and installs anything missing.

```bash
# 1. Clone the repository
git clone https://github.com/FLSEST/MySuricates.git autotrigger
cd autotrigger

# 2. Build and start the backend
cd ai-automation-backend
mvn clean package -DskipTests
java -jar target/ai-automation-backend-*.jar

# 3. Install and start the frontend (new terminal, again in the repo root directory)
cd autotrigger/ai-automation-frontend
npm install
npm run dev

# 4. Open
# Frontend: http://localhost:3000
# Backend API: http://localhost:9090/api/v1
```

Alternatively, the backend runs in a container — in that case, please read the
[Deployment](docs/en/deployment.md) section first: the Compose file binds the host Docker socket and expects a self-set API key.

<!-- Screenshot placeholder -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Documentation

| Document | Contents |
|---|---|
| [Getting Started](docs/en/GETTING-STARTED.md) | Initial installation on a new Linux machine (bootstrap script, prerequisites, AI setup) |
| [Architecture](docs/en/architektur.md) | System overview, layers, data flow, security |
| [Diagrams](docs/diagramme.md) | Nine pages of flow and architecture diagrams (engine, scheduler, skills, memory, request path, frontend, deployment) |
| [API Reference](docs/en/api-referenz.md) | REST endpoints with request/response |
| [Data Model](docs/en/datenmodell.md) | Entities, DB schema, migrations |
| [Frontend](docs/en/frontend.md) | Pages, components, hooks, design system |
| [Deployment](docs/en/deployment.md) | Docker, configuration, environment variables, trust model |
| [Development](docs/en/entwicklung.md) | Local setup, build, tests, conventions |
| [Integrations](docs/en/integrations.md) | Plugin system, OAuth, provider reference |
| [Workflow Guide](docs/en/WORKFLOW-GUIDE.md) | Block types, patterns, timeouts |
| [Assistant](docs/en/assistant-system.md) | Chat assistant, sessions, suggestions |
| [Audit Protocol](docs/en/audit-workflow-protocol.md) | How findings are produced, gate status, TODO linking |
| [Evidence](docs/en/belege.md) | Cases where a mechanism found something the normal process missed — with run ID and number |
| [Vision](docs/en/vision.md) | Product vision and roadmap |

For agents that call the REST API directly, [AGENTS.md](AGENTS.md) is the entry point.

## Todo workflow linking

Todos can optionally be linked to a **resolver workflow** — the workflow that is meant to resolve this one todo. The link is deliberately not global and does not start anything automatically. It is configured in the todo view of the UI; the field names mentioned are those of the REST API (`/api/v1/todos`).

- `linkedWorkflowId` on the todo is optional.
- A linked workflow is only started by the manual `Loesen` action.
- On start, the todo changes to `IN_PROGRESS` and `resolutionState=RUNNING`.
- When the workflow run succeeds, the todo is automatically set to `DONE`.
- When a run fails, the todo jumps back to its previous status and `resolutionState=FAILED`.

Distinction:
- This is not the same as the existing `TodoTrigger` mechanism.
- `TodoTrigger` starts workflows based on rules/automatically.
- Todo workflow linking is currently a manual, optional resolve flow per todo.

## Contributions and support

Issues remain open — but this repository is a research project by a single developer, not a maintained product: no support, no promise of response times, including for pull requests. If you still want to contribute something, it is best to get in touch via an issue first.

Please do **not** report security vulnerabilities as a public issue; use the reporting channel in [SECURITY.md](SECURITY.md) instead (private security advisories from GitHub).

---

> 🌍 This English README.md is generated from [README.de.md](README.de.md) by the "README DE↔EN Sync" workflow. For content changes, edit the German source and re-run the workflow rather than editing this file by hand.
