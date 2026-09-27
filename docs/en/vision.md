# MySuricates — Vision

MySuricates is a self-hosted application made of a backend and a web interface, in which work
runs as chains of blocks. This document does not describe what it does today — that is in
[README.md](../../README.md) — but where it is meant to go.

> Short identifiers in parentheses such as `(182bd224)` or `TODO 63ecc124` are
> provenance markers: commits of this repository, or items in the accompanying
> MySuricates instance. They record when and why something came about — from the
> outside, only the commit can be looked up.

## The Idea

MySuricates pursues two goals:

1. **A universal problem solver** — for everyday life and work
2. **A self-improving AI platform** — that gets smarter with every execution

Not just for developers. For anyone who wants to solve real problems.

---

## Guiding ideas

1. **Deterministic where possible**, also because of cost: a shell step costs no tokens, AI only where it is needed. Frontier models help with building. What is built runs deterministically, and the remaining, narrowly scoped AI steps can be fed differently (a smaller model, another provider).
2. **Reuse and follow up:** templates, pool, sub-workflows, chains and skills for reuse. The TODO system follows up.
3. **Easy access and overview** through a user interface.
4. **Everything plugs together:** various concepts, connecting further sources.
5. **Independent of the provider, as far as possible:** provider and model can be chosen per block. Encapsulating the AI in a container has not been achieved yet.

---

## The Principle of Continuous Approximation

Perfection is not a state — it is a direction. A system that considers perfection achievable will eventually stop learning. This one won't.

