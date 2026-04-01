> *Last updated: 28. März 2026, 19:45 Uhr — Workflow: MySuricates Docs aktualisieren | Run: 01102949-3825-4cd9-b4b9-8aa84917c4c2*

# Workflow Building Guide

Practical guide for creating effective workflows in MySuricates. Covers all block types, proven patterns, timeout configuration, and common pitfalls.

**Target audience**: AI agents (Claude, Codex) and developers who create or debug workflows.

---

## 1. Quick Start

A workflow is a sequence of **blocks** that are executed one after another. Each block has a `type` and a `config` map. The output of each block is available to the next via `{{ previous_output }}`.

### Minimal Example: Two-Block Workflow

```json
{
  "name": "Simple File Analysis",
  "projectId": "<project-uuid>",
  "blocks": [
    {
      "type": "shell",
      "config": {
        "command": "find . -name '*.java' | head -20"
      }
    },
    {
      "type": "simple",
      "config": {
        "command": "Analyze these files and describe the project structure:\n\n{{ previous_output }}"
      }
    }
  ]
}
```

### API Endpoint

```
POST /api/v1/workflows
```

All workflow CRUD and execution operations are under `/api/v1/workflows`. A run is started with `POST /api/v1/workflows/{id}/execute`.

### Context Variables

Pass variables at start:

```
POST /api/v1/workflows/{id}/execute
{
  "context": {
    "filepath": "/path/to/file.md",
    "language": "German"
  }
}
```

Access in block configs with `{{ filepath }}` or `{{ language }}`.

---

## 2. Block Type Reference

MySuricates supports 12 block types. Each block has a `type` and a `config` map.

### Common Block Properties

In addition to its type-specific config fields, every block also supports these common properties:

| Field | Type | Default | Description |
|---|---|---|---|
| `on_failure` | string | `"stop"` | Behavior on failure: `"stop"` (terminate workflow) or `"continue"` (proceed with next block) |
| `skillIds` | string[] | `[]` | Optional list of skill IDs activated for this block (additive to workflow-level skills) |

**Default behavior**: When a block fails (status `FAILED`), the workflow stops and the run is marked as `FAILED`. With `"on_failure": "continue"`, the workflow proceeds with the next block.

**Per-block skills**: Blocks can activate additional skills via the `skillIds` field. These are merged with the workflow-level `activatedSkills` before execution and restored to the baseline after the block completes. This allows individual blocks to use specialized skills without affecting other blocks.

```json
{
  "type": "simple",
  "config": {
    "command": "Analyze the code for security vulnerabilities"
  },
  "skillIds": ["builtin-code-review", "builtin-security-analyzer"]
}
```

---

### `simple` — AI Text Processing

Sends the prompt to the Claude CLI on the host. No shell execution.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `command` | string | **required** | The prompt sent to Claude |
| `timeout_seconds` | int | 300 | Claude CLI timeout |

```json
{
  "type": "simple",
  "config": {
    "command": "Analyze the following code and list improvement suggestions:\n\n{{ previous_output }}"
  }
}
```

**When to use**: Text analysis, summarization, code review, content generation — any task that requires AI reasoning without shell access.

---

### `shell` — Shell Command Execution

Executes a shell command on the host or inside a Docker container.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `command` | string | **required** | Shell command(s) to execute |
| `container_image` | string | null | Docker image (see execution environment) |
| `timeout_seconds` | int | 300 | Execution timeout |
| `mount_docker_socket` | bool | false | Mount Docker socket into the container (only effective when `container_image` is set) |

```json
{
  "type": "shell",
  "config": {
    "command": "set -euo pipefail\ngit log --oneline -20"
  }
}
```

**When to use**: Running scripts, collecting data, build commands, file operations — anything that requires deterministic shell execution.

---

### `agentic` — Goal-Driven AI Agent

Executes an autonomous Goal-Reason-Act-Observe loop. The most powerful but also most expensive block type.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `goal` | string | **required** | What the agent should accomplish |
| `success_criteria` | string | `""` | How success is measured |
| `max_iterations` | int | 10 | Hard iteration cap |
| `iteration_timeout_seconds` | int | 300 | Wall-clock timeout per iteration |
| `total_timeout_seconds` | int | 1800 | Overall block timeout (30 min) |
| `max_total_tokens` | int | 200000 | Token budget (input+output across all iterations) |
| `max_repeated_actions` | int | 3 | Abort after N identical actions |
| `require_approval` | bool | false | Approval gate before each action |
| `timeout_seconds` | int | null | Claude CLI timeout per call (REASON + ACT) |

```json
{
  "type": "agentic",
  "config": {
    "goal": "Create a portfolio website in portfolio/ based on {{ filepath }}.",
    "success_criteria": "index.html exists and contains all sections.",
    "max_iterations": 8,
    "timeout_seconds": 600,
    "max_total_tokens": 300000
  }
}
```

