# Building blocks

> **How do I work with strong LLMs so that I can put my own limited abilities to use as well?**

MySuricates grew out of this question. The answers are building blocks, and you don't need all of
them: a small core is always there, the rest is switched on – or you connect what you already have.
Determinism is the common thread, because you want to reuse what you have built.

The markers in the tables:

- **Core** – always there (preset `core`, the default)
- **+research**, **+full** – modules switched on at startup
  ([list of all modules](architektur.md#module-system-startup-configuration))
- **Switch** – hardening, set at startup

## The four answers

### 1. Nothing disappears into the void

Tasks and side threads that come out of a piece of work are not lost. On top of that, workflows
remember the things that were forgotten during a session.

| Building block | | What for |
|---|---|---|
| Run history | Core | Every step records what went in and what came out. |
| Versions | Core | Every change to a workflow is kept and can be restored. |
| TODOs | Core | Whatever a run finds becomes a task. |
| API for agents | Core | An AI session (Claude Code or Codex, for example) creates TODOs and starts workflows – the project knows MySuricates. → [AGENTS.md](../../AGENTS.md) |
| Memory | +research | Insights from runs flow into later runs. → [Workflow memory](konzepte/workflow-memory.md) |
| Audit | +research | Findings with a history, including those accepted on purpose. → [Audit protocol](audit-workflow-protocol.md) |

### 2. Combine deterministic and non-deterministic – and make it visible

| Building block | | What for |
|---|---|---|
| Workflow made of steps | Core | Fixed steps (shell, branch, loop), AI steps and human steps (approval, input) in one flow. → [Workflow guide](WORKFLOW-GUIDE.md) |
| Step kind in the run | Core | Each step shows whether an AI, a fixed rule or a person acted. |
| Traffic light | Core | A step outputs `GREEN`, `YELLOW` or `RED` – a verdict further steps can depend on. → [Gates](konzepte/gates.md) |
| Agent step with a budget | Core | An open-ended AI task runs against fixed limits: iterations, time, tokens. → [Agentic budgets](konzepte/agentische-budgets.md) |
| Definition check | Core | Reports construction errors in a workflow before it runs. |
| Diagram (BPMN) | +research | The same flow as a diagram, also while a run is in progress – readable without code, exchangeable with BPMN tools. |
| Seal | Switch | An approved flow runs only the way it was approved. → [Hardening](konzepte/haertung.md) |

### 3. Independent of providers

| Building block | | What for |
|---|---|---|
| AI providers | Core | Claude and Codex directly, further models through Pi – selectable per workflow or per step. Switch the provider and keep your flow. |
| Model per step | Core | A narrowly scoped AI step can get a smaller model or a different provider. |
| Connections | Core / +full | Use your own building blocks or connect an existing system – see [Own or connect](#own-or-connect). |

### 4. Many more ideas grew from this

| Building block | | What for |
|---|---|---|
| Templates, pool, blueprints | Core | Take ready-made workflows and adapt them; blueprints are scaffolds to fill in. → [Pool](../../workflow-pool/README.md) |
| Triggers | Core | Schedule, follow-up to a run, follow-up to a TODO. → [Access paths](konzepte/zugaenge.md) |
| Sub-workflows | Core | One workflow calls another, also once per entry of a list. |
| Goals | Core | Bundle TODOs and workflows towards one result. |
| Dashboard report | Core | A workflow puts its result on the dashboard as a tile. |
| Chains | +research | Several workflows in a row. |

## Own or connect

In some cases one building block makes sense because you already have the other one, in others not.
If your tasks already live in Jira, you connect Jira instead of using the built-in TODOs.

| Topic | own | connect, for example |
|---|---|---|
| Tasks | TODOs, goals | Jira, GitHub, Trello, … |
| Triggers | Schedule, follow-up triggers | Webhook, … |
| Notifications | User interface | ntfy, Slack, Telegram, … |
| Knowledge | Memory, document step | Confluence, feeds, … |
| Appointments | Calendar with the scheduled runs | CalDAV, Google Calendar, iCal feeds, … |
| Code | Project folder | GitHub, GitLab, Gitea, … |

The systems named here are examples, not a complete list. Which connections have been checked against
the real service is listed under [Available providers](integrations.md#available-providers).

### Three ways to connect something

1. **Shell step** (Core) – with the tool the system itself provides: its command-line program or a
   call to its web interface.
2. **HTTP API** (+full) – describe a web interface, no code needed.
   → [HTTP API connection](integrations.md#http-api-connection-http-api)
3. **Own provider** (code) – for connections with sign-in and their own actions; the bundled
   providers serve as templates.
   → [Adding a new provider](integrations.md#adding-a-new-provider-step-by-step)

## Approaches built from the same blocks

There are a great many ways to tackle problems with AI, and different projects have chosen different
approaches. Here an approach is a combination of the same building blocks – you can try several side
by side. Runs record which steps ran, how many tokens they needed and where things failed, so on the
same task you can see what works.

### Loop engineering

A run checks and finds something, it becomes a TODO, a follow-up run repairs it, and the check runs
again. Building blocks: traffic light → TODO → TODO trigger → repair workflow. The loop is limited by a
maximum number of attempts, and a repair run does not create a TODO itself.

To look at: the blueprints “Traffic light”, “Started by a TODO trigger” and “Loop with a gate”, and
“SaaS — Auto-Fix” in the pool. More: [Self-improvement](konzepte/selbstverbesserung.md).

### Spec-driven development

It starts with a description (spec). From it comes a plan that gets approved; each part is then
implemented and checked on its own. Building blocks: document step reads the spec → AI step breaks it
down → approval → one sub-workflow per part → tests as the fixed check.

To look at in the pool: “SaaS — Spec to Build-Plan” → “SaaS — Feature Loop” → “SaaS — Implement One
Feature”. They expect a SaaS factory in the project, but can be read as a pattern without one.

### Orchestrating agents

Several AI steps or workflows work one after another or once per entry of a list, each with its own
limits; a second provider can review the result without writing anything itself. Building blocks:
sub-workflow per entry, agent step with a budget, isolated clone, chains (+research).

To look at in the pool: the chain “TODO batch” made of “TODO batch: triage” and “TODO batch: work
through”, plus “SaaS — Feature Loop”. More: [Agentic budgets](konzepte/agentische-budgets.md).

Further approaches come from putting the same building blocks together differently.

## Two paths through these docs

**I build workflows.** The [workflow guide](WORKFLOW-GUIDE.md) for steps, patterns and pitfalls, the
blueprints in the user interface, the [concepts](konzepte/README.md) to adopt, and
[integrations](integrations.md) to connect things.

**I use, check, approve.** That can be anyone, not only people who develop software.
[Getting Started](GETTING-STARTED.md) to begin with; in the user interface the “Simple Mode”, the
“Approvals” page with everything waiting for a decision, and the run view with step kinds and the
diagram. Hints appear where you need them.

For reference: [Architecture](architektur.md), [API reference](api-referenz.md),
[Data model](datenmodell.md), [Deployment](deployment.md), [Development](entwicklung.md).