Every run produces data. Every iteration refines the next attempt. Learning (feedback from runs), Memory (knowledge per workflow), Factory (generates and tests workflows) and Wishes (from a wish in your own words to a finished process) — described in [Pillar 2](#pillar-2-self-improving-ai-platform) — are not features — they are the mechanisms of this continuous approximation. The system will never be perfect. But with every step it gets closer to perfection — and that's exactly the point.

---

## Pillar 1: Universal Problem Solver

MySuricates solves real problems — in personal life and in the professional world. Workflows are the building blocks, Chains combine them into complex solutions.

### Professional

- **Morning Briefing**: News, calendar, priorities — everything at a glance
- **Meeting Minutes**: Recording → Summary → Action Items → Distribution
- **Social Media Calendar**: Content ideas → Texts → Schedule → Posting
- **Security Audit**: Scan codebase → Assess vulnerabilities → Report
- **Code Review**: Automatic analysis → Improvement suggestions → PR comments

### Personal

- **Application Coach**: Analyze job posting → Cover letter → Tailor resume
- **Travel Planning**: Budget, interests, timeframe → complete travel plan
- **Vocabulary Songs**: Frequency-based song generation for language learning
- **Event Organization**: Guest list, location, schedule, invitations

### Combined (Chains)

- **Content Machine**: Research → Article → Social Posts → Newsletter
- **Website Audit**: Performance → SEO → Accessibility → Overall Report
- **Office Morning Routine**: Check emails → Calendar → Briefing → Prioritize todos

The core: Every workflow is a solved problem. Every chain is a solved problem chain. The template library grows — and with it the number of problems MySuricates can solve out-of-the-box.

---

## Pillar 2: Self-Improving AI Platform

The system improves with every execution. Four mechanisms drive the evolution:

### Wishes — From Intent to Solution

The user says what they want in natural language. The system decomposes, builds, tests, executes, evaluates, and iterates.

```
User: "I need a weekly security report for my project"

MySuricates:
  1. Decompose → 3 sub-tasks identified
  2. Build → Workflow with shell + agentic blocks
  3. Pre-flight verify → Syntax OK, dependencies available
  4. Execute → Run on test instance
  5. Evaluate → Success rate, output quality
  6. Iterate → Fix issues, re-run if needed
```

The test instance is a second, isolated MySuricates instance (Docker container, port 8090,
[deployment.md → Three-Environment Setup](deployment.md#three-environment-setup)); experiments run
there without touching your own data. The Decompose-Approval Gate lets the user review and adjust the plan before execution. Pre-flight verification catches errors before they waste compute.

### Factory — Workflow Generation Pipeline

Topic in, verified template out:

1. **Generate**: AI creates a workflow from a topic description
2. **Test**: Auto-deploy to the test instance and run
3. **Verify**: Check success, output quality, cost
4. **Promote**: Successful workflows become reusable templates

Multi-provider support (Claude + Codex + Pi) means the best model for each generation task.

### Learning — Feedback Loop

Every workflow run produces data that flows back into generation:

- **Success rates** per template, block type, and model
- **Strategy patterns** — what worked, what didn't
- **Cost tracking** — token usage per block and workflow
- **Cross-project recognition** — reusable patterns across different projects

Learning feedback is injected into prompts, making each generation smarter than the last.

### Memory — Accumulated Knowledge

Workflows accumulate knowledge across runs:

- Knowledge extraction from completed runs
- Summarization via Claude or Codex
- Active memories injected into future executions
- Per-workflow knowledge base that grows over time

---

## What's Already Built

| Component | Status |
|-----------|--------|
| Block-based Workflows (20 block types) | Done |
| Docker Container Execution | Done |
| Agentic Blocks (Reason → Act → Observe) | Done |
| Workflow Chains (sequential composition) | Done |
| Workflow Factory (AI-generated + Auto-Test) | Done |
| Wishes (Intent → Decompose → Execute → Evaluate) | Done |
| Decompose-Approval Gate | Done |
| Pre-flight Verification + Auto-Fix + AutoRepair Config | Done |
| Learning Feedback in Prompts | Done |
| Cross-Project Workflow Recognition | Done |
| 54 Built-in Templates (Dev + Office + Everyday) | Done |
| 19 Built-in Skills (Code Review, Test Generator, Security Analyzer, etc.) | Done |
| Staging (Dev + Stable + Test) | Done |
| Several AI providers (Claude + Codex + Pi) | Done |
| Assistant System (contextual floating chat with page suggestions) | Done |
| Gate Status System (checking workflows report GREEN/YELLOW/RED; with auto-TODO, RED — in mode auto also YELLOW — becomes a TODO) | Done |
| Agent Instructions System (base and per-workflow instructions) | Done |
| CompletionTrigger System (automatic actions after workflow completion) | Done |
| TodoTrigger System (Self-Improvement Loop: TODO → Fix Workflow → Verify → Close) | Done |
| Notification System (NotificationConfig) | Done |
| Workflow Memory (knowledge extraction and accumulation across runs) | Done |
| Template Verification (quality checks for workflow templates) | Done |
| Workflow Conversion (agentic runs → deterministic workflows) | Done |
| Run Resumption (resume from last step after interruption) | Done |
| Session-to-Workflow Import (Claude sessions as workflows) | Done |
| Extended Schedule Control (end conditions, conflict behavior) | Done |
| In-App Documentation (DocsController, contextual help) | Done |
| Onboarding Profiles (guided setup for new users) | Done |
| Workflow Pool & PoolBrowser (template marketplace browsing) | Done |
| Workflow Versioning (version history + restore) | Done |
| Simple Mode (operating surface for newcomers who install it themselves; hand-off to Pro visible) | Done |
| Insights Page (analytics dashboard) | Done |
| Chain Templates (pre-built chain templates) | Done |

---

## Roadmap

### Next: Cost & Intelligence

- **Cost estimation before execution** — the user sees the expected token costs before a Wish runs
- **Model selection per Wish** — automatic or manual choice of the appropriate model
- **Wish analytics dashboard** — add costs and trends of Wishes (success rate and status counts exist already)

### Later: Ecosystem

- **External skill packages** — installable packages (Git repo/ZIP) with new Skills and Capabilities
- **MCP servers as skills** — automatically make MCP servers available as Skills/Capabilities
- **Integration plugins** — integrations as loadable packages; today the providers of the
  `integration` block (Slack, Jira, GitHub and others) are built in
- **Workflow marketplace** — share and discover community templates

### Done from earlier roadmap steps

- **Refining on its own** — a Wish can approve and refine its plan without asking (option "Auto-Refine")
- **Iteration history** — every pass of a Wish is kept, with a diff view against the previous one
- **Crystallize** — a successfully completed Wish becomes a reusable template
- **Finding templates** — search and take over from the workflow pool (bundled workflow definitions)

---

## Architecture Overview

```
                    User
                      |
              "I want to solve X"
                      |
                  [ Wish API ]
                      |
        Decompose → Approve → Execute
                                |
                    [ Workflow Engine ]
                    /        |        \
              Blocks      Chains    Templates
             /  |  \
        Shell  Agentic  Human-Input  ...
                |
        [ Host Claude/Codex/Pi CLI ]
                |
        Reason → Act → Observe
                |
        [ Learning + Memory ]
                |
        Feedback → Next Generation
```

## Technical Stack

- **Backend**: Spring Boot 3.5, Java 21, SQLite, Flyway
- **Frontend**: React 19, TypeScript, TailwindCSS, TanStack Query, Zustand
- **Execution**: Docker containers (Node, Python, DevOps, Java agent images), Host Claude CLI, Host Codex CLI, Host Pi CLI
- **Deployment**: Dev (source) + Stable (Docker, port 9091) + Test (Docker, port 8090)
