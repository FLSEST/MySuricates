> *Last updated: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

# MySuricates — Vision

## The Idea

MySuricates pursues two goals:

1. **A universal problem solver** — for everyday life and work
2. **A self-improving AI platform** — that gets smarter with every execution

Not just for developers. For anyone who wants to solve real problems.

---

## The Principle of Continuous Approximation

Perfection is not a state — it is a direction. A system that considers perfection achievable will eventually stop learning. This one will not.

Every run produces data. Every iteration refines the next attempt. Learning, Memory, Factory, and Wishes are not features — they are the mechanisms of this continuous approximation. The system will never be perfect. But with every step it gets closer to perfection — and that is exactly the point.

---

## Pillar 1: Universal Problem Solver

MySuricates solves real problems — in personal life and in the professional world. Workflows are the building blocks, Chains combine them into complex solutions.

### Professional

- **Morning Briefing**: News, calendar, priorities — everything at a glance
- **Meeting Minutes**: Recording → Summary → Action Items → Distribution
- **Social Media Calendar**: Content ideas → Copy → Schedule → Posting
- **Security Audit**: Scan codebase → Assess vulnerabilities → Report
- **Code Review**: Automatic analysis → Improvement suggestions → PR comments

### Personal

- **Application Coach**: Analyze job posting → Cover letter → Adapt resume
- **Travel Planning**: Budget, interests, timeframe → Complete travel plan
- **Vocabulary Songs**: Generate frequency-based songs for language learning
- **Event Organization**: Guest list, venue, schedule, invitations

### Combined (Chains)

- **Content Machine**: Research → Article → Social Posts → Newsletter
- **Website Audit**: Performance → SEO → Accessibility → Overall Report
- **Office Morning Routine**: Check emails → Calendar → Briefing → Prioritize todos

The core idea: Every workflow is a solved problem. Every chain is a solved problem chain. The template library grows — and with it the number of problems MySuricates can solve out-of-the-box.

---

## Pillar 2: Self-Improving AI Platform

The system gets better with every execution. Four mechanisms drive the evolution:

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

The Decompose-Approval Gate lets the user review and adjust the plan before execution. Pre-flight verification catches errors before they waste compute.

### Factory — Workflow Generation Pipeline

Topic in, verified template out:

1. **Generate**: AI creates a workflow from a topic description
2. **Test**: Auto-deploy to the test instance and run
3. **Verify**: Check success, output quality, cost
4. **Promote**: Successful workflows become reusable templates

Multi-provider support (Claude + Codex) means the best model for each generation task.

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

## What Has Been Built (v0.6.1)

| Component | Status |
|-----------|--------|
| Block-based workflows (12 block types) | Done |
| Docker container execution | Done |
| Agentic Blocks (Goal → Reason → Act → Observe) | Done |
| Workflow Chains (sequential composition) | Done |
| Workflow Factory (AI-generated + auto-test) | Done |
| Wishes (Intent → Decompose → Execute → Evaluate) | Done |
| Decompose-Approval Gate | Done |
| Pre-flight Verification + Auto-Fix | Done |
| Learning feedback in prompts | Done |
| Cross-project workflow recognition | Done |
| 44 built-in templates (dev + office + everyday) | Done |
| 16 built-in skills (code review, test generator, OCR, etc.) | Done |
| Staging (Dev + Stable + Test) | Done |
| Multi-provider (Claude + Codex) | Done |
| Assistant system (contextual floating chat with page suggestions) | Done |
| Gate Status System (quality gates, AutoTodo integration) | Done |
| Agent Instructions System (base and per-workflow instructions) | Done |
| CompletionTrigger system (automatic actions after workflow completion) | Done |
| Notification system (NotificationConfig) | Done |
| Workflow Memory (knowledge extraction and accumulation across runs) | Done |
| Template Verification (quality checks for workflow templates) | Done |
| Workflow Conversion (agentic runs → deterministic workflows) | Done |
| Run resumption (continue from last step after interruption) | Done |
| Session-to-workflow import (Claude sessions as workflows) | Done |
| Extended schedule control (end conditions, conflict behavior) | Done |
| In-app documentation (DocsController, contextual help) | Done |

---

## Roadmap

### Phase 2: Cost & Intelligence (next step)

- **Cost estimation before execution** — User sees expected token costs before a Wish runs
- **Model selection per Wish** — Automatic or manual choice of the appropriate model
- **Auto-refine without user intervention** — Wish iterates independently until target quality is reached
- **Iteration history with delta tracking** — Traceable what changed between iterations

### Phase 3: Crystallize & Discovery

- **Crystallize Wizard** — Convert successful Wishes into reusable templates
- **Template Discovery & Search** — Find templates by problem, not by name
- **Wish Analytics Dashboard** — Visualize success rates, costs, trends

### Long-term: Ecosystem

- **External skill packages** — Installable packages (Git repo/ZIP) with new skills + capabilities
- **MCP servers as skills** — Automatically make MCP servers available as skills/capabilities
- **Integration plugins** — Slack, Jira, GitHub, Email as workflow blocks
- **Workflow Marketplace** — Share and discover community templates

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
        [ Host Claude/Codex CLI ]
                |
        Goal → Reason → Act → Observe
                |
        [ Learning + Memory ]
                |
        Feedback → Next Generation
```

## Technical Stack

- **Backend**: Spring Boot 3.4, Java 21, SQLite, Flyway
- **Frontend**: React 18, TypeScript, TailwindCSS, TanStack Query, Zustand
- **Execution**: Docker containers (Python/Node/Go), Host Claude CLI, Host Codex CLI
- **Deployment**: Dev (source) + Stable (Docker, port 9091) + Test (Docker, port 8090)