**When to use**: Complex multi-step tasks that require judgment — creating files, fixing code, building projects. See [Agentic Block Deep Dive](#8-agentic-block-deep-dive) for details.

---

### `retry` — Retry on Failure

Retries a command up to N times with a pause between attempts.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `command` | string | **required** | Command to execute |
| `max_retries` | int | 3 | Maximum retry attempts |
| `delay` | int | 5 | Pause between attempts (seconds) |
| `exponential_backoff` | bool | false | Double the pause with each attempt |
| `execution_mode` | string | `"claude"` | `"claude"` (AI) or `"shell"` |
| `timeout_seconds` | int | 300 | Execution timeout per attempt |

```json
{
  "type": "retry",
  "config": {
    "command": "npm test",
    "max_retries": 3,
    "delay": 5,
    "execution_mode": "shell"
  }
}
```

**Exponential Backoff**: When `exponential_backoff: true`, the wait time doubles with each attempt: 1st attempt = `delay` seconds, 2nd attempt = `delay * 2`, 3rd attempt = `delay * 4`, etc.

**When to use**: Flaky tests, network-dependent operations, API calls that may temporarily fail.

---

### `conditional` — If/Then/Else

Evaluates a condition and executes one of two branches.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `condition` | string | **required** | Condition to evaluate |
| `then_command` | string | **required** | Command if condition is true |
| `else_command` | string | null | Command if condition is false |
| `execution_mode` | string | `"claude"` | `"claude"` (AI) or `"shell"` |
| `timeout_seconds` | int | 300 | Execution timeout |

```json
{
  "type": "conditional",
  "config": {
    "condition": "test -f package.json",
    "then_command": "npm test",
    "else_command": "echo 'No tests'",
    "execution_mode": "shell"
  }
}
```

**Condition evaluation**: In `shell` mode, exit code 0 = true, non-zero = false. In `claude` mode, the AI answers the condition with "TRUE" or "FALSE".

**Dual mode**: `execution_mode` controls both the condition evaluation and the execution of `then_command`/`else_command`. `timeout_seconds` applies to both.

**When to use**: Branching logic based on file existence, previous output, environment checks.

---

### `switch` — Multi-Way Branching

Evaluates an expression and routes execution to the matching case. Like a switch/case statement.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `expression` | string | **required** | Expression to evaluate (supports `${var}` and `{{ var }}`) |
| `cases` | list | **required** | List of case objects with `value` and `blocks` |
| `default` | list | null | Blocks when no match |

Each case has:
- `value`: String to match (pipe-separated alternatives: `"error|critical"`)
- `blocks`: List of block definitions to execute on match

```json
{
  "type": "switch",
  "config": {
    "expression": "${gate_status}",
    "cases": [
      {
        "value": "GREEN",
        "blocks": [{ "type": "shell", "config": { "command": "echo 'All good'" } }]
      },
      {
        "value": "YELLOW|RED",
        "blocks": [{ "type": "shell", "config": { "command": "echo 'Issues found'" } }]
      }
    ],
    "default": [
      { "type": "shell", "config": { "command": "echo 'Unknown status'" } }
    ]
  }
}
```

**When to use**: Multi-way branching when `conditional` (binary if/else) is not sufficient. Routing based on status codes, environment names, or gate results.

---

### `foreach` — Iterate Over Items

Executes a command for each item in a list.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `variable_name` | string | `"item"` | Name of the loop variable |
| `items_source` | string | `"list"` | `"command"` (shell output) or `"list"` (inline) |
| `items_command` | string | — | Shell command, one item per line (only with `items_source: "command"`) |
| `items` | list | — | Inline list of items (only with `items_source: "list"`) |
| `command` | string | **required** | Command per item |
| `execution_mode` | string | `"claude"` | `"claude"` (AI) or `"shell"` |
| `timeout_seconds` | int | 300 | Timeout per iteration |

```json
{
  "type": "foreach",
  "config": {
    "variable_name": "repo",
    "items_source": "command",
    "items_command": "find . -maxdepth 2 -name package.json | xargs -n1 dirname | sort -u",
    "execution_mode": "shell",
    "timeout_seconds": 600,
    "command": "cd '{{ repo }}' && npm outdated --json || true"
  }
}
```

**Note**: `items_command` is always executed as a shell command (regardless of `execution_mode`), since it only collects the iteration targets.

**When to use**: Processing multiple files, repos, or items with the same operation.

---

### `while` — Loop Until Condition

Repeats a command until a condition is met or the maximum iteration count is reached.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `condition_command` | string | null | Condition evaluated each iteration. Without `condition_command`, the loop runs until `max_iterations` or command failure |
| `command` | string | **required** | Command per iteration |
| `max_iterations` | int | 100 | Maximum loop iterations |
| `execution_mode` | string | `"claude"` | `"claude"` (AI) or `"shell"` |
| `timeout_seconds` | int | 300 | Execution timeout per iteration |

```json
{
  "type": "while",
  "config": {
    "condition_command": "Rate the quality on a scale of 1-10. Reply ONLY with 'true' if below 8, or 'false' if 8 or higher.",
    "command": "Improve the text. Output the fully revised version.",
    "max_iterations": 3
  }
}
```

**Condition evaluation**: In `shell` mode, exit code 0 = continue, non-zero = stop. In `claude` mode, "false", "0", or empty response = stop, anything else = continue.

**Dual mode**: `execution_mode` controls both the condition evaluation and the command execution. `timeout_seconds` applies to both.

**When to use**: Iterative refinement, polling until a condition is met.

---

### `approval` — Human Approval

Pauses execution and waits for human approval via WebSocket.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `question` | string | `"Approval required"` | Approval question for the user |
| `options` | list | `["Yes", "Yes always", "No"]` | Available response options |
| `variable_name` | string | `"approval_answer"` | Context variable for the answer |
| `timeout_minutes` | int | 30 | Wait time |
| `mode` | string | `"ask"` | `"ask"` (interactive) or `"auto"` (uses `auto_answer`) |
| `auto_answer` | string | — | Automatic answer when `mode` = `"auto"` |
| `on_no` | string | `"continue"` | Behavior on "No": `"continue"` (proceed, status COMPLETED), `"fail"` (FAILED), or `"skip"` (SKIPPED) |

```json
{
  "type": "approval",
  "config": {
    "question": "## Result\n\n{{ previous_output }}\n\nProceed?",
    "options": ["Yes", "No", "Abort"],
    "variable_name": "pr_decision",
    "timeout_minutes": 60,
    "on_no": "fail"
  }
}
```

**"Yes always"**: When the user selects "Yes always", the answer is remembered for this question for the rest of the run — subsequent approval blocks with the same question are automatically answered.

**Auto mode**: With `"mode": "auto"` and `"auto_answer": "Yes"`, the block is answered immediately without user interaction. Useful for CI/CD pipelines.

**When to use**: Before destructive operations (push, deploy, delete), quality gates.

---

### `human-input` — Collect User Input

Pauses execution to collect free-text input from the user.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `prompt` | string | `"Please provide input:"` | Question for the user |
| `input_type` | string | `"text"` | `"text"` or `"select"` |
| `variable_name` | string | `"user_input"` | Context variable for the input |
| `options` | list | `[]` | Selection options (for `select` type) |
| `default_value` | string | `""` | Default value on timeout |
| `required` | bool | true | Whether input is required |
| `validation_pattern` | string | — | Regex pattern for validation |
| `timeout_minutes` | int | 30 | Wait time |
| `use_default_on_timeout` | bool | **true** | Use default value on timeout instead of failing |

```json
{
  "type": "human-input",
  "config": {
    "prompt": "What language should the documentation be in?",
    "input_type": "select",
    "variable_name": "doc_language",
    "options": ["Deutsch", "English", "Francais"],
    "default_value": "Deutsch",
    "timeout_minutes": 15,
    "use_default_on_timeout": true
  }
}
```

**Timeout behavior**: If `use_default_on_timeout` = true (default) and a `default_value` is set, the default value is used on timeout and the block ends with COMPLETED. If false or no default value is set, the block ends with FAILED.

**When to use**: Querying parameters at runtime, interactive workflows.

---

### `sub-workflow` — Nested Workflow

Executes another workflow as a nested step.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `workflow_id` | string | **required** | ID of the workflow to execute |

**Limits**: Maximum nesting depth of 5 levels. Circular references are detected. 30-minute timeout.

**When to use**: Composing complex workflows from smaller, reusable ones.

---

### `skill-capability` — Invoke a Skill

Invokes the capability of a registered skill.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `capability` | string | **required** | Capability name (e.g. `"web_search"`, `"analyze"`) |
| `input` | string | `""` | Input data (optional, default: empty string) |
| `params` | map | `{}` | Additional parameters |
| `timeout_seconds` | int | null | Claude CLI timeout |

```json
{
  "type": "skill-capability",
  "config": {
    "capability": "analyze",
    "input": "Analyze: {{ previous_output }}",
    "params": { "max_entries": 5 }
  }
}
```

**When to use**: Leveraging specialized skills (OCR, web search, analysis).

---

## 3. Template Variables

### Variable Syntax

Variables use double braces: `{{ variable_name }}` (spaces optional: `{{variable_name}}` also works).

### Built-in Variables

| Variable | Source | Description |
|---|---|---|
| `{{ previous_output }}` | Auto | Output of the previous block (step N-1) |
| `{{ step_N_output }}` | Auto | Output of step N (e.g. `{{ step_0_output }}` for the first block) |
| `{{ previous_chain_output }}` | Auto | Output of the previous chain entry (chains only) |
| `{{ item }}` / `{{ <variable_name> }}` | ForEach | Current loop item |

### System-Injected Variables

The following variables are automatically set by the `WorkflowExecutor` on every run and are available in all blocks:

| Variable | Description |
|---|---|
| `{{ workflow_run_id }}` | Unique run ID |
| `{{ workflow_id }}` | Workflow ID |
| `{{ workflow_name }}` | Workflow name |
| `{{ project_path }}` | Project path |
| `{{ model }}` | Configured model (or empty) |
| `{{ provider }}` | Configured AI provider (or empty) |
| `{{ started_at }}` | Run start time (ISO-8601) |

### Context Variables

Pass custom variables via the execution context:

```json
{
  "context": {
    "filepath": "/home/user/document.md",
    "language": "German",
    "batch_size": "50"
  }
}
```

Access in any block config: `{{ filepath }}`, `{{ language }}`, `{{ batch_size }}`.

### Resolution

Variables are resolved at runtime by `ExecutionContext.resolveTemplate()`. Unresolved variables remain as literal text (e.g. `{{ undefined_var }}` stays unchanged).

### Approval/Human-Input Variables

Answers from `approval` and `human-input` blocks are stored as context variables and are available in subsequent blocks:

```json
// approval block stores answer in {{ pr_decision }}
// next block can use: {{ pr_decision }}
```

---

## 4. Proven Workflow Patterns

These patterns are distilled from the 40+ built-in templates. Each pattern has a clear structure and use case.

### Pattern 1: Sequential Simple Chain

**Structure**: `simple` -> `simple` -> `simple` -> `simple`

All blocks are purely AI-based (no shell). Each block refines the output of the previous one.

**Use case**: Text analysis, content creation, translation, code review.

**Templates**: `file-improve`, `code-refactor`, `translation-pipeline`, `fact-check-pipeline`, `project-overview`

```
Block 1: Read/explore input
Block 2: Analyze/evaluate
Block 3: Improve/transform
Block 4: Summarize/output
```

---

### Pattern 2: Shell Collect + AI Analyze

**Structure**: `shell` -> `simple` (or `skill-capability`) -> `simple`

Shell block collects raw data, AI block analyzes it.

**Use case**: Log analysis, Git history, system metrics, dependency checks.

**Templates**: `git-to-changelog`, `log-triage`, `git-statistics-report`

```
Block 1 (shell): Collect raw data (git log, find, grep, metrics)
Block 2 (simple): Analyze and categorize
Block 3 (simple): Create report with actionable recommendations
```

---

### Pattern 3: Analyze + Agentic Create + Verify

**Structure**: `simple` -> `agentic` -> `simple`

AI analyzes input, agentic block creates complex output (files, code), simple block verifies.

**Use case**: Generating websites, prototypes, documentation packages, dashboards.

**Templates**: `doc-to-website`, `idea-to-prototype`, `cv-to-portfolio`, `csv-to-dashboard`, `codebase-to-onboarding`

```
Block 1 (simple): Analyze input, create plan
Block 2 (agentic): Execute plan — create files, install deps, build
Block 3 (simple): Verify output, quality check
```

---

### Pattern 4: Shell + Conditional Branching

**Structure**: `shell` -> `conditional` -> `shell`/`simple`

Shell block generates data, conditional block branches based on result.

**Use case**: Deploy pipelines, CI/CD, conditional processing.

**Templates**: `deploy-pipeline`, `git-to-changelog` (conditional migration aid)

```
Block 1 (shell): Check preconditions
Block 2 (conditional): Branch based on result
Block 3: Execute corresponding action
```

---

### Pattern 5: Shell Scan + ForEach Process

**Structure**: `shell` -> `foreach` -> `simple`/`skill-capability`

Shell block discovers items, ForEach processes each one, final block aggregates.

**Use case**: Multi-repo operations, batch file processing, bulk analysis.

**Templates**: `dependency-risk-radar`

```
Block 1 (shell): Discover items (find repos, list files, etc.)
Block 2 (foreach): Process each item
Block 3 (simple): Aggregate and summarize results
```

---

### Pattern 6: Audit Pipeline

**Structure**: `shell` -> `simple` -> `simple` -> `simple` (+ optional `conditional` + TODO block)

Shell collects code/config data, multiple simple blocks analyze different aspects, final block creates structured report.

**Use case**: Code audits, security scans, documentation checks, health assessments.

**Templates**: `api-consistency-audit`, `dead-code-detector`, `doc-accuracy-check`, `codebase-health-scorecard`, `architecture-debt-radar`, `test-coverage-gaps`

```
Block 1 (shell): Collect raw data (grep, find, wc, etc.)
Block 2 (simple): Deep analysis with specific criteria
Block 3 (conditional): Optional severity check
Block 4 (simple): Structured report with findings table + action plan
Block 5 (agentic): Optional: Create TODOs from findings
```

---

### Pattern 7: Multi-Stage Pipeline with Approval Gate

**Structure**: `shell` -> `simple` -> `agentic` -> `shell` -> `conditional` -> `approval` -> `shell`

The most complex pattern. Safety-first with verification and human approval.

**Use case**: Automated code fixes with PR creation, deploy with rollback.

**Templates**: `audit-fix-pr-pipeline`

```
Block 1 (shell): Git safety setup (check clean tree, create branch)
Block 2 (simple): Parse findings, create fix plan
Block 3 (agentic): Apply fixes, one commit per fix
Block 4 (shell): Verification (build + test + lint)
Block 5 (conditional): Rollback on failure
Block 6 (approval): Human approval before push
Block 7 (shell): Push + create PR (or abort)
```

---

### Quality Gate Markers

Any block can emit gate status markers in its output to signal quality results. The `WorkflowExecutor` scans **all** block outputs and uses the highest severity.

```
gate_status: GREEN|YELLOW|RED
gate_category: lint-errors
gate_description: 3 issues found
```

| Gate | Run Status | Auto-Todo Priority | Meaning |
|------|-----------|-------------------|---------|
| GREEN | `COMPLETED` | — | All checks passed |
| YELLOW | `COMPLETED_WITH_ERRORS` | MEDIUM | Warnings, non-blocking issues |
| RED | `COMPLETED` | HIGH | Critical issues (handled via AutoTodoService) |

**Shell block example** with gate emission:
```bash
set -euo pipefail
ERRORS=$(eslint src/ --format json | jq '[.[] | .errorCount] | add // 0')
if [ "$ERRORS" -gt 0 ]; then
  echo "gate_status: RED"
  echo "gate_category: lint-errors"
  echo "gate_description: $ERRORS lint errors found"
elif [ "$WARNINGS" -gt 0 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: lint-warnings"
  echo "gate_description: $WARNINGS warnings found"
else
  echo "gate_status: GREEN"
fi
```

When multiple blocks emit gates, the highest severity wins (RED > YELLOW > GREEN).

---

## 5. Timeout Configuration

### Default Values

Source: `ExecutionDefaults.java`

| Setting | Default | Description |
|---|---|---|
| `SHELL_TIMEOUT_SECONDS` | 300 (5 min) | Shell and simple block timeout |
| `AGENTIC_MAX_ITERATIONS` | 10 | Agentic block iteration cap |
| `AGENTIC_ITERATION_TIMEOUT_SECONDS` | 300 (5 min) | Wall-clock timeout per iteration |
| `AGENTIC_TOTAL_TIMEOUT_SECONDS` | 1800 (30 min) | Overall agentic block timeout |
| `AGENTIC_MAX_TOTAL_TOKENS` | 200,000 | Token budget (input+output) |
| `INTERACTION_TIMEOUT_MINUTES` | 30 | Approval/human-input timeout |
| `SUB_WORKFLOW_MAX_WAIT_MINUTES` | 30 | Sub-workflow completion timeout |

### The Dual-Timeout Trap

Agentic blocks have **two independent timeouts** that must be aligned with each other:

1. **`timeout_seconds`**: Claude CLI timeout per call (applies to REASON and ACT phase)
2. **`iteration_timeout_seconds`**: Wall-clock timeout for the entire iteration (REASON + ACT)

**The trap**: If `timeout_seconds=900` but `iteration_timeout_seconds=300`, the CLI call can succeed in 900s, but the iteration will be marked as TIMEOUT at 300s and the block FAILS.

### Auto-Alignment

`AgenticBlockConfig` automatically aligns timeouts when `timeout_seconds` is set:

```
iteration_timeout_seconds = max(current, timeout_seconds * 2 + 60)
```

The formula: `timeout_seconds * 2` (room for REASON + ACT) `+ 60` (overhead).

If `total_timeout_seconds` cannot fit even one iteration, it is also auto-aligned.

### Recommended Values by Model

| Model | `timeout_seconds` | `iteration_timeout_seconds` | `total_timeout_seconds` | `max_total_tokens` |
|---|---|---|---|---|
| **Opus** | 600-900 | 900-1260 | 3600-5400 | 300,000-400,000 |
| **Sonnet** | 300-600 | 300-660 | 1800-3600 | 200,000-300,000 |
| **Haiku** | 120-300 | 300 | 900-1800 | 100,000-200,000 |

### Simple/Shell Block Timeouts

For simple and shell blocks, only `timeout_seconds` applies:

| Task Complexity | Recommended `timeout_seconds` |
|---|---|
| Quick commands (git, find) | 60-120 |
| Build/test (npm, mvn) | 300-600 |
| Complex analysis (Opus) | 600-900 |

---

## 6. Model Selection

The model is set at the workflow level (`workflow.model` field) or left as null for the system default.

### Which Model to Use When

| Model | Use Case | Strengths | Weaknesses |
|---|---|---|---|
| `claude-opus-4-6` | Analysis, audits, security reviews | Precision, fewer hallucinations, deep reasoning | Slower, more expensive |
| `claude-sonnet-4-6` | Code generation, routine tasks | Fast, good enough for most tasks | May hallucinate findings in analyses |
| `claude-haiku-4-5` | Simple classification, extraction | Very fast, inexpensive | Limited reasoning depth |

### Critical Rule

**Always use Opus for analysis workflows.** Sonnet hallucinates findings (e.g. reporting false SQL injection vulnerabilities). For code analysis, security audits, and architecture reviews, precision matters more than speed.

### Setting the Model

Via API:
```json
{
  "model": "claude-opus-4-6"
}
```

In the workflow entity, the `model` field is passed via the `--model` flag to the Claude CLI.

**Important**: A null model field uses the system default (which may be Sonnet). If Opus is needed, set it explicitly.

---

## 7. Execution Environment

### Host Environment (Default)

Shell blocks without `container_image` run on the host. Available tools:

- `curl`, `git`, `python3`, `jq`, `nodejs`, `npm`, `wget`, `unzip`, `bash`

Everything else (pip packages, go, docker-cli, gh, trivy, specialized tools) is **NOT** available.

### Container Images

For shell blocks that need more tools, set `container_image`:

| Image | Base | Included |
|---|---|---|
| `automation-agent-python:latest` | Python 3.12 | pandas, numpy, requests, beautifulsoup4, pip, git, curl, jq |
| `automation-agent-devops:latest` | Ubuntu 24.04 | git, curl, jq, python3, docker-cli, gh (GitHub CLI), trivy, gitleaks |
| `automation-agent-node:latest` | Node.js 21 | npm (full ecosystem), git, curl, jq |
| `automation-agent-go:latest` | Go 1.22 | git, curl, bash, jq |

### Rules

- Shell blocks WITHOUT `container_image` can ONLY use host tools
- If a command needs pip, go, docker, gh, or other specialized tools -> set `container_image`
- Containers mount the project directory to `/workspace`
- Claude CLI always runs on the host (not in containers) to avoid nested session detection

### Example: Shell Block with Container

```json
{
  "type": "shell",
  "config": {
    "command": "gh pr list --state open --json number,title",
    "container_image": "automation-agent-devops:latest"
  }
}
```

### Example: Shell Block with Docker Socket

```json
{
  "type": "shell",
  "config": {
    "command": "docker build -t myapp:latest .",
    "container_image": "automation-agent-devops:latest",
    "mount_docker_socket": true
  }
}
```

---

## 8. Agentic Block Deep Dive

### The Goal-Reason-Act-Observe Loop

Each iteration:
1. **REASON**: Claude analyzes the current state, conversation history, and decides the next ACTION (uses `--system-prompt`, `--max-turns 3`)
2. **ACT**: A **separate** Claude CLI call executes the ACTION (uses `--append-system-prompt`, `--model`, project working directory)
3. **OBSERVE**: The result is recorded and fed back into the next REASON phase

### Critical: ACT Has No Context from REASON

The ACT phase is a **fresh Claude CLI call**. It shares no conversation context with REASON. This means:

**Every ACTION must be self-contained:**
- Use **absolute paths** (not relative, not "the file from earlier")
- Specify **exact names** (not "the function we discussed")
- Give **precise instructions** (not "fix the bug" but "in `/path/to/File.java` line 42, change `foo` to `bar`")

```
WRONG: "Fix the bug in the service class"
RIGHT: "In /home/user/project/src/main/java/com/example/UserService.java
        modify the getUserById() method to handle null inputs
        by adding a null check at the beginning of the method."
```

### History Compression

After 7 turns, older turns are summarized to manage the context window. This means earlier observations may be compressed. Design goals and success criteria so they can be evaluated from the most recent context.

### Stale File Pollution

Old files from previous runs can pollute the agentic block's context (e.g. glob patterns picking up stale reports). Insert a cleanup step before agentic blocks:

```json
{
  "type": "shell",
  "config": {
    "command": "rm -rf output/ && mkdir -p output/"
  }
}
```

### Safety Limits

| Config Key | Default | Purpose |
|---|---|---|
| `max_iterations` | 10 | Hard stop after N iterations |
| `iteration_timeout_seconds` | 300 | Wall-clock timeout per iteration |
| `total_timeout_seconds` | 1800 | Overall timeout (30 min) |
| `max_total_tokens` | 200,000 | Token budget (input+output) |
| `max_repeated_actions` | 3 | Abort after N identical actions |
| `require_approval` | false | Human gate before each action |

### Action Compression

When an ACTION exceeds 100,000 characters, it is compressed via a separate Claude CLI call (60s timeout) before being sent to the ACT phase.

### Token Tracking

Token usage (input + output) is tracked across all iterations. When the budget is exhausted, the block stops. If REASON or ACT returns no usage data, a WARN is logged — the budget is not enforced.

---

## 9. Workflow Instructions & Memory

### Two-Level Instruction System

1. **Base instructions** (`src/main/resources/agent-instructions.md`): Loaded at startup, apply to ALL workflows. Cover working directory, file output, error handling, quality standards.

2. **Workflow-specific instructions** (`workflow.instructions` field): Per workflow, editable in the UI (Monaco editor). Appear as part of the system prompt.

### How Instructions Are Injected

- **Block execution** (all block types): Via `--append-system-prompt` flag on the Claude CLI
- **Agentic REASON phase**: Via `--system-prompt` flag, includes workflow instructions in the `## Workflow Instructions` section

### Workflow Memory

Workflows can accumulate knowledge across runs. Memory is **manually extracted** — it does not happen automatically after a run.

#### How It Works

1. **Extract** (manual): After one or more runs, trigger extraction via the UI ("Memory" tab on the workflow detail page) or API. Select runs and choose a provider (Claude or Codex). The `ConversationPreprocessor` compresses the step outputs by ~50-70% before sending them to the AI, which creates a structured knowledge entry (title, Markdown content, tags).
2. **Activate** (manual): Extracted memory starts as `active=false`. Explicitly activate it before it takes effect. Only one version can be active at a time.
3. **Used on next run**: Before each workflow execution, the active memory is written to `.claude/rules/project-memory.md` in the project directory. The Claude CLI accesses this file automatically via the rules system.
4. **Versioning**: Multiple memory versions per workflow are retained. Older versions can be restored by activating them.
5. **Editing**: Title and content can be manually refined in the UI (Monaco editor).

#### When to Use

Extract memory after a workflow produces a run with valuable insights — patterns, pitfalls, domain knowledge — that should guide future runs. Especially useful for agentic workflows that learn project-specific conventions over time.

#### API

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/workflows/{id}/memory` | List all memory versions |
| POST | `/api/v1/workflows/{id}/memory/extract` | Extract from runs (`runIds`, `provider`) |
| PUT | `/api/v1/workflows/{id}/memory/{mid}` | Update title/content |
| PUT | `/api/v1/workflows/{id}/memory/{mid}/activate` | Activate version |
| DELETE | `/api/v1/workflows/{id}/memory/{mid}` | Delete version |

```json
// POST /api/v1/workflows/{id}/memory/extract
{
  "runIds": ["run-id-1", "run-id-2"],  // optional — omit for last 10 runs
  "provider": "CLAUDE"                  // or "CODEX"
}
```

---

## 10. Chains

Chains group multiple workflows for **sequential execution**.

### Core Properties

- **Cross-project**: A chain can reference workflows from any project
- **Sequential**: Workflows run one after another (not in parallel)
- **Context passing**: Each entry can access `{{ previous_chain_output }}` from the last completed workflow
- **stopOnFailure**: Controls whether the chain stops on workflow failure (configurable per chain and per entry)

### Chain Structure

```json
{
  "name": "Complete Audit + Fix Pipeline",
  "entries": [
    {
      "workflowId": "<audit-workflow-id>",
      "sortOrder": 0,
      "context": { "scope": "backend" },
      "stopOnFailure": true
    },
    {
      "workflowId": "<fix-workflow-id>",
      "sortOrder": 1,
      "context": { "audit_report": "{{ previous_chain_output }}" },
      "stopOnFailure": false
    }
  ]
}
```

### API

```
POST   /api/v1/chains              # Create chain
POST   /api/v1/chains/{id}/execute  # Start execution
DELETE /api/v1/chains/{id}          # Delete chain
```

### Limits

- Polling-based completion check (2s interval)
- 30-minute timeout per workflow in the chain

---

## 11. Dos and Don'ts

### DO

- **Set `timeout_seconds` explicitly** for complex blocks — don't rely on defaults
- **Use Opus for analysis/audit workflows** — Sonnet hallucinates findings
- **Use `set -euo pipefail`** at the beginning of multi-line shell commands
- **Use absolute paths** in agentic block goals and actions
- **Insert cleanup steps** before agentic blocks to avoid stale file pollution
- **Keep agentic block goals specific** — "Create index.html in output/" instead of "Build a website"
- **Use `{{ previous_output }}`** to chain block outputs — data flows forward through the pipeline
- **Set both `timeout_seconds` and `iteration_timeout_seconds`** for agentic blocks — auto-alignment helps, but explicit is better
- **Insert a verification block** after agentic blocks (check files, run tests, validate output)

### DON'T

- **Don't use Sonnet for code analysis** — hallucinates false positives (fake SQL injection, fake security issues)
- **Don't set `iteration_timeout_seconds` lower than `timeout_seconds`** — the CLI will succeed, but the iteration will be marked as TIMEOUT
- **Don't assume ACT has context from REASON** — every action is a fresh CLI call
- **Don't use relative paths in agentic actions** — the ACT phase may not share the same working directory context
- **Don't run Opus workflows in parallel** when `max-concurrent-calls` is low (e.g. 3) — workflows will block each other
- **Don't use `container_image` for simple blocks** — simple blocks always use the host Claude CLI
- **Don't forget to set `model` explicitly** — null uses the system default which may not be desired
- **Don't use shell blocks for AI reasoning** — use simple blocks instead (shell blocks only execute commands)
- **Don't chain more than 5-7 blocks** without good reason — each block adds latency and cost
- **Don't skip the verification step** after agentic blocks — trust but verify

### Anti-Patterns

| Anti-Pattern | Problem | Solution |
|---|---|---|
| Agentic block with vague goal | Agent spins without progress | Formulate goal concretely with file paths and success criteria |
| 100+ words per agentic action | ACT phase runs into timeout or gets confused | Split into smaller, specific actions |
| No cleanup before agentic block | Stale files from previous runs pollute context | Insert `rm -rf output/` shell block before |
| `timeout_seconds: 900` with default `iteration_timeout_seconds` | Iteration times out at 300s even though CLI succeeds | Set both timeouts or use auto-alignment |

---

## Appendix: Template Quick Reference

All 40+ built-in templates with their primary pattern:

| Template | Pattern | Category |
|---|---|---|
| `file-improve` | Sequential Simple Chain | CONTENT |
| `code-refactor` | Sequential Simple Chain | CODE |
| `translation-pipeline` | Sequential Simple Chain | CONTENT |
| `fact-check-pipeline` | Sequential Simple Chain + Conditional | RESEARCH |
| `project-overview` | Sequential Simple Chain | CODE |
| `iterative-refine` | Simple + While Loop | CONTENT |
| `document-review` | Skill-Capability Chain | CONTENT |
| `research-assistant` | Skill-Capability Chain | RESEARCH |
| `data-analysis` | Skill-Capability Chain | DATA |
| `deploy-pipeline` | Conditional + Shell | CODE |
| `code-quality` | Shell + Retry | CODE |
| `git-to-changelog` | Shell Collect + AI Analyze | CODE |
| `log-triage` | Shell Collect + AI Analyze | CODE |
| `git-statistics-report` | Shell Collect + AI Analyze | CODE |
| `doc-to-website` | Analyze + Agentic + Verify | TRANSFORM |
| `idea-to-prototype` | Analyze + Agentic + Verify | TRANSFORM |
| `cv-to-portfolio` | Analyze + Agentic + Verify | TRANSFORM |
| `csv-to-dashboard` | Analyze + Agentic + Verify | DATA |
| `codebase-to-onboarding` | Analyze + Agentic + Verify | CODE |
| `notes-to-presentation` | Analyze + Agentic + Verify | TRANSFORM |
| `api-to-sdk` | Analyze + Agentic + Verify | TRANSFORM |
| `markdown-to-ebook` | Analyze + Agentic + Verify | TRANSFORM |
| `bug-to-fix` | Analyze + Agentic + Verify | CODE |
| `dependency-risk-radar` | Shell Scan + ForEach | CODE |
| `api-consistency-audit` | Audit Pipeline | CODE |
| `dead-code-detector` | Audit Pipeline | CODE |
| `doc-accuracy-check` | Audit Pipeline | CODE |
| `codebase-health-scorecard` | Audit Pipeline | CODE |
| `architecture-debt-radar` | Audit Pipeline | CODE |
| `test-coverage-gaps` | Audit Pipeline | CODE |
| `audit-fix-pr-pipeline` | Multi-Stage + Approval | CODE |
| `hunt-quality-pipeline` | Multi-Stage Pipeline | CODE |
| `folder-backup` | Shell Chain | UTILITY |
| `github-project-bootstrap` | Shell Chain | CODE |
| `dependency-update-check` | Shell + Conditional | CODE |
| `secret-scanner` | Shell + Conditional | CODE |
| `container-vuln-scan` | Shell + Conditional | CODE |
| `database-backup-verify` | Shell Chain | CODE |
| `directory-sync` | Shell Chain | UTILITY |
| `docker-image-build-push` | Shell + Conditional | CODE |
| `todo-creator` | Agentic | UTILITY |

---

## TodoTrigger — Self-Improvement Loop

TodoTriggers close the self-improvement loop: when an audit workflow discovers an issue and creates a TODO, a TodoTrigger can automatically start a fix workflow to resolve it.

### Flow

```
Audit Workflow → AutoTodoService → TODO (OPEN)
                                     ↓ TodoCreatedEvent
                             TodoTriggerService → Fix Workflow
                                                     ↓ WorkflowRunCompletedEvent
                                             TodoFeedbackListener → TODO (DONE)
```

### Setting Up a Trigger

**POST** `/api/v1/todo-triggers`

```json
{
  "tagPattern": "backend,bug",
  "targetWorkflowId": "<fix-workflow-id>",
  "description": "Automatically fix backend bugs",
  "maxAttempts": 3,
  "autoClose": true
}
```

**Tag matching**: Comma-separated with AND semantics. All tags in the pattern must be present in the TODO's tag list. Example: `"backend,bug"` matches a TODO with tags `["backend", "bug", "security"]`, but not `["backend", "feature"]`.

### Optional Filters

| Field | Description | Example |
|-------|-------------|---------|
| `projectId` | Only TODOs from this project | `"ebf4f50c-..."` |
| `sourceFilter` | Only TODOs from this source | `"WORKFLOW"` |
| `minPriority` | Minimum priority (HIGH, MEDIUM, LOW) | `"HIGH"` |

### Context Variables in the Fix Workflow

The fix workflow automatically receives these variables:

| Variable | Content |
|----------|---------|
| `todo_id` | UUID of the TODO |
| `todo_title` | Title |
| `todo_description` | Description (Markdown) |
| `todo_tags` | Comma-separated tags |
| `todo_priority` | HIGH / MEDIUM / LOW |
| `todo_project_id` | Project UUID |
| `todo_source` | USER / AI / WORKFLOW |
| `todo_reference_type` | e.g. `WORKFLOW_RUN` |
| `todo_reference_id` | Run UUID |
| `todo_trigger_id` | Trigger UUID |

### Loop Prevention (3 Layers)

1. **Max attempts**: After `maxAttempts` failed fix attempts, the trigger won't fire again
2. **Source filter**: Triggers can be restricted to `sourceFilter: "WORKFLOW"` — manually created TODOs won't trigger a fix
3. **Fix attempt tracking**: `todo.fixAttempts` counts attempts, `todo.lastFixRunId` stores the last run

### Auto-Close

With `autoClose: true` (default): when the fix workflow completes with a **GREEN** gate status, the TODO is automatically set to DONE. With YELLOW or RED, it stays open.

### Best Practices

- **Keep tag patterns specific** — `"backend,security,bug"` instead of just `"bug"`, otherwise the fix workflow triggers on every bug
- **Design fix workflows to be idempotent** — they may run multiple times
- **Set gate status in fix workflows** — so auto-close works correctly
- **Limit maxAttempts** — default 3 is reasonable; higher values risk infinite loops on systematic failures
