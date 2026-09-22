[Deutsch](README.de.md) | **English**

<p align="center"><img src="ai-automation-frontend/public/icons/icon.svg" alt="MySuricates logo: meerkats on a hill" width="120"></p>

# MySuricates

A research project on the question of how far software development can be operated under control with AI agents — and a runnable system in which this can be observed.

MySuricates is a local workflow platform: workflows consist of blocks that are either deterministic (shell, branch, loop) or call an AI. This mixture has produced a control loop that generates findings about its own codebase, turns them into TODOs, triggers fix workflows, and checks the result again. The code was largely created in AI sessions, some of it through this control loop itself.

**What this is not:** not a product, not a service, no support, no promise of stability. The architectural debt is known and is tracked in the system itself as audit findings — see [Maturity level](#maturity-level-and-known-debt).

## The questions behind it

Four mechanisms were built to investigate one question each. Built means here: they run. It does not mean that the question has been answered.

| Question | Mechanism in the code |
|---|---|
| Can a system identify and work through its own need for improvement without someone giving it the initial push? | **Self-improvement loop**: audit workflow → gate evaluation → automatically generated TODO → `TodoTrigger` starts a fix workflow → verification. Three layers prevent the loop from chasing itself. |
| What does an agent need to know about a project, and where does that knowledge come from if nobody writes it down? | **Workflow memory**: knowledge is extracted from completed runs and injected as context before the next run. |
| How do you retain control over an agent that plans several steps itself? | **Agentic blocks** separate reason and act — first one call decides what to do, a second executes it — and run against hard budgets: iterations, time, cumulative tokens, repetition detection. |
| Can the quality of an AI output be evaluated by machine instead of being read? | **Gates**: blocks output `gate_status: GREEN\|YELLOW\|RED`. RED creates a high-priority TODO, YELLOW a medium-priority one. The evaluation lives in the workflow, not in the platform. |

The most honest answer after seven months: the mechanisms hold up, but they need guardrails, and the guardrails are the real yield. A gate that only asks a language model whether something is good reliably produces findings — including invented ones. Deterministic measurement before AI evaluation is the recurring lesson.

## What is there

Status as of 2026-09-21 (commit `3776647f`), commit counter and lines from the working tree on that day (lines via `wc -l`, including blank and comment lines; backend `src/main/java`, frontend `src` including tests). Test numbers from a run in an isolated worktree, dated in the table:

| | |
|---|---|
| Created since | 2026-02-15, around 1,750 commits, one author |
| Backend | Java 21 / Spring Boot 3.4, around 71,000 lines, 47 REST controllers, 78 Flyway migrations |
| Frontend | React 19 / TypeScript, around 71,000 lines |
| Tests | Backend 10,313 (0 failures, 1 skipped), frontend 1,436 — run from 2026-09-21 |
| Building blocks | 20 block types, 67 workflow templates, 19 built-in skills, 7 integration providers |

The test numbers are a dated finding from a local run. The CI definition (`.github/workflows/ci.yml`) has run both suites on every push since 2026-09-12, in addition to the hygiene gate and secret scan; however, the numbers above do not come from a CI run.

## Maturity level and known debt

Experimental. The system runs daily on the author's machine and had around 2,400 runs behind it on 2026-09-12 — so far it has not been operated under load, with multiple users, or outside a trusted network (self-reported).

The debt is not hidden, but counted. The Audit Center — a page in the application where findings from the audit workflows are tracked over time — counted **89 findings** on 2026-09-12:
26 open (of which 1 critical, 4 high), 25 waiting for verification, 38 completed. In substance, almost all are structural: services and UI components that are too large and too tightly coupled, blurry controller/repository boundaries, storage and retention topics. How these findings are created and what the statuses mean is described in the [Audit workflow protocol](docs/en/audit-workflow-protocol.md).

Known hard limits:

- **SQLite with exactly one connection.** The connection pool is deliberately set to 1 because SQLite only knows one writer. This serializes all database access and limits parallelism.
- **No multi-tenancy.** One API key, one user, no roles.
- **The AI calls depend on locally installed CLIs** (`claude`, `codex`, `pi`) together with their login — there is no API-key path as an alternative.

## Before you start it

Shell blocks and AI calls run **on the host with your privileges**, not in a container; the Claude CLI by default does not ask before using tools. When started from source, backend and frontend listen on all network interfaces (the instance set up by the installer binds only to `127.0.0.1` by default), and some endpoints do not require an API key. MySuricates is built for local operation by a trusted person — for remote access, put a VPN in front of it, not an open port. Full details: [Trust model and network exposure](docs/en/deployment.md#trust-model-and-network-exposure).

## Tech stack

| Component | Technology |
|---|---|
| Backend | Spring Boot 3.4, Java 21 |
| Frontend | React 19, TypeScript 6, Vite 8 |
| Database | SQLite (WAL mode) + Flyway |
| Container | Docker (Java Docker Client 3.4.1) — optional, only for shell blocks with `container_image` |
| Styling | Tailwind CSS 4, CSS variables |
| State | Zustand 5 + TanStack Query 5 |
| WebSocket | STOMP over SockJS |
| Security | API key auth, rate limiting |
| i18n | German / English |
| PWA | Vite PWA Plugin |
| Integrations | Google Calendar, Gmail, Jira, Confluence, Trello, GitHub, Taskwarrior |

Execution happens where the instance is running — in the container (recommended) or directly on the machine: shell blocks through the user's shell, AI blocks through the installed CLI (in the container with the login from the machine mounted in, without a second login). A dedicated container per shell block (`container_image`) exists only if the Docker socket is explicitly exposed.

## Quickstart

> New machine (Linux, macOS, Windows via WSL2), possibly without Java/Node/Docker? → **[docs/en/GETTING-STARTED.md](docs/en/GETTING-STARTED.md)**
> guides you step by step through the setup. Short version: `git clone https://github.com/FLSEST/MySuricates.git autotrigger && cd autotrigger && ./scripts/setup.sh`.

```bash
git clone https://github.com/FLSEST/MySuricates.git autotrigger
cd autotrigger
./scripts/setup.sh              # guided, in English; in German: ./scripts/setup.sh --lang de
```

The installer asks for the path — an instance in a container (recommended), directly on the machine, or staged **Dev · Test · Prod** — installs missing tools after asking, builds and starts the instance. Then: http://localhost:9090, the API key is shown by `scripts/instance.sh key --name main`. The instance listens only on `127.0.0.1`; the Docker socket is mounted only on explicit request.

If you develop MySuricates itself: `./scripts/setup.sh --from-source --start`, then `./scripts/start-autotrigger-dev.sh` (backend :9090, Vite with hot reload :3000) — see [docs/en/entwicklung.md](docs/en/entwicklung.md).

<!-- Screenshot placeholder -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Documentation

| Document | Contents |
|---|---|
| [Getting Started](docs/en/GETTING-STARTED.md) | Initial installation with the guided installer (container, machine, or Dev · Test · Prod) |
| [Architecture](docs/en/architektur.md) | System overview, layers, data flow, security |
| [Diagrams](docs/diagramme.md) | Nine pages of flow and architecture diagrams (engine, scheduler, skills, memory, request path, frontend, deployment) |
| [API reference](docs/en/api-referenz.md) | REST endpoints with request/response |
| [Data model](docs/en/datenmodell.md) | Entities, DB schema, migrations |
| [Frontend](docs/en/frontend.md) | Pages, components, hooks, design system |
| [Deployment](docs/en/deployment.md) | Docker, configuration, environment variables, trust model |
| [Development](docs/en/entwicklung.md) | Local setup, build, tests, conventions |
| [Integrations](docs/en/integrations.md) | Plugin system, OAuth, provider reference |
| [Workflow guide](docs/en/WORKFLOW-GUIDE.md) | Block types, patterns, timeouts |
| [Assistant](docs/en/assistant-system.md) | Chat assistant, sessions, suggestions |
| [Audit protocol](docs/en/audit-workflow-protocol.md) | How findings are created, gate status, TODO linkage |
| [Evidence](docs/en/belege.md) | Cases in which a mechanism found something the normal process missed — with run ID and number |
| [Vision](docs/en/vision.md) | Product vision and roadmap |

For agents that access the REST API directly, [AGENTS.md](AGENTS.md) is the entry point.

## Todo workflow linkage

Todos can optionally be linked to a **resolver workflow** — the workflow that is supposed to resolve this one Todo. The linkage is deliberately not global and does not start anything automatically. It is configured in the Todo view of the UI; the field names mentioned are those of the REST API (`/api/v1/todos`).

- `linkedWorkflowId` on the Todo is optional.
- A linked workflow is started only by the manual `Resolve` action.
- On start, the Todo changes to `IN_PROGRESS` and `resolutionState=RUNNING`.
- On a successful workflow run, the Todo is automatically set to `DONE`.
- On a failed run, the Todo jumps back to its previous status and `resolutionState=FAILED`.

Delimitation:
- This is not the same as the existing `TodoTrigger` mechanism.
- `TodoTrigger` starts workflows rule-based/automatically.
- The Todo workflow linkage is currently a manual, optional resolve flow per Todo.

## Contributions and support

Issues remain open — but this repository is a research project by a single developer, not a maintained product: no support, no promise of response times, not even for pull requests. If you still want to contribute something, it is best to open an issue first.

Please do **not** report security vulnerabilities as a public issue, but through the reporting channel in [SECURITY.md](SECURITY.md) (private security advisories from GitHub).

---

> 🌍 This English README.md is generated from [README.de.md](README.de.md) by the "README DE↔EN Sync" workflow. For content changes, edit the German source and re-run the workflow rather than editing this file by hand.
