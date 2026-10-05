[Deutsch](README.de.md) | **English**

<p align="center"><img src="docs/assets/readme-banner-en.svg" alt="MySuricates – Build your own workflows with your suricates and let them help you keep an eye on every step. Deterministic wherever possible. Any provider: swap it out and keep your flow. Keep track of what you have done and what you are doing. Put your suricates to work the way you need them. Workflows, chains, skills, templates, schedules, TODOs, integrations, webhooks, human-in-the-loop, assistant, BPMN, versions, gates, memory, dashboard, audit, highly configurable, fully customizable, features can be switched off, and more."></p>

<p align="center">
  <a href="https://github.com/FLSEST/MySuricates/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/FLSEST/MySuricates/ci.yml?branch=main&amp;label=CI" alt="CI"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/FLSEST/MySuricates" alt="License"></a>
  <a href="https://github.com/FLSEST/MySuricates/releases/latest"><img src="https://img.shields.io/github/v/release/FLSEST/MySuricates" alt="Release"></a>
</p>

# MySuricates

A research project on the question of how software development and other work change with AI agents,
and how that work can be put together in different ways from repeatable, as traceable as possible
building blocks — and a working system in which this can be observed.

**What this is not:** not a product, not a service, no support, no promise of stability. The
architectural debt is known and is tracked within the system itself as audit findings — see
[Maturity level](#maturity-level-and-known-debt).

## Questions that come up along the way

There are already very different answers to these questions today.

> **How do I work with strong LLMs if I don't just want to hand agent systems more tasks on demand
> or simply let them run free?**

1. Tasks and side threads that come out of a piece of work **do not disappear into the void** — TODO
   system, history. MySuricates knows the project, the project knows MySuricates. On top of that,
   workflows that **think of the things that were forgotten during the session**.
2. How do I build flows that **combine deterministic and non-deterministic** — and how do I make that
   **visible** (versions, runs, process steps)? And from there: what different kinds of steps does
   that take?
3. How do I make the whole thing **independent of providers**?
4. How do I build **loops (loop engineering)** like the audit system that make a run comparable with
   earlier ones, find questions that were already asked, and let **people evaluate** the result?
5. From this, **many additional ideas** emerged: how to get there, how to connect it, how to make it
   visible.

The answers are building blocks, and you don't need all of them. What you already have, you connect —
an existing task system instead of the built-in TODOs, for example. Which building block belongs to
which answer, and which approaches (spec-driven development or orchestrating agents, for example)
can be built from the same blocks, is shown in [Building blocks](docs/en/baukasten.md).

After installation only the core is on (preset `core`); `research` and `full` switch on further
modules — which ones is listed under [Research questions](#research-questions) and in [Building blocks](docs/en/baukasten.md).

## Quickstart

Just trying it out — all you need is Docker:

```bash
git clone https://github.com/FLSEST/MySuricates.git && cd MySuricates
scripts/tryout.sh build          # builds the image from the latest release; takes a few minutes the first time
scripts/tryout.sh start          # http://localhost:9095 — the output shows the API key for login
scripts/tryout.sh login claude   # optional, for AI blocks; likewise codex or pi
```

The repository does not publish a ready-made image; `build` builds locally from the repository. The
container mounts nothing from the machine — no folders, no logins; the AI login stays in a dedicated
Docker volume. Permanent setup: [Installation](#installation) below or step by step in
[Getting Started](docs/en/GETTING-STARTED.md).

## Documentation

Ordered by depth: the overview first, then the individual building blocks, then the reference.

**Getting started**

| Document | Content |
|---|---|
| [Building blocks](docs/en/baukasten.md) | The question behind it and its four answers as building blocks: what belongs to the core, what is switched on, what you have yourself or connect — and approaches such as loop engineering or spec-driven development built from the same blocks |
| [Getting Started](docs/en/GETTING-STARTED.md) | Try it out without installation (tryout image) and first installation with the guided installer (container, machine, or dev · test · prod) |

**Building blocks in depth**

| Document | Content |
|---|---|
| [Concepts](docs/en/konzepte/README.md) | Eight mechanisms to adopt — gates, self-improvement, hardening, and more — each with a flow diagram, code evidence, and dated measurement |
| [Workflow Guide](docs/en/WORKFLOW-GUIDE.md) | Block types, patterns, timeouts |
| [Integrations](docs/en/integrations.md) | Plugin system, OAuth, provider reference |
| [Audit Protocol](docs/en/audit-workflow-protocol.md) | How findings are created, gate status, TODO linking |
| [Assistant](docs/en/assistant-system.md) | Chat assistant, sessions, suggestions |
| [Evidence](docs/en/belege.md) | Cases where a mechanism found something the normal process had missed — with run ID and number |
| [Diagrams](docs/en/diagramme.md) | Nine pages of flow and architecture diagrams (engine, control and status, scheduler, skills, memory, request path, frontend, deployment, logging, and monitoring) |

**Reference**

| Document | Content |
|---|---|
| [API Reference](docs/en/api-referenz.md) | REST endpoints per area with purpose and module, generated from the controllers |
| [Block Reference](docs/en/block-referenz.md) | Every config key per block type, generated from the editor's schema |
| [Data Model](docs/en/datenmodell.md) | Tables, relations, enums, SQLite specifics |

**Operation and development**

| Document | Content |
|---|---|
| [Deployment](docs/en/deployment.md) | Docker, configuration, environment variables, trust model |
| [Architecture](docs/en/architektur.md) | System overview, layers, data flow, security |
| [Development](docs/en/entwicklung.md) | Local setup, getting into the code, build, tests, conventions |
| [Frontend](docs/en/frontend.md) | Structure and rules: state, API client, i18n, design system and themes |

For agents that call the REST API directly, [AGENTS.md](AGENTS.md) is the starting point.

## Guiding ideas

1. **Deterministic wherever possible**, also because of cost: a shell step costs no tokens; AI is used only where it is needed. Frontier models help with building. What has been built runs deterministically, and the remaining, narrowly scoped AI points can be fed differently (smaller model, different provider).
2. **Reuse and follow up:** templates, pool, sub-workflows, chains, and skills for reuse. The TODO system follows up.
3. **Simple access and overview** through one interface.
4. **Everything can be wired together:** diverse concepts, connect additional sources.
5. **Provider-independent as far as possible:** provider and model can be selected per block. Encapsulating AI in the container has not yet been achieved.

## Research questions

Four mechanisms were built to investigate one question each. Built here means: they run.
It does not mean that the question has been answered.

| Question | Mechanism in the code |
|---|---|
| Can a system identify and work through its own need for improvement without anyone giving it the initial push? | **Self-Improvement Loop**: audit workflow → machine evaluation by a gate (last row of this table) → automatically created TODO → the `TodoTrigger` starts a fix workflow for it → verification. Three layers prevent the loop from chasing itself: a fix run is marked as such and may not create a TODO itself, the number of fix attempts is limited per TODO, and as long as one fix run is running, no second one starts. [Details](docs/en/architektur.md#todo-triggers--self-improvement-loop-v51v52) |
| What does an agent need to know about a project, and where does that knowledge come from if nobody writes it down? | **Workflow Memory**: knowledge is extracted from completed runs and injected as context before the next run. |
| How do you retain control over an agent that plans several steps by itself? | **Agentic blocks** separate Reason and Act — first one call decides what to do, a second executes it — and run against hard budgets: iterations, time, cumulative tokens, repetition detection. |
| Can the quality of an AI output be evaluated by machine instead of by reading it? | **Gates**: blocks output `gate_status: GREEN\|YELLOW\|RED`. RED creates a high-priority TODO, YELLOW a medium-priority one. The evaluation lives in the workflow, not in the platform. |

Not all of this is enabled after installation. Peripheral functions are modules that are fixed at
startup. The `core` preset (default) contains the core — workflows, blocks, schedules, TODOs.
`research` adds the mechanisms from this section and their interfaces: chains (several workflows as
one chain), the chat assistant, learning (analyses of completed runs — metrics and recommendations),
workflow memory (knowledge from runs that is injected into later runs), the Audit Center (the page
where the findings from audit workflows are tracked over time), and BPMN export. `full` enables
everything — including integrations, webhooks, and remote access. The installer asks for this; the
complete list with all modules is in [Architecture](docs/en/architektur.md#module-system-startup-configuration).

## What is in there

As of 2026-09-30, after release v0.7.7. Commit counters and lines from the working tree on that
day (lines via `wc -l`, including blank and comment lines; backend `src/main/java`, frontend `src`
including tests). The commit counter applies only in the working repository (the release handover
condenses the history in the public repository to one commit per release); the other numbers apply
in both, because the handover carries over the release tag's code tree unchanged. Test numbers from
the most recently documented CI run, with their own date in the table:

| | |
|---|---|
| Created since | 2026-02-15, around 2,630 commits in the working repository, one author <!-- doc-facts-check: ignore --> |
| Backend | Java 21 / Spring Boot 3.5, around 73,000 lines, 54 REST controllers, 97 Flyway migrations |
| Frontend | React 19 / TypeScript, around 77,000 lines |
| Tests | Backend 10,476, frontend 1,580 (192 files) — 0 failures, 0 skipped, CI run 35777382298 from 2026-09-22 |
| Building blocks | 20 block types, 54 workflow templates, 33 blueprints (basic scaffolds to fill in), 19 built-in skills, 18 integration providers |

The test numbers come from the CI run named in the table and are therefore verifiable. The
CI definition (`.github/workflows/ci.yml`) has run both suites on every push since 2026-09-12,
in addition to the hygiene gate and secret scan; since 2026-09-21 on Ubuntu 26.04. Since
2026-09-29 a suite is skipped when the change demonstrably does not touch it (pure documentation,
for example); once a week both run without the filter.

## Maturity level and known debt

Known hard limits:

- **SQLite with exactly one connection.** The connection pool is deliberately set to 1 because SQLite
  only has one writer. This serializes all database access and limits parallelism.
- **No multi-tenancy.** One API key, one user, no roles.
- **The AI calls depend on locally installed CLIs** (`claude`, `codex`, `pi`) — there is
  no way around the CLI. The CLIs themselves also accept an API key via environment variable instead
  of a login.

## Before you start it

Shell blocks and AI calls run **with your privileges** — directly on the machine or, for the
instance in a container, with your mounted folders and logins — not in a sandbox per block; the AI
CLIs by default do not ask before using tools. When started from source, backend and frontend listen
on all network interfaces (the instance set up by the installer binds only to `127.0.0.1` by
default), and some endpoints do not need an API key. MySuricates
is built for local operation by a trusted person — for remote access, put a VPN in front of it,
not an open port. Full details:
[Trust model and network exposure](docs/en/deployment.md#trust-model-and-network-exposure).

## Tech stack

| Component | Technology |
|---|---|
| Backend | Spring Boot 3.5, Java 21 |
| Frontend | React 19, TypeScript 6, Vite 8 |
| Database | SQLite (WAL mode) + Flyway |
| Container | Docker (Java Docker Client 3.7.1) — optional, only for shell blocks with `container_image` |
| Styling | Tailwind CSS 4, CSS variables |
| State | Zustand 5 + TanStack Query 5 |
| WebSocket | STOMP over SockJS |
| Security | API key auth, rate limiting, admission check before every run; optional HMAC-signed workflow seals |
| i18n | German / English |
| PWA | Vite PWA Plugin |
| Integrations | Google Calendar, Gmail, Jira, Confluence, Trello, GitHub, Gitea/Forgejo, GitLab, Taskwarrior — module, in the `full` preset |

Execution happens where the instance runs — in the container (recommended) or directly on the
machine: shell blocks through the user's shell, AI blocks through the installed CLI (mounted into
the container from the machine). A dedicated container per shell block (`container_image`) exists
only if the Docker socket is explicitly made available. What gets into the container from the AI
providers is controlled by a switch: by default, only the programs and, per provider, the login
(for Claude, a one-time token from `claude setup-token`), not the machine's settings, plugins,
memory, or histories. If you want to share less or everything, see the variants under [AI providers in the container](docs/en/deployment.md#ai-providers-in-the-container).

## Installation

> New machine (Linux, macOS, Windows via WSL2), possibly without Java/Node/Docker? → **[Getting Started](docs/en/GETTING-STARTED.md)**
> guides you through the setup step by step. Short version: `git clone https://github.com/FLSEST/MySuricates.git && cd MySuricates && ./scripts/setup.sh`.

**Requirement for AI blocks:** At least one of the supported command lines must be installed and
logged in on the machine — `claude` (Anthropic), `codex` (OpenAI), or `pi`. Without one of them,
only the deterministic blocks run (shell, branch, loop). The installer shows which CLIs it found and
whether they are logged in, but does not require any — without a CLI, AI blocks fail only at runtime.

Set up permanently:

```bash
git clone https://github.com/FLSEST/MySuricates.git
cd MySuricates
./scripts/setup.sh              # guided, in English; in German: ./scripts/setup.sh --lang de
```

The installer asks for the path — an instance in a container (recommended), directly on the machine,
or staged **Dev · Test · Prod** — and for the modules, installs missing tools after confirmation,
builds, and starts the instance. Afterwards: http://localhost:9090, the API key is shown by
`scripts/instance.sh key --name main`. The instance listens only on `127.0.0.1`; the Docker socket
is mounted only on explicit request.

If you develop MySuricates itself: `./scripts/setup.sh --from-source --start` sets it up, builds
the runtime JAR, and immediately starts `./scripts/start-mysuricates-dev.sh` with it — backend on
:9090 and Vite with hot reload on :3000, in one instance. Without `--start`, the installer ends with
the same command as a hint; from then on, it is the way to start and stop (`stop`) the dev instance
and to rebuild after a change (`restart`). It is idempotent and does not start a second instance.
See [Development](docs/en/entwicklung.md).

<!-- Screenshot placeholder -->
<!-- ![Dashboard](docs/screenshots/dashboard.png) -->

## Contributions and support

Issues remain open — but this repository is a research project by a single developer, not a
maintained product: no support, no commitment to response times, not even for pull requests.
If you still want to contribute something, it is best to get in touch via an issue first.

Please do **not** report security vulnerabilities as public issues; use the reporting channel in
[SECURITY.md](SECURITY.md) instead (GitHub private vulnerability reporting).

---

> 🌍 **README.de.md is the source, `README.md` the translation.** Content changes belong in the German file. The author generates the English version from it in their MySuricates instance using the "README DE↔EN Sync" workflow; the repository holds only the result, not the workflow. So if you contribute from outside, change the German source and adjust `README.md` by hand in the same commit — or change only the German one and note in the pull request that the translation still needs to follow.

<!-- synced-from: 23539d5c1316 sha256:ffd6c2810119343e6ff08036e322c524815ba0f7fa306dedec4e930f18340293 -->
