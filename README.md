[Deutsch](README.de.md) | **English**

# MySuricates

A research project exploring how far software development can be carried out with AI agents in a controlled way — and a runnable system where that can be observed.

MySuricates is a local workflow platform: workflows consist of blocks that are either deterministic (shell, branch, loop) or call an AI. This mix has produced a feedback loop that generates findings about its own codebase, turns them into TODOs, triggers fix workflows, and verifies the result again. The code was largely created in AI sessions, with part of it produced through this feedback loop itself.

**What this is not:** not a product, not a service, no support, no promise of stability. The architectural debt is known and is tracked in the system itself as audit findings — see [Maturity](#maturity-and-known-debt).

## The questions behind it

Four mechanisms were built to investigate one question each. Built means: they run. It does not mean the question has been answered.

| Question | Mechanism in the code |
|---|---|
| Can a system identify and work through its own need for improvement without someone giving it the initial push? | **Self-improvement loop**: audit workflow → gate evaluation → automatically generated TODO → `TodoTrigger` starts a fix workflow → verification. Three layers prevent the loop from chasing itself. |
| What does an agent need to know about a project, and where does that knowledge come from if nobody writes it down? | **Workflow memory**: knowledge is extracted from completed runs and fed in as context before the next run. |
| How do you keep control over an agent that plans several steps by itself? | **Agentic blocks** separate reason and act and run against hard budgets: iterations, time, cumulative tokens, repetition detection. |
| Can the quality of an AI output be evaluated by machine instead of by reading it? | **Gates**: blocks output `gate_status: GREEN\|YELLOW\|RED`. RED creates a high-priority TODO, YELLOW a medium-priority one. The evaluation lives in the workflow, not in the platform. |

The most honest answer after seven months: the mechanisms hold up, but they need guardrails, and those guardrails are the real result. A gate that merely asks a language model whether something is good reliably produces findings — including invented ones. Deterministic measurement before AI evaluation is the recurring lesson.

## What is there

As of 2026-09-18, commit counts and lines from the working tree on that day (lines via `wc -l`, including blank and comment lines). Test counts are from a run on 2026-09-12 in an isolated worktree:

| | |
|---|---|
| Created since | 2026-02-15, roughly 1,320 commits, one author |
| Backend | Java 21 / Spring Boot 3.4, roughly 62,000 lines, 43 REST controllers, 73 Flyway migrations |
| Frontend | React 19 / TypeScript, roughly 56,000 lines |
| Tests | Backend 7,633 (0 failures, 1 skipped), frontend 496 — run from 2026-09-12 |
| Building blocks | 19 block types, 66 workflow templates, 19 built-in skills, 7 integration providers |

The test counts are a dated finding. CI has been checking that they stay that way since 2026-09-12: it runs both suites on every push, in addition to the hygiene gate and secret scan. A CI run on an external Git host is still pending.

## Maturity and known debt

Experimental. The system runs daily on one machine and had around 2,400 runs behind it on 2026-09-12 — it has never been operated under load, never with multiple users, and never outside a trusted network.

The debt is not hidden; it is counted. On 2026-09-12, the Audit Center listed **89 findings**:
26 open (including 1 critical, 4 high), 25 awaiting verification, 38 completed. In substance, they are almost entirely structural: services and UI components that are too large and too tightly coupled, blurry controller/repository boundaries, memory and retention topics. How these findings are produced and what the statuses mean is described in the
[Audit workflow protocol](docs/en/audit-workflow-protocol.md).

Known hard limits:

- **SQLite with exactly one connection.** The connection pool is deliberately set to 1 because SQLite only supports one writer. This serializes all database access and limits parallelism.
- **No multi-tenancy.** One API key, one user, no roles.
- **The AI calls depend on locally installed CLIs** (`claude`, `codex`, `pi`) including their logins — there is no API-key path as an alternative.

## Before you start it

Shell blocks and AI calls run **on the host with your privileges**, not in a container; by default, the Claude CLI does not ask before using tools. Backend and frontend listen on all network interfaces, and some endpoints do not require an API key. MySuricates is built for local operation by a trusted person — for remote access, put a VPN in front of it, not an open port. Full details:
[Trust model and network exposure](docs/en/deployment.md#vertrauensmodell-und-netzwerk-exposition).

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

Execution happens **on the host**: shell blocks through the user's shell, AI blocks through the installed CLI. Docker is optional and is only needed when a shell block explicitly sets a `container_image`.

## Quickstart

> New machine (Linux), possibly without Java/Node/Docker? → **[docs/GETTING-STARTED.md](docs/en/GETTING-STARTED.md)**
> walks you through the setup step by step. Short version: `git clone <repository-url> autotrigger && cd autotrigger && ./scripts/setup.sh`.

```bash
# 1. Clone repository
git clone <repository-url> autotrigger
cd autotrigger

# 2. Build and start backend
cd ai-automation-backend
mvn clean package -DskipTests
java -jar target/ai-automation-backend-*.jar

# 3. Install and start frontend (new terminal)
cd ai-automation-frontend
npm install
npm run dev

# 4. Open
# Frontend: http://localhost:3000
# Backend API: http://localhost:9090/api/v1
```

Alternatively, the backend can run in a container — in that case, please read the
[Deployment](docs/en/deployment.md) section first: the Compose file mounts the host Docker socket and expects an API key you set yourself.

<!-- Screenshot placeholder -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Documentation

| Document | Content |
|---|---|
| [Getting Started](docs/en/GETTING-STARTED.md) | Initial installation on a new Linux machine (bootstrap script, prerequisites, AI setup) |
| [Architecture](docs/en/architektur.md) | System overview, layers, data flow, security |
| [API reference](docs/en/api-referenz.md) | REST endpoints with request/response |
| [Data model](docs/en/datenmodell.md) | Entities, DB schema, migrations |
| [Frontend](docs/en/frontend.md) | Pages, components, hooks, design system |
| [Deployment](docs/en/deployment.md) | Docker, configuration, environment variables, trust model |
| [Development](docs/en/entwicklung.md) | Local setup, build, tests, conventions |
| [Integrations](docs/en/integrations.md) | Plugin system, OAuth, provider reference |
| [Workflow guide](docs/en/WORKFLOW-GUIDE.md) | Block types, patterns, timeouts |
| [Assistant](docs/en/assistant-system.md) | Chat assistant, sessions, suggestions |
| [Audit protocol](docs/en/audit-workflow-protocol.md) | How findings are produced, gate status, TODO linking |
| [Vision](docs/en/vision.md) | Product vision and roadmap |

For agents that access the REST API directly, [AGENTS.md](AGENTS.md) is the entry point.

## Todo workflow linking

Todos can optionally be linked to a resolver workflow. The link is deliberately not global and does not start anything automatically.

- `linkedWorkflowId` on the todo is optional.
- A linked workflow is only started by the manual `Resolve` action.
- On start, the todo changes to `IN_PROGRESS` and `resolutionState=RUNNING`.
- After a successful workflow run, the todo is automatically set to `DONE`.
- After a failed run, the todo jumps back to its previous status and `resolutionState=FAILED`.

Boundary:
- This is not the same as the existing `TodoTrigger` mechanism.
- `TodoTrigger` starts workflows based on rules/automatically.
- Todo workflow linking is currently a manual, optional resolve flow per todo.

## Contributions and support

Issues remain open — but this repository is a research project by a single developer, not a maintained product: no support, no promise of response times, including for pull requests. If you still want to contribute something, it is best to get in touch via an issue first.

Please do **not** report security vulnerabilities as public issues; use the reporting channel in [SECURITY.md](SECURITY.md) instead (private GitHub security advisories).

---

> 🌍 This English README.md is generated from [README.de.md](README.de.md) by the "README DE↔EN Sync" workflow. For content changes, edit the German source and re-run the workflow rather than editing this file by hand.
