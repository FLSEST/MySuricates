> *Last updated: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

# Workflow Memory — Architecture & Data Flow

## Overview

The Workflow Memory feature extracts reusable knowledge from past workflow runs
and provides it as context for future executions. It supports two providers:
**Claude CLI** and **OpenAI Codex CLI**, both running directly on the host.

---

## 1. Data Flow Diagram — Extracting Knowledge

```
 Browser (WorkflowMemoryPanel.tsx)
 ┌──────────────────────────────────────────────────────────────────┐
 │  POST /api/v1/workflows/{id}/memory/extract                     │
 │  Body: { provider: "CLAUDE" | "CODEX", runIds?: [...] }         │
 └──────────────────────────┬───────────────────────────────────────┘
                            │
                            ▼
 Backend (Spring Boot, Port 9090)
 ┌──────────────────────────────────────────────────────────────────┐
 │  WorkflowMemoryController.extract()                             │
 │  ├── Error Handling: 400 / 422 / 502                            │
 │  └──────────────┬───────────────────────────────────────────────│
 │                 ▼                                                │
 │  WorkflowMemoryService.extractKnowledge()                       │
 │  │                                                               │
 │  ├─ 1. Load runs ◄──── WorkflowRunRepository (SQLite)           │
 │  │     └── Last 10 runs or specific run IDs                     │
 │  │                                                               │
 │  ├─ 2. Collect StepResults                                      │
 │  │     └── All steps from all loaded runs                       │
 │  │                                                               │
 │  ├─ 3. Preprocessing ◄── ConversationPreprocessor               │
 │  │     ├── Compress code blocks (>30 lines)                     │
 │  │     ├── Compress data dumps (>20 lines)                      │
 │  │     ├── Remove meta comments                                 │
 │  │     ├── Consolidate repeated errors                          │
 │  │     └── Compress agentic iterations                          │
 │  │     Reduction: typically 50-70%                               │
 │  │                                                               │
 │  ├─ 4. getSummarizer(provider)                                  │
 │  │     ├── "CLAUDE" ──► ClaudeSummarizer                        │
 │  │     └── "CODEX"  ──► CodexSummarizer                        │
 │  │                                                               │
 │  └─ 5. Save result ──► WorkflowMemoryRepository (SQLite)        │
 │        └── WorkflowMemory { title, content, tags, provider }    │
 └──────────────────────────────────────────────────────────────────┘
```

---

## 2. Summarizer Architecture

```
                  KnowledgeSummarizer (Interface)
                  │ summarize(preprocessed) → SummarizationResult
                  │
          ┌───────┴──────────┐
          │                  │
  ClaudeSummarizer    CodexSummarizer
          │                  │
  HostClaudeService   HostCodexService
          │                  │
          ▼                  ▼
  ┌──────────────┐   ┌──────────────┐
  │ claude -p    │   │ codex exec   │
  │ --max-turns 1│   │ -o result.txt│
  │ --output-    │   │ --skip-git-  │
  │  format      │   │  repo-check  │
  │  stream-json │   │ -C <workdir> │
  └──────┬───────┘   └──────┬───────┘
         │                   │
         ▼                   ▼
  ~/.autotrigger/     ~/.autotrigger/
  summarization/      summarization/
  claude/             codex/
  └── CLAUDE.md       └── AGENTS.md
  (Instructions)      (Instructions)
```

### Provider Comparison

| Property          | ClaudeSummarizer              | CodexSummarizer               |
|-------------------|-------------------------------|-------------------------------|
| CLI               | `claude -p`                   | `codex exec`                  |
| Max Turns         | 1 (no tool use)               | — (codex exec default)        |
| Output Method     | Parse stream JSON             | `-o` flag (file)              |
| Instructions      | `CLAUDE.md` in working dir    | `AGENTS.md` in working dir    |
| Auth              | `~/.claude/` (CLI-native)     | `~/.codex/` (CLI-native)      |
| Timeout           | `automation.summarization.timeout-seconds` (default: 120s) |

---

