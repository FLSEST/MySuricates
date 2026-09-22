[Deutsch](README.de.md) | **English**

<p align="center"><img src="ai-automation-frontend/public/icons/tray-meerkat.svg" alt="MySuricates logo: a meerkat" width="120"></p>

# MySuricates

A research project on the question of how far software development can be run in a controlled way
with AI agents — and a runnable system in which this can be observed.

MySuricates is a local workflow platform: workflows consist of blocks that are either
deterministic (shell, branch, loop) or call an AI. This mixture has produced
a control loop that generates findings about its own codebase, turns them into TODOs,
triggers fix workflows, and checks the result again. The code was largely created in AI sessions,
some of it through this control loop itself.

**What this is not:** not a product, not a service, no support, no promise of stability. The
architectural debt is known and is tracked in the system itself as audit findings — see
[Maturity](#maturity-and-known-debt).

## Documentation

| Document | Contents |
|---|---|
| [Getting Started](docs/en/GETTING-STARTED.md) | Try it without installation (tryout image) and initial installation with the guided installer (container, machine, or Dev · Test · Prod) |
| [Concepts](docs/konzepte/README.md) | Eight mechanisms to adopt — gates, self-improvement, hardening, and others — each with flow diagram, code evidence, and dated measurement |
| [Workflow Guide](docs/en/WORKFLOW-GUIDE.md) | Block types, patterns, timeouts |
| [Architecture](docs/en/architektur.md) | System overview, layers, data flow, security |
| [Diagrams](docs/diagramme.md) | Nine pages of flow and architecture diagrams (engine, control and status, scheduler, skills, memory, request path, frontend, deployment, logging and monitoring) |
| [API Reference](docs/en/api-referenz.md) | REST endpoints with request/response |
| [Data Model](docs/en/datenmodell.md) | Entities, DB schema, migrations |
| [Frontend](docs/en/frontend.md) | Pages, components, hooks, design system |
| [Deployment](docs/en/deployment.md) | Docker, configuration, environment variables, trust model |
| [Development](docs/en/entwicklung.md) | Local setup, build, tests, conventions |
| [Developer Onboarding](docs/en/onboarding/README.md) | Getting into the code: architecture, code tour, glossary, FAQ |
| [Integrations](docs/en/integrations.md) | Plugin system, OAuth, provider reference |
| [Assistant](docs/en/assistant-system.md) | Chat assistant, sessions, suggestions |
| [Audit Protocol](docs/en/audit-workflow-protocol.md) | How findings are created, gate status, TODO linkage |
| [Evidence](docs/en/belege.md) | Cases in which a mechanism found something that the normal process missed — with run ID and number |
| [Vision](docs/en/vision.md) | Product vision and roadmap |

For agents that access the REST API directly, [AGENTS.md](AGENTS.md) is the entry point.

## The questions behind it

Four mechanisms were built to investigate one question each. Built means here: they run.
It does not mean that the question has been answered.

| Question | Mechanism in the code |
|---|---|
| Can a system identify and work through its own need for improvement without someone giving it the initial push? | **Self-improvement loop**: audit workflow → gate evaluation → automatically generated TODO → `TodoTrigger` starts a fix workflow → verification. Three layers prevent the loop from chasing itself. |
| What does an agent need to know about a project, and where does that knowledge come from if nobody writes it down? | **Workflow memory**: knowledge is extracted from completed runs and injected as context before the next run. |
| How do you retain control over an agent that plans several steps itself? | **Agentic blocks** separate reason and act — first one call decides what to do, a second executes it — and run against hard budgets: iterations, time, cumulative tokens, repetition detection. |
| Can the quality of an AI output be evaluated by machine instead of being read? | **Gates**: blocks output `gate_status: GREEN\|YELLOW\|RED`. RED creates a high-priority TODO, YELLOW a medium-priority one. The evaluation lives in the workflow, not in the platform. |

The most honest answer after seven months: the mechanisms hold up, but they need guardrails,
and the guardrails are the real yield. A gate that only asks a language model whether something
is good reliably produces findings — including invented ones. Deterministic measurement before AI evaluation is
the recurring lesson. The mechanisms individually, each with flow diagram, code evidence, and limits:
[Concepts](docs/konzepte/README.md).

Not all of this is enabled after installation. Edge features are modules that are fixed at
startup: the `core` preset (default) contains the core, `research` adds
chains, assistant, learning, workflow memory, Audit Center, and BPMN, `full` enables everything —
including integrations, webhooks, and remote access. The installer asks about this.

## What is there

Status as of 2026-09-22 (commit `d23a09ed`, release v0.7.6 plus follow-up work), commit counter and lines
from the working tree on that day (lines via `wc -l`, including blank and comment lines; backend
`src/main/java`, frontend `src` including tests). Test numbers from the CI run for this commit,
dated in the table:

| | |
|---|---|
| Created since | 2026-02-15, around 1,800 commits, one author |
| Backend | Java 21 / Spring Boot 3.5, around 73,000 lines, 48 REST controllers, 84 Flyway migrations |
| Frontend | React 19 / TypeScript, around 77,000 lines |
| Tests | Backend 10,476, frontend 1,580 (192 files) — 0 failures, 0 skipped, CI run 35777382298 of 2026-09-22 |
| Building blocks | 20 block types, 66 workflow templates, 20 blueprints (basic structures to fill in), 19 built-in skills, 7 integration providers |

The test numbers come from the CI run for exactly this commit and are therefore verifiable. The
CI definition (`.github/workflows/ci.yml`) has run both suites on every push since 2026-09-12, in
addition to the hygiene gate and secret scan; since 2026-09-21 on Ubuntu 26.04.

## Maturity and known debt

Experimental. The system runs daily on the author's machine and had
around 2,400 runs behind it on 2026-09-12 — so far it has not been operated under load, with multiple users, or
outside a trusted network (self-reported).

The debt is not hidden, but counted. The Audit Center — a page in the application
where findings from the audit workflows are tracked over time — counted on 2026-09-22 for
MySuricates itself **322 findings** since 2026-07-19: 29 open (of which 1 critical, 4 high),
7 waiting for verification, 267 fixed, 18 accepted as risk, 1 discarded. On that day, the open
findings mainly concerned three fields: structure (an overly large central execution class, strongly
coupled services, controllers with direct repository access), performance and storage
(database size without a vacuum strategy, N+1 candidates, bundle size), and readiness for a
release. How these findings are created and what the statuses mean is described in the
[Audit Workflow Protocol](docs/en/audit-workflow-protocol.md).

Known hard limits:

- **SQLite with exactly one connection.** The connection pool is deliberately set to 1 because SQLite
  only has one writer. This serializes all database access and limits parallelism.
- **No multi-tenancy.** One API key, one user, no roles.
- **The AI calls depend on locally installed CLIs** (`claude`, `codex`, `pi`) — there is
  no way around the CLI. The CLIs themselves also accept an
  API key via environment variable instead of the login.

## Before you start it

Shell blocks and AI calls run **with your privileges** — directly on the machine or, for the
instance in the container, with your mounted folders and logins — not per block in a
sandbox; the AI CLIs by default do not ask before using tools. Started from source,
the backend and frontend listen on all network interfaces (the instance set up by the installer
binds only to `127.0.0.1` by default), and some endpoints require
no API key. MySuricates
is built for local operation by a trusted person — for remote access,
put a VPN in front of it, not an open port. Full details:
[Trust model and network exposure](docs/en/deployment.md#trust-model-and-network-exposure).

## Tech stack

| Component | Technology |
|---|---|
| Backend | Spring Boot 3.5, Java 21 |
| Frontend | React 19, TypeScript 6, Vite 8 |
| Database | SQLite (WAL mode) + Flyway |
| Container | Docker (Java Docker Client 3.4.1) — optional, only for shell blocks with `container_image` |
| Styling | Tailwind CSS 4, CSS variables |
| State | Zustand 5 + TanStack Query 5 |
| WebSocket | STOMP over SockJS |
| Security | API key auth, rate limiting, admission check before every run; optional HMAC-signed workflow seals |
| i18n | German / English |
| PWA | Vite PWA Plugin |
| Integrations | Google Calendar, Gmail, Jira, Confluence, Trello, GitHub, Taskwarrior — module, in the `full` preset |

Execution happens where the instance is running — in the container (recommended) or directly on the
machine: shell blocks through the user's shell, AI blocks through the installed CLI (in the
container with the login from the machine mounted in, without a second login). A dedicated container per
shell block (`container_image`) exists only if the Docker socket is explicitly exposed.

## Quickstart

> New machine (Linux, macOS, Windows via WSL2), possibly without Java/Node/Docker? → **[docs/en/GETTING-STARTED.md](docs/en/GETTING-STARTED.md)**
> guides you step by step through the setup. Short version: `git clone https://github.com/FLSEST/MySuricates.git autotrigger && cd autotrigger && ./scripts/setup.sh`.

Just trying it out: In the cloned repository, `scripts/tryout.sh build && scripts/tryout.sh start`
starts a container with all tools at http://localhost:9095 that mounts nothing from the machine —
all it needs is Docker
([Getting Started](docs/en/GETTING-STARTED.md#just-trying-it-out--no-installation)).

Set up permanently:

```bash
git clone https://github.com/FLSEST/MySuricates.git autotrigger
cd autotrigger
./scripts/setup.sh              # guided, in English; in German: ./scripts/setup.sh --lang de
```

The installer asks for the path — an instance in a container (recommended), directly on the machine,
or staged **Dev · Test · Prod** — and for the modules, installs missing tools after asking,
builds and starts the instance. Then: http://localhost:9090, the API key is shown by
`scripts/instance.sh key --name main`. The instance listens only on `127.0.0.1`; the Docker socket
is mounted only on explicit request.

If you develop MySuricates itself: `./scripts/setup.sh --from-source --start`, then
`./scripts/start-autotrigger-dev.sh` (backend :9090, Vite with hot reload :3000) — see
[docs/en/entwicklung.md](docs/en/entwicklung.md).

<!-- Screenshot placeholder -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Todo workflow linkage

Todos can optionally be linked to a **resolver workflow** — the workflow that is supposed to
resolve this one Todo. The linkage is deliberately not global and does not start anything
automatically. It is configured in the Todo view of the UI; the field names mentioned
are those of the REST API (`/api/v1/todos`).

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

Issues remain open — but this repository is a research project by a
single developer, not a maintained product: no support, no promise of
response times, not even for pull requests. If you still want to contribute
something, it is best to open an issue first.

Please do **not** report security vulnerabilities as a public issue, but through
the reporting channel in [SECURITY.md](SECURITY.md) (private security advisories from
GitHub).

---

> 🌍 This English README.md is generated from [README.de.md](README.de.md) by the "README DE↔EN Sync" workflow. For content changes, edit the German source and re-run the workflow rather than editing this file by hand.