## 3. Data Flow Diagram — Using Memory During Workflow Execution

```
  WorkflowExecutor.execute(workflow)
  │
  ├─ 1. writeActiveMemoryToProject(workflowId, projectPath)
  │     │
  │     ├── Read active memory from DB
  │     │   └── WorkflowMemoryRepository.findByWorkflowIdAndActiveTrue()
  │     │
  │     ├── If present:
  │     │   └── Writes .claude/rules/project-memory.md into the project
  │     │
  │     └── If not present:
  │         └── Deletes old project-memory.md (cleanup logic)
  │
  ├─ 2. Block execution starts
  │     └── Claude CLI reads CLAUDE.md + .claude/rules/*.md automatically
  │         └── Workflow memory is available as context
  │
  └─ 3. Run result is saved
        └── WorkflowRun + StepResults in SQLite
            └── Can be used later for new memory extraction
```

---

## 4. Data Model

```
┌─────────────────────────┐       ┌─────────────────────────┐
│     workflow_memory      │       │      workflow_run        │
├─────────────────────────┤       ├─────────────────────────┤
│ id          TEXT PK      │       │ id          TEXT PK      │
│ workflow_id TEXT FK       │◄──────│ workflow_id TEXT FK       │
│ title       TEXT          │       │ status      TEXT          │
│ content     TEXT (MD)     │       │ started_at  TIMESTAMP    │
│ tags        TEXT (JSON)   │       │ finished_at TIMESTAMP    │
│ source_run_ids TEXT (JSON)│───────│ steps       (embedded)   │
│ provider    TEXT          │       │   ├── step_index INT     │
│ active      BOOLEAN       │       │   ├── input     TEXT     │
│ created_at  TIMESTAMP    │       │   ├── output    TEXT     │
└─────────────────────────┘       │   ├── status    TEXT     │
                                   │   └── metadata  JSON     │
 provider: "CLAUDE" | "CODEX"     └─────────────────────────┘
 active: only 1 per workflow

 Relationship: source_run_ids references
 the run IDs that were used for extraction
```

---

## 5. Directory Structure

```
~/.autotrigger/summarization/           ← Configurable via application.yml
├── claude/                             ← Working dir for ClaudeSummarizer
│   └── CLAUDE.md                       ← Instructions (copied from resources)
└── codex/                              ← Working dir for CodexSummarizer
    └── AGENTS.md                       ← Instructions (copied from resources)

ai-automation-backend/src/main/resources/
├── agent-instructions.md               ← Base instructions (block execution)
└── summarization/
    ├── claude-summarizer.md            ← Template for CLAUDE.md
    └── codex-summarizer.md             ← Template for AGENTS.md
```

`SummarizationSetupService` copies the templates into the working directories
on backend startup. Changes to the resource files are picked up on the next restart.

---

## 6. Configuration (application.yml)

```yaml
automation:
  summarization:
    base-dir: ${AUTOMATION_SUMMARIZATION_DIR:~/.autotrigger/summarization}
    timeout-seconds: ${AUTOMATION_SUMMARIZATION_TIMEOUT:120}
```

---

## 7. API Endpoints

| Method   | Path                                              | Description                              |
|----------|---------------------------------------------------|------------------------------------------|
| `GET`    | `/api/v1/workflows/{id}/memory`                   | All memories of a workflow                |
| `POST`   | `/api/v1/workflows/{id}/memory/extract`            | Extract knowledge (async, ~30-60s)       |
| `PUT`    | `/api/v1/workflows/{id}/memory/{memId}`            | Update title/content                     |
| `PUT`    | `/api/v1/workflows/{id}/memory/{memId}/activate`   | Activate memory                          |
| `DELETE` | `/api/v1/workflows/{id}/memory/{memId}`            | Delete memory                            |

### Error Responses (POST /extract)

| HTTP Status | Cause                                        |
|-------------|----------------------------------------------|
| 201         | Successfully extracted                       |
| 400         | Unknown provider                             |
| 422         | No runs / no steps found                     |
| 502         | CLI call failed (timeout, crash)             |
