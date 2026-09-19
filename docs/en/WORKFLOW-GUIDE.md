# Workflow Building Guide

Practical guide for creating effective workflows in MySuricates. Covers all block types, proven patterns, timeout configuration, and common pitfalls.

**Target audience**: AI agents (Claude, Codex) and developers who create or debug workflows.

---

## 1. Quick Start

A workflow is a sequence of **blocks** that are executed one after another. Each block has a `type` and a `config` map. The output of each block is available to the next one via `{{ previous_output }}`.

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
    "language": "English"
  }
}
```

Access in block configs with `{{ filepath }}` or `{{ language }}`.

---

## 2. Block Type Reference

MySuricates supports 20 block types. Each block has a `type` and a `config` map.

**Unknown block types are refused at save time** (HTTP 400; the message names the type and the allowed ones). The check runs against the executor registry (`BlockTypeRegistry` — all registered `BlockExecutor` beans) and recurses into nested blocks (`switch` `cases[].blocks` and `default`). A missing or blank `type` counts as unknown. It applies to every write path alike: UI, REST API, import, pool install, seeder and factory. A typo like `"shel"` therefore fails immediately instead of mid-run.

**Restoring an old version** (`POST /api/v1/workflows/{id}/versions/{versionId}/restore`) passes the same check: if the old definition uses a type that no longer exists, the restore fails with 400 — a definition the platform can no longer run does not come back.

### Common Block Properties

In addition to its type-specific config fields, every block also supports these common properties:

| Config Key | Type | Default | Description |
|---|---|---|---|
| `on_failure` | string | `"stop"` | Behavior on failure: `"stop"` (terminate workflow) or `"continue"` (proceed with next block) |
| `provider` | string | null | **AI-capable blocks only** (`simple`, `agentic`, `skill-capability`, `compress-ai` and the inline `command` of `retry`, `foreach`, `while`, `conditional`, `switch`): AI provider for this block only (`CLAUDE`, `PI`, `CODEX`; for `compress-ai` only `CLAUDE`). Precedence block > workflow > system default. On the `integration` block, `provider` instead names the integration provider (`google-calendar`, …). |
| `model` | string | null | **AI-capable blocks only** (including `compress-ai`): model for this block only (CLI alias `opus`/`sonnet`, concrete id or pi `<backend>/<model>`). Precedence block > workflow > provider default. If a block sets a *different* `provider` than the workflow, the workflow model is not inherited (model ids are provider-specific). |
| `effort` | string | null | **AI-capable blocks only**: reasoning effort `low`/`medium`/`high`/`xhigh` for this block only. Precedence block > workflow (`workflow.effort`) > provider default; unset emits no flag at all. Claude maps it to `--effort`, pi to `--thinking`, codex ignores it. The value actually used is visible in the run details as step metadata `aiRequest.effort`. |

Additionally, every block supports an optional top-level field (not inside `config`):

| Field | Type | Default | Description |
|---|---|---|---|
| `skillIds` | string[] | `[]` | Skill IDs that are activated **in addition to** the workflow skills for this block |

**Default behavior**: When a block fails (status `FAILED`), the workflow stops and the run is marked as `FAILED`. With `"on_failure": "continue"`, the workflow proceeds with the next block.

**Per-block skills**: Blocks can bring their own skills via `skillIds`, which apply in addition to the workflow-level skills. The `WorkflowExecutor` merges both lists before execution and restores the baseline state after the block. Example:

```json
{
  "type": "simple",
  "config": { "command": "Analyze the code for security vulnerabilities" },
  "skillIds": ["builtin-code-review", "builtin-security-analyzer"]
}
```

---

### `simple` — AI Text Processing

Sends the prompt to the Claude CLI on the host. No shell execution.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `command` | string | **required** | The prompt sent to Claude |
| `timeout_seconds` | int | null | Claude CLI timeout (if null, the global `automation.host-claude.timeout-seconds` value applies) |

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
| `container_image` | string | null | Docker image (see execution environment; the server may restrict this via an allowlist) |
| `timeout_seconds` | int | 300 | Execution timeout |
| `shell_escape_variables` | bool | false | POSIX-safe escape of **all** template variables (single quotes). Variables from webhook payloads (incl. `git_*`), the block outputs derived from them, and `{{ previous_report }}` / `{{ open_todos }}` are **always** escaped, regardless of this option |
| `mount_docker_socket` | bool | false | Mount Docker socket into the container (only effective when `container_image` is set) |
| `mounts` | list | `[]` | Additional Docker volume mounts in format `host:container[:ro\|rw]` (only effective when `container_image` is set) |
| `checked_command` | bool | false | Derives a gate (`gate_status`/`gate_category`/`gate_description`, see [Quality Gate Markers](#quality-gate-markers) → "`checked_command` / `check_category`") from the exit code and prepends `set -e -o pipefail` |
| `check_category` | string | `build-check` | Category for the gate derived from `checked_command` — only a fallback used when the script itself does not supply a `gate_category` |

> **Placeholders inside heredocs:** a `{{ var }}` inside a quoted heredoc (`<<'PY'`) ends up as
> source code in the interpreter — with `PID = '{{ project_id }}'` any value containing `'` becomes
> Python code. Pass values on the shell line and read them from the environment instead:
> `PROJECT_ID={{ project_id }} python3 - <<'PY'` … `os.environ['PROJECT_ID']`, block option
> `"shell_escape_variables": true`. Shell blocks receive no other environment variables.

> **Untrusted variables:** Everything a webhook caller supplies (payload fields and the derived
> `git_*` fields) is single-quoted in shell commands (`shell`, `foreach`, `while`, `retry`, `conditional` and
> blocks nested in `switch`, each in shell mode) — even without `shell_escape_variables`. Write such placeholders **unquoted**
> (`git checkout {{ git_branch }}`, not `"{{ git_branch }}"`), otherwise the quotes double up.
> **And that is not merely cosmetic: inside your own quotes the escaping is void.** With the value
> `x; id`, `PREV='{{ previous_output }}'` becomes `PREV=''x; id''` — the shell runs that as
> `PREV=x` **followed by** `id`. So never put an untrusted or tainted placeholder in quotes; always
> write it bare (`PREV={{ previous_output }}`) **and set `"shell_escape_variables": true` on the
> block** — without taint the value is substituted raw otherwise, and a multi-line output breaks the
> command. Bare plus the escape option is the only form that is right in both cases; the test
> `TemplateHygieneGateTest` enforces it for built-in templates and the pool. Values from
> `defaultContext` and system variables stay raw without the option.
>
> **Unset workflow variables:** if a variable is unset, its placeholder stays literally in the command;
> bare `SOURCE_DIR={{ source_dir }}` would then be a shell error. Idiom:
> `set -- {{ source_dir }}; SOURCE_DIR="$*"` — set, it yields the escaped value; unset, the literal
> placeholder, which you detect as before with `[ "$SOURCE_DIR" = "{{ source_dir }}" ]`.

> **Taint propagation onto block outputs:** in a webhook-triggered run — and in every sub-workflow
> it starts as well as after a resume — `{{ previous_output }}`, `{{ block.<name>.output }}`,
> `{{ step_N_output }}` and the items of an `items_command` are single-quoted too. The reason: a
> block can carry the payload into its output without naming the placeholder at all (reading a
> file, calling an API). The taint appears as soon as the run holds any untrusted input and stays
> for the rest of the run. So write these placeholders **unquoted** as well. In runs without
> untrusted input (schedule, manual start, TodoTrigger, chain) every block output is substituted
> raw exactly as before.

> **`{{ previous_report }}` / `{{ open_todos }}`:** both are **always** treated as untrusted and are
> therefore **always single-quoted** in shell blocks — in every run, regardless of the trigger and of
> `shell_escape_variables`. The reason: `previous_report` is the output of an earlier run (which may
> have processed a webhook payload), and `open_todos` contains titles from imported issues. So write
> these placeholders **unquoted** (`REPORT={{ previous_report }}`, not `REPORT="{{ previous_report }}"`).
> In AI prompts the value is still substituted raw (prompts are never escaped).
>
> **Both placeholders do trigger taint propagation — on two paths:**
>
> 1. **Substitution into a shell field colours the whole run.** As soon as `{{ previous_report }}` or
>    `{{ open_todos }}` really lands in `command`, `items_command`, `condition`, … of a shell block,
>    **from that point on** every block output of the run (`{{ previous_output }}`,
>    `{{ block.<name>.output }}`, `{{ step_N_output }}`, `foreach` items) is single-quoted too —
>    exactly as in a webhook-triggered run.
> 2. **An AI prompt colours that block's output — and from it, the run.** If platform data was in the
>    prompt of an AI block (`simple`, `agentic`, `skill-capability` plus the non-shell branches of
>    `foreach`/`while`/`conditional`/`retry`/`switch`), its output counts as untrusted: a following
>    shell block receives it single-quoted. Once that output is recorded the run is coloured, sticky
>    just as in case 1 — every **further** block output and the variables derived from it
>    (`{{ step_N_output }}`, `foreach` items, `document` extracts) are quoted from then on too, and
>    the taint is passed to sub-workflows and to a resume via `exportTaint()`. The prompt alone
>    colours nothing: if no output is recorded, the run stays clean.
>
> A run that does not use the two placeholders at all is unchanged: block outputs are substituted raw
> as before.
>
> **The safe form for follow-up blocks:** set `"shell_escape_variables": true` and pull the
> **unquoted** placeholder into a variable via `set --` — `set -- {{ previous_output }}`, then
> `ANSWER="${1:-}"`. This behaves identically in coloured and in clean runs and is the form the
> shipped definitions use (the `PlatformPromptQuotingGateTest` gate enforces it there).
>
> Full trust model (three trust classes, reserved context keys, known limits):
> [architektur.md → trust classes for context values](architektur.md#workflow-hardening-optional).

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
| `goal` | string | **required** | What the agent should achieve |
| `success_criteria` | string | `""` | How success is measured |
| `max_iterations` | int | 10 | Hard iteration upper limit |
| `iteration_timeout_seconds` | int | 300 | Wall-clock timeout per iteration |
| `total_timeout_seconds` | int | 1800 | Total block timeout (30 min) |
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
| `exponential_backoff` | bool | false | Double pause on each attempt |
| `execution_mode` | string | `"ai"` | `"ai"` (AI, provider from `provider` on the block/workflow) or `"shell"`; `"claude"` still means `"ai"`, other values are rejected |
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

**Exponential Backoff**: When `exponential_backoff: true`, the wait time doubles on each attempt: 1st attempt = `delay` seconds, 2nd attempt = `delay * 2`, 3rd attempt = `delay * 4`, etc.

**When to use**: Flaky tests, network-dependent operations, API calls that may fail temporarily.

---

### `conditional` — If/Then/Else

Evaluates a condition and executes one of two branches.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `condition` | string | **required** | Condition to evaluate |
| `then_command` | string | null | Command if condition is true (if null, nothing is executed) |
| `else_command` | string | null | Command if condition is false |
| `execution_mode` | string | `"ai"` | `"ai"` (AI, provider from `provider` on the block/workflow) or `"shell"`; `"claude"` still means `"ai"`, other values are rejected |
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

**Dual mode**: `execution_mode` controls both condition evaluation and execution of `then_command`/`else_command`. `timeout_seconds` applies to both.

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
- `value`: String to match against (pipe-separated alternatives: `"error|critical"`)
- `blocks`: List of block definitions to execute on match

Nested blocks in `cases` and `default` are limited to `shell` and `simple` — other types (including `compress`/`compress-ai`) are rejected with an error message.

A `name` on a nested block makes its output available as `{{ block.<name>.output }}` in later steps; without a name only `{{ previous_output }}` carries it. The switch block's own output stays the concatenation of all executed case blocks.

The blocks of a case run in sequence and each one moves `{{ previous_output }}` along — like the iterations of a `while` loop. The second block of a case therefore sees the first one's output, no longer the step before the switch (unnamed blocks included). If you need the state from before the switch, name that block and reference `{{ block.<name>.output }}`.

Block names should be unique across the workflow: on a collision the last write wins. A case block overwrites an earlier block of the same name, and after the switch its aggregated output in turn overwrites an equally named child.

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
| `items_command` | string | — | Shell command, one item per line (only for `items_source: "command"`) |
| `items` | list | — | Inline list of items (only for `items_source: "list"`) |
| `command` | string | **required** | Command per item |
| `execution_mode` | string | `"ai"` | `"ai"` (AI, provider from `provider` on the block/workflow) or `"shell"`; `"claude"` still means `"ai"`, other values are rejected |
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
| `execution_mode` | string | `"ai"` | `"ai"` (AI, provider from `provider` on the block/workflow) or `"shell"`; `"claude"` still means `"ai"`, other values are rejected |
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

**Dual mode**: `execution_mode` controls both condition evaluation and command execution. `timeout_seconds` applies to both.

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

**"Yes always"**: When the user selects "Yes always", the answer is remembered for this question for the rest of the run — subsequent approval blocks with the same question are answered automatically.

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
| `fields` | list | `[]` | Multi-field form: each entry has `variable_name`, `label`, `input_type`, `required`, `default_value`, `options`, `placeholder`, `description`, `validation_pattern` |

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

**Timeout behavior**: When `use_default_on_timeout` = true (default) and a `default_value` is set, the default value is used on timeout and the block ends with COMPLETED. When false or no default value is set, the block ends with FAILED.

**When to use**: Collecting parameters at runtime, interactive workflows.

---

### `sub-workflow` — Nested Workflow

Executes another workflow as a nested step.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `workflow_id` | string | **required** | ID of the workflow to execute |

**Limits**: Maximum nesting depth of 5 levels. Circular references are detected. 30-minute timeout.

**Markers**: The step merely passes the child run's last output through (step metadata `derivedOutput: true`). Any `gate_status:` and `todo_item:` markers inside it are evaluated in the child run only — the parent run deliberately leaves them out of its gate summary and TODO list.

**When to use**: Composing complex workflows from smaller, reusable ones.

---

### `skill-capability` — Invoke a Skill

Invokes a capability of a registered skill.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `capability` | string | **required** | Capability name (e.g. `"web_search"`, `"analyze"`) |
| `input` | string | `""` | Input data (optional, default: empty string) |
| `params` | map | `{}` | Additional parameters |
| `timeout_seconds` | int | null | Claude CLI timeout |
| `allow_writes` | bool | `false` | Allows the capability to write to the repo. Required for `writes:true` capabilities (test-generator, feature-implementer). Without `allow_writes`, the block runs read-only (tool whitelist Read/Grep/Glob/WebFetch/WebSearch/Task/TodoWrite). |

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

**Security**: Capabilities declare via `Capability.writes` whether they write files. Built-in `writes:true`: `builtin-test-generator/*`, `builtin-feature-implementer/*`. A block that invokes a `writes:true` capability without `"allow_writes": true` fails immediately with a clear message. For all other cases, the host CLI is started with a read-only tool whitelist (defense in depth).

---

### `document` — Extract Text from Documents

Reads a file (PDF, DOCX, XLSX, PPTX, HTML, TXT, EPUB, RTF, ...) and extracts plain text via Apache Tika. Pure Java, no native dependencies. Output is the extracted text as `previous_output` (and optionally as a named variable). Cost: 0 tokens.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `file_path` | string | **required** | Path to the file. Absolute or relative to the project path. Supports templates (`{{ variable }}`). |
| `variable_name` | string | null | If set, the extracted text is additionally written to this context variable. |
| `fail_on_empty` | bool | `false` | If true, the block FAILS when the extracted text is empty/below threshold. |
| `min_characters` | int | `1` | Threshold for `fail_on_empty`: fewer characters count as empty. |

```json
{
  "type": "document",
  "config": {
    "file_path": "{{ invoice_path }}",
    "variable_name": "invoice_text",
    "fail_on_empty": true,
    "min_characters": 50
  }
}
```

**Supported formats (selection)**: PDF (with text layer), DOCX/XLSX/PPTX, HTML/XML, TXT, EPUB, RTF, ODT.

**Limits**:
- Default max 50 MB per file (configurable via `automation.document.max-bytes`)
- Default max 5 million characters output (configurable via `automation.document.max-characters`)
- Path traversal (`../`) out of the project path is blocked
- Scanned PDFs without a text layer produce empty output — OCR is not currently part of this block (planned for a future release)

**When to use**: Extracting invoice/contract text, summarizing PDF reports (combined with a `simple` block), document classification, preprocessing for LLM analysis without vision token costs.

**Example combination with `simple`**:
```json
[
  { "type": "document", "config": { "file_path": "{{ pdf }}", "variable_name": "text" } },
  { "type": "simple", "config": { "command": "Summarize:\n\n{{ text }}" } }
]
```

---

### `todo-create` / `todo-update` / `todo-close` — Native TODO Operations

Create or change TODOs directly through the TodoService — **no REST self-call, no DB access, 0 tokens**. They replace the anti-pattern `curl POST /api/v1/todos` from shell/agentic blocks.

**`todo-create`**:

| Config Key | Type | Default | Description |
|---|---|---|---|
| `title` | string | **required** | Title (template-capable) |
| `description` | string | null | Markdown description (template-capable) |
| `priority` | string | `MEDIUM` | HIGH / MEDIUM / LOW / CRITICAL |
| `tags` | list or string | null | List or comma-separated |
| `project_id` | string | context variable `project_id` | Target project |
| `source` | string | `WORKFLOW` | USER / AI / WORKFLOW |
| `source_agent` | string | `workflow` | Source agent label |
| `variable_name` | string | `todo_id` | Context variable that receives the new TODO id |

**`todo-update`**: `id` (required, template-capable — e.g. `{{ todo_id }}`), optionally `title`/`description`/`priority`/`tags` — only the fields that are set are changed.

**`todo-close`**: `id` (required, template-capable) — sets the status to DONE.

```json
[
  { "type": "todo-create", "config": { "title": "Fix {{ component }}", "priority": "HIGH", "tags": ["bug"] } },
  { "type": "simple", "config": { "command": "Fix the problem ..." } },
  { "type": "todo-close", "config": { "id": "{{ todo_id }}" } }
]
```

**When to use**: Deterministic single-TODO operations (create a tracking TODO and close it at the end). For **dynamically many** TODOs out of general analysis workflows use `todo_item:` markers instead (see §Auto-TODO/detector format). Structured v4 audit templates use the versioned `mysuricates:audit-result:v1` envelope instead; the Audit Center manages findings and TODO links from it.

---

### `compress` / `compress-ai` — Visibly Condense Outputs

Condense the output of **exactly one** earlier block before it reaches subsequent steps. The original stays untouched under `{{ block.<source>.output }}`; the compression block has its own output `{{ block.<name>.output }}`. Compression is **final** — anyone who needs to see everything references the original.

**Output format** (both types):

```
## backend_tests — FAILED · exit 1 · 312 s · 740.808 → 17.950 Zeichen
Stufen: text.normalize (−4.102 Zeichen) · lines.filter (−8.914 Zeilen) · text.truncate (−96.004 Zeichen)

<condensed content>
```

Status and exit code come from the stored step data, never from the log text. Gate and TODO markers inside compression outputs are **not** evaluated at run completion — the original step remains authoritative.

Any name that also resolves as `{{ block.<name>.output }}` works as a `source` — top-level blocks and named `switch` case blocks (2718c20e). The header then shows the status, exit code and duration of the case step, not those of the whole `switch`. Nested blocks without a name (`foreach`/`while` iterations) only move `previous_output`; there, `"previous"` is the right source.

**`compress`** (rule-based, 0 tokens):

| Config Key | Type | Default | Description |
|---|---|---|---|
| `source` | string | **required** | Block name of an earlier block (a named `switch` case block counts too), or `"previous"` |
| `stages` | list | **required** | Stages `{ "op": …, … }`, run in order |
| `max_chars` | int | 20000 | hard ceiling including header lines; above it, head/tail is trimmed |
| `timeout_seconds` | int | 30 | time budget (guards against expensive regex) |

| Stage | Options | Effect |
|---|---|---|
| `text.normalize` | — | ANSI, CR, trailing whitespace, runs of blank lines |
| `text.truncate` | `max_chars`, `keep` (`head`/`tail`/`head_tail`) | trims at line boundaries, the notice counts toward the limit |
| `lines.filter` | `include`, `exclude` (regex lists), `before`, `after` | keep matches with context / drop lines |
| `lines.replace` | `pattern`, `replacement` | regex replacement per line (e.g. strip timestamps) |
| `lines.dedupe` | `count`, `consecutive_only` | remove repeats, optionally `37× …` |
| `lines.head` / `lines.tail` | `lines` | first / last n lines |
| `markdown.section` | `headings` (regex, case-insensitive), `level` | only matching sections |
| `json.select` | `paths` (`items[].title`, `meta.count`) | only these fields, valid JSON |
| `json.limit` | `path`, `count` | trim an array |
| `json.sort` | `path`, `by`, `order` | sort an array, missing values last |
| `json.to_lines` | `path`, `template` (`{title} — {url}`) | one entry per line — simple curly-brace placeholders |

JSON stages need JSON as input and must come before text stages.

**`compress-ai`** (one tool-less AI call): `source` (required), `instruction` (plain text, placeholders are **not** resolved), `max_chars` (default 4000, minimum 500), `model`, `effort`, `timeout_seconds`. Runs in a fresh, private working directory per call (no project `CLAUDE.md`, no MCP servers, no plugin hooks — `--tools ""`, `--strict-mcp-config`, `--setting-sources local`). Because of `--setting-sources local`, user-level Claude settings (e.g. `env` or `apiKeyHelper` in `~/.claude/settings.json`) are **not** applied to this call. Only provider `CLAUDE`; a different `provider` fails — if the workflow runs on another provider, set `"provider": "CLAUDE"` on the block. Inputs above the `compress-ai-max-input-chars` setting (default 60,000) fail without making the call — put a `compress` block before it instead.

> **Limit — `--bare` would isolate more, but is unusable (TODO edb38a38):** the Claude CLI has a
> minimal mode `--bare` that additionally skips hooks, LSP, plugin sync, auto-memory and `CLAUDE.md`
> discovery. It is still not used here, because per `claude --help` `--bare` also skips **keychain
> reads**: "Anthropic auth is strictly `ANTHROPIC_API_KEY` or `apiKeyHelper` via `--settings` (OAuth
> and keychain are never read)". On a host signed in via OAuth, as is usual, the call therefore
> aborts — verified on the dev host: `claude -p --bare "Antworte mit OK"` → `Not logged in · Please
> run /login` (exit code 1), while `claude -p --setting-sources local "Antworte mit OK"` → `OK`.
> `--bare` would thus only be possible for instances that run purely on `ANTHROPIC_API_KEY`.

```json
[
  { "type": "shell", "name": "backend_tests", "config": { "command": "mvn -q test", "on_failure": "continue", "checked_command": true } },
  { "type": "compress", "name": "backend_kompakt", "config": { "source": "backend_tests", "stages": [
      { "op": "text.normalize" },
      { "op": "lines.filter", "exclude": ["^\\s+at "] },
      { "op": "lines.dedupe", "count": true },
      { "op": "text.truncate", "max_chars": 18000, "keep": "head_tail" } ] } },
  { "type": "simple", "name": "report", "config": { "command": "Bewerte:\n{{ block.backend_kompakt.output }}" } }
]
```

**Not allowed:** nested inside a `switch` block (rejected).

---

### `dashboard-report` — report the dashboard result without a marker

Fills the **dashboard result** of this run directly from block config — the same fields, the same bounds and the same validation as the marker `dashboard_widget:` (see api-referenz.md), but **without a marker line and without text search**. Deterministic: no AI call, no shell, 0 tokens.

Module: `dashboard-report` (standard on, can be switched off). When off, the block is refused and the type is not offered in the editor.

| Config Key | Type | Default | Description |
|---|---|---|---|
| `status` | string | **required** | `GREEN`, `YELLOW` or `RED` (templatable) |
| `title` | string | – | Title, ≤ 80 characters |
| `summary` | string | – | Summary, ≤ 500 characters |
| `kpis` | list of objects | – | ≤ 6 × `{ label (≤ 40), value (≤ 40, string), trend? (UP/DOWN/FLAT) }` |
| `items` | list of strings | – | ≤ 10 entries, each ≤ 200 characters |

```json
{ "type": "dashboard-report", "name": "report", "config": {
  "status": "{{ gate_status }}",
  "title": "Nightly QA",
  "kpis": [{ "label": "Hunts", "value": "{{ hunt_count }}", "trend": "UP" }],
  "items": ["{{ finding_1 }}"],
  "summary": "{{ block.analyse.output }}"
} }
```

**Rules:**

- All values are templatable. A placeholder that evaluates to empty at runtime causes that field to be omitted (for `status` it is an error). A KPI object is omitted only if **all** its values are empty; a half-filled one causes the block to fail.
- A placeholder that **could not be resolved** (unknown variable — the resolver leaves it literally) **causes the block to fail** and names the field. Better a red step than `{{ hunt_count }}` as a KPI on the dashboard.
- Any breach of the contract (too long, unknown field in a KPI, number instead of string, invisible characters) causes the step to be **FAILED**; the message names the field (`kpis[2].value`), never the value.
- Unknown keys at the top level are ignored (legitimate platform keys like `timeout_seconds` live there); unknown keys **inside** a KPI object are rejected.
- Step output contains only counts, never contract values.

**Precedence over the marker:** Once a `dashboard-report` block has written a result, the marker `dashboard_widget:` is not evaluated in **any** step output. The block is the explicitly configured way; without this rule, a marker line in a later AI step's output could overwrite the report. Two `dashboard-report` blocks in one workflow: the one executed last wins.

**In the editor** the block shows a form instead of raw JSON editing, with an optional button "Suggestion from last run" (one AI call; Settings → Execution, requires provider `CLAUDE`). The suggestion is shown and only written to config after "Apply".

**When to use**: whenever a workflow needs to report a dashboard result. Use the marker only when values first arise in AI text and no variable exists for them.

---

## 3. Template Variables

### Variable Syntax

Variables use double braces: `{{ variable_name }}` (spaces optional: `{{variable_name}}` works too).

### Built-in Variables

| Variable | Source | Description |
|---|---|---|
| `{{ previous_output }}` | Auto | Output of the previous block (step N-1) |
| `{{ step_N_output }}` | Auto | Output of step N (e.g. `{{ step_0_output }}` for the first block) |
| `{{ block.<name>.output }}` | Auto | Output of a named block (block must have a `name` field) |
| `{{ previous_chain_output }}` | Auto | Output of the previous chain entry (chains only) |
| `{{ item }}` / `{{ <variable_name> }}` | ForEach | Current loop item |

### System-Injected Variables

The following variables are automatically set by the `WorkflowExecutor` on every run and are available in all blocks:

| Variable | Description |
|---|---|
| `{{ workflow_run_id }}` | Unique run ID |
| `{{ workflow_id }}` | Workflow ID |
| `{{ workflow_name }}` | Workflow name |
| `{{ project_id }}` | Project ID (or empty) |
| `{{ project_path }}` | Project path (or empty) |
| `{{ model }}` | Configured model (or empty) |
| `{{ provider }}` | Configured AI provider (or empty) |
| `{{ started_at }}` | Run start time (ISO-8601, e.g. `2026-04-22T21:30:45.123Z`) |
| `{{ run_timestamp }}` | Path-safe timestamp (UTC, format `yyyyMMdd-HHmmss`, e.g. `20260422-213045`) — ideal for per-run output directories |
| `{{ home_dir }}` | Home directory of the JVM user |
| `{{ user }}` | Operating system username |
| `{{ workflow_version }}` | Current version number of the workflow |
| `{{ workflow_changed_since_last_run }}` | `"true"` or `"false"` — whether the definition has changed since the last successful run (hash comparison; timestamps when no hash exists) |
| `{{ workflow_definition_hash }}` | SHA-256 of the executed definition (empty when `automation.hardening.provenance` is off) |
| `{{ trigger_source }}` | What started the run: `MANUAL`, `RETRY`, `RESUME`, `SCHEDULE`, `WEBHOOK`, `TODO_TRIGGER`, `TODO_RESOLUTION`, `COMPLETION_TRIGGER`, `CHAIN`, `SUB_WORKFLOW`, `ASSISTANT`, `AUTO_REPAIR`, `WISH` |
| `{{ previous_report }}` | Longest non-shell step output from the last COMPLETED run of the same workflow (max 3000 characters), or `"(no previous report available)"`. Always treated as untrusted → always single-quoted in shell blocks, so write it unquoted |
| `{{ open_todos }}` | Markdown list of open/in-progress TODOs for the project inside an `<open_todos>…</open_todos>` block, or `"(keine offenen TODOs)"`. Titles and tags are flattened to one line, capped at 200/80 chars and stripped of platform markers (`todo_item:`, `gate_*:`) — they may originate from imported issues and are data, not instructions. At most 150 entries. Always treated as untrusted → always single-quoted in shell blocks, so write it unquoted |

### Context Variables

Pass custom variables via the execution context:

```json
{
  "context": {
    "filepath": "/home/user/document.md",
    "language": "English",
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

These patterns are distilled from the 65+ built-in templates. Each pattern has a clear structure and use case.

### Block Choice: Frugality Ladder

Workflow construction follows the same YAGNI logic as code — the first rung that fits wins:

1. **Can it be deterministic?** → `shell` block (0 tokens, fast, reproducible)
2. **Does a single AI call suffice?** → `simple` (1 call) instead of `agentic` (N calls: REASON + ACT per iteration)
3. **`agentic` only** when the necessary steps are unknown up front and the result is verifiable
4. **Existing block type before new platform feature** — check the reference in section 2 first
5. **Few blocks before many** — every block costs tokens, runtime, and is its own timeout risk

No blocks "just in case" (summaries, reports, formatting that nobody reads).
The token budget is tight: every avoided AI call stretches the runtime of the allowance.

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

**Use case**: Log analysis, git history, system metrics, dependency checks.

**Templates**: `git-to-changelog`, `log-triage`, `git-statistics-report`

```
Block 1 (shell): Collect raw data (git log, find, grep, metrics)
Block 2 (simple): Analyze and categorize
Block 3 (simple): Create report with actionable recommendations
```

**Large outputs:** put a `compress` block between shell and AI; for very large logs, `compress` → `compress-ai`.

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

**Templates**: `deploy-pipeline`, `git-to-changelog` (conditional migration help)

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
Block 1 (shell): Discover items (find repos, list files etc.)
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
Block 1 (shell): Collect raw data (grep, find, wc etc.)
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

Every block can emit gate status markers in its output to signal quality results. The `WorkflowExecutor` scans **all** block outputs and uses the highest severity.

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

**`run.metadata.gate_summary`** additionally aggregates all parsed gates of the run:

```json
{
  "gate_summary": {
    "highest": "YELLOW",
    "counts_by_status": {"GREEN": 3, "YELLOW": 2},
    "items": [
      {"step_index": 0, "status": "GREEN", "category": "public-inventory"},
      {"step_index": 2, "status": "YELLOW", "category": "lint"}
    ]
  }
}
```

This allows diffing two runs without re-parsing (e.g. for public readiness trends):

```bash
curl -s -H "X-API-Key: $KEY" "$BASE/executions/$RUN1" | jq '.metadata.gate_summary' > a.json
curl -s -H "X-API-Key: $KEY" "$BASE/executions/$RUN2" | jq '.metadata.gate_summary' > b.json
diff <(jq -S . a.json) <(jq -S . b.json)
```

### `checked_command` / `check_category` — Gate Derived Automatically from the Exit Code

Instead of `echo`ing `gate_status` lines itself, a `shell` block can leave that to the
`CheckedCommandGate`: `"checked_command": true` prepends `set -e -o pipefail` to the command and
derives a gate from the exit code — **exit 0 → `GREEN`, exit ≠0 → `RED`**, default category
`build-check` (overridable via `check_category`), description `Command exit <code>`.

```json
{
  "type": "shell",
  "config": {
    "command": "mvn -q test",
    "checked_command": true,
    "check_category": "backend-tests"
  }
}
```

**Precedence rule when the script itself already emits a gate marker** (`CheckedCommandGate`, since
b19f5225): the script's own marker wins as soon as it is **at least as severe** as the one derived
from the exit code — at equal severity, the script wins too. Only when the exit code is stricter
than what the script itself reports (or the script reports no marker at all) does the exit code
prevail. Category and description from `check_category` or the default are always only a fallback:
they are added only when the winning script gate does not supply those fields itself (a "bare"
`gate_status:` line without a category would otherwise stay categoryless).

Example: a script that reports a milder `YELLOW` itself but aborts with a failing exit code — the
exit code (`RED`) is stricter and wins:

```bash
#!/usr/bin/env bash
set -euo pipefail
echo "gate_status: YELLOW"
echo "gate_category: lint-warnings"
exit 2   # stricter than YELLOW → the block gate reports RED, not YELLOW
```

Conversely, if the script reports a gate at least as severe as what the exit code implies (e.g. it
reports `gate_status: RED` itself on exit 1), its `gate_category`/`gate_description` are kept
instead of being replaced by `check_category`/the default.

Covered by `ShellBlockExecutorTest` (among others `execute_checkedCommandExitZero_doesNotShadowTheScriptsOwnWorseGate`,
`execute_checkedCommandFailing_stillEscalatesAMilderScriptGate`,
`execute_checkedCommandFailingWithItsOwnRed_keepsTheScriptsCategoryAndDescription`,
`execute_checkedCommandWithABareScriptGate_fillsInCategoryAndDescriptionFromTheBlock`).

---

## 5. Timeout Configuration

### Default Values

Source: `ExecutionDefaults.java`

| Setting | Default | Description |
|---|---|---|
| `SHELL_TIMEOUT_SECONDS` | 300 (5 min) | Shell and simple block timeout |
| `AGENTIC_MAX_ITERATIONS` | 10 | Agentic block iteration upper limit |
| `AGENTIC_ITERATION_TIMEOUT_SECONDS` | 300 (5 min) | Wall-clock timeout per iteration |
| `AGENTIC_TOTAL_TIMEOUT_SECONDS` | 1800 (30 min) | Total agentic block timeout |
| `AGENTIC_MAX_TOTAL_TOKENS` | 200,000 | Token budget (input+output) |
| `INTERACTION_TIMEOUT_MINUTES` | 30 | Approval/human-input timeout |
| `SUB_WORKFLOW_MAX_WAIT_MINUTES` | 30 | Sub-workflow completion timeout |

### The Dual-Timeout Trap

Agentic blocks have **two independent timeouts** that must be aligned with each other:

1. **`timeout_seconds`**: Claude CLI timeout per call (applies to REASON and ACT phases)
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

**Bytecode measurements cost compile time**: shell blocks that measure the bytecode (`scripts/public-readiness/arch-metrics.sh` with `jdeps`) need a `target/classes` — the workspace clone of an isolated run never has one, so the first run compiles (~1-2 minutes, hence `timeout_seconds: 600`). From the second run of the same commit on, the compile cache under `$AUTOMATION_ARCH_CACHE` (default `~/.cache/mysuricates/arch-classes`) takes over and the block finishes in seconds; `compile=cached` in the `## run` section marks the hit. With uncommitted changes it deliberately compiles again.

---

## 6. Model Selection

The model is set at the workflow level (`workflow.model` field) or left as null for the system default.

### Which Model to Use When

| Model | Use Case | Strengths | Weaknesses |
|---|---|---|---|
| `opus` | Analysis, audits, security reviews | Precision, fewer hallucinations, deep reasoning | Slower, more expensive |
| `sonnet` | Code generation, routine tasks | Fast, good enough for most tasks | Can hallucinate findings in analyses |
| `haiku` | Simple classification, extraction | Very fast, inexpensive | Limited reasoning depth |
| `fable` | Heaviest reasoning/agentic tasks | Strongest model | Most expensive tier |

The values are **CLI aliases** of the Claude CLI and always point to the newest model of their
family — they never go stale. A concrete id (e.g. `claude-opus-5`) can be typed freely into the
model field when a workflow should be pinned to a version. The suggestion list in the UI comes from
`GET /api/v1/system/ai-providers/{id}/models` (pi: `pi --list-models`, codex: local model cache).

### Critical Rule

**Always use Opus for analysis workflows.** Sonnet hallucinates findings (e.g. reporting false SQL injection vulnerabilities). For code analysis, security audits, and architecture reviews, precision matters more than speed.

### Provider Rule: Codex Is Read-Only

`"provider": "CODEX"` always runs `codex exec` with `--sandbox read-only` (`HostCodexService`). Blocks that are
supposed to write files (simple/agentic/foreach with file output) still report COMPLETED — but the files are
missing (silent failure). Therefore:

- **CODEX only for analysis/extraction without file writes** (reports via `previous_output`, classification).
- **Writing Codex workflows go through Pi:** `"provider": "PI"`, `"model": "openai-codex/gpt-5.5"` — Pi's
  ACT path has read/write/edit/bash tools and can write (example: `pi-smoke-test`).

### Setting the Model

Via API:
```json
{
  "model": "opus"
}
```

In the workflow entity, the `model` field is passed via the `--model` flag to the Claude CLI.

**Important**: A null model field uses the system default, which may be Sonnet. When Opus is needed, set it explicitly.

### Reasoning Effort

In addition to the model, a uniform effort level can be set per workflow (`workflow.effort` field, UI: sidebar → "Reasoning Effort"). Valid values: `low`, `medium`, `high`, `xhigh`. Unset (null) = provider default, **no** flag is emitted.

| Provider | CLI Mapping | Notes |
|---|---|---|
| Claude | `--effort <level>` | REASON and ACT calls |
| Pi | `--thinking <level>` | Explicit effort wins over the maxTurns heuristic; without effort the ACT path behaves as before (no flag) |
| Codex | — | Ignored (`supportsEffort=false`), the UI hides the slider |

**Per-block override**: block config key `"effort"` overrides the workflow level (precedence: block > workflow > provider default). Invalid block values are ignored with a warning.

```json
{
  "type": "simple",
  "config": {
    "command": "Analyze the architecture ...",
    "effort": "xhigh"
  }
}
```

Rule of thumb: `high`/`xhigh` for analysis/audit blocks, `low` for mechanical transformations. The effort actually used is visible in the step metadata (`aiRequest.effort`) of the run details.

### Provider and Model per Block

Following the same pattern as `effort`, AI-capable blocks (`simple`, `agentic`, `skill-capability`, and the inline `command` of `retry`, `foreach`, `while`, `conditional`, `switch`) can set the provider and model for themselves only — config keys `"provider"` and `"model"`. Resolution: **Block > Workflow > System default** (`AiProviderRegistry.resolve()` or the provider's default model). Without a block key, existing workflows behave unchanged.

```json
[
  { "type": "shell",  "config": { "command": "git diff --stat HEAD~1" } },
  { "type": "simple", "config": { "command": "Summarize the diff:\n\n{{ previous_output }}", "model": "haiku" } },
  { "type": "simple", "config": { "command": "Assess the architecture risks ...", "provider": "PI", "model": "anthropic/opus", "effort": "high" } }
]
```

Rules:
- A block that only sets `provider` does **not** inherit the workflow model when the provider differs from the workflow's provider — model ids are provider-specific (`opus` vs. `anthropic/opus`). With the same provider (or without `provider`), the workflow model still applies.
- Nested blocks (e.g. `blocks` in `switch`) carry their own keys; the wrapper block does not pass its values down to child blocks.
- The `integration` block uses `provider` for the integration provider and is not an AI block — no override applies there.
- In the UI block editor, the "AI provider and model for this block" fields appear only on AI-capable block types; the model suggestions come from the host catalog of the chosen provider (`GET /api/v1/system/ai-providers/{id}/models`), free text remains allowed.
- The values actually used appear in the step metadata of the run details: `aiRequest.provider`, `aiRequest.model`, `aiRequest.effort` (for `agentic` additionally top-level `provider`/`model`).
- Wrapper blocks (`retry`, `foreach`, `while`, `conditional`, `switch`, `skill-capability`) carry the `aiRequest`, `provider`, and `requestedModel` of their **first inner AI call** into their aggregate step (provider and model are constant per block); the run page shows them as pills. Additional keys: `foreach` → `totalItems` + `items` (`{index, item, status, durationMs}`, max. 500, item label truncated to 120 characters), `while` → `iterationCount` + `iterations`, `retry` → `attempts` + `maxAttempts`, `conditional` → `conditionMet`, `switch` → `expression` + `matchedCase`. Pure shell iterations carry no `aiRequest`.

---

## 7. Execution Environment

### Host Environment (Default)

Shell blocks without `container_image` run on the host. Available tools:

- `curl`, `git`, `python3`, `jq`, `nodejs`, `npm`, `wget`, `unzip`, `bash`

Anything else (pip packages, go, docker-cli, gh, trivy, specialized tools) is **NOT** available.

### Live Output While Watching a Run

While a block runs, its output appears line by line in the live logs of the execution page: shell and container blocks
with stdout/stderr (merged), Claude blocks as tool calls, truncated text and a closing line, pi/codex as raw lines —
also for blocks inside `foreach`/`while`/`retry` (under the index of the outer step). Notes:

- Progress bars using `\r` only show their latest state; very long lines are truncated.
- Programs that buffer their output (e.g. `python3` without `-u`) only show up when they flush — use `python3 -u` or
  `PYTHONUNBUFFERED=1` for live output.
- The step output (`{{ block.<name>.output }}`, step card) is unaffected and still contains the complete output.

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
- The server can restrict `container_image` via an allowlist (`automation.execution.allowed-container-images` /
  `AUTOMATION_ALLOWED_CONTAINER_IMAGES`). Empty (default) allows any image. If an image is not listed, the
  block fails visibly with exit code 125 and a SECURITY entry in the activity log instead of running silently.

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
2. **ACT**: A **separate** Claude CLI call executes the ACTION (uses `--append-system-prompt`, `--model`, project directory)
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

When the history exceeds 7 entries, middle turns are summarized — the first turn (initial prompt) and the last 4 turns remain fully intact. This means earlier observations may be compressed. Design goals and success criteria so they can be evaluated from the most recent context.

### Stale File Pollution

Old files from previous runs can pollute the agentic block's context (e.g. glob patterns picking up stale reports). Add a cleanup step before agentic blocks:

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
| `total_timeout_seconds` | 1800 | Total timeout (30 min) |
| `max_total_tokens` | 200,000 | Token budget (input+output) |
| `max_repeated_actions` | 3 | Abort after N identical actions |
| `require_approval` | false | Human gate before each action |

### Action Compression

When an ACTION exceeds 100,000 characters, it is compressed via a separate Claude CLI call (60s timeout) before being sent to the ACT phase.

### Token Tracking

Token usage (input + output) is tracked across all iterations. When the budget is exhausted, the block stops. If REASON or ACT provides no usage data, a WARN is logged — the budget is not enforced.

---

## 9. Workflow Instructions & Memory

### Two-Level Instruction System

1. **Base instructions** (`src/main/resources/agent-instructions.md`): Loaded at startup, apply to ALL workflows. Cover working directory, file output, error handling, quality standards.

2. **Workflow-specific instructions** (`workflow.instructions` field): Per workflow, editable in the UI (Monaco editor). Appear as part of the system prompt.

### How Instructions Are Injected

- **Block execution** (all block types): Via `--append-system-prompt` flag on the Claude CLI
- **Agentic REASON phase**: Via `--system-prompt` flag, includes workflow instructions in the `## Workflow Instructions` section

### Workflow Memory

Workflows can accumulate knowledge across runs. Memory can be extracted **manually** or **automatically**.

#### How It Works

1. **Extract** (manually or automatically): After one or more runs, trigger extraction via UI ("Memory" tab on the workflow detail page) or API. Select runs and choose a provider (Claude or Codex). The `ConversationPreprocessor` compresses step outputs by ~50-70% before sending them to the AI, which creates a structured knowledge entry (title, Markdown content, tags).
2. **Activate** (manually or automatically): With manual extraction, memory starts as `active=false`. With automatic extraction, it is activated immediately. Only one version can be active at a time.
3. **Used on next run**: Before each workflow execution, the active memory is written to `.claude/rules/project-memory.md` in the project directory. The Claude CLI accesses this file automatically via the rules system; pi and Codex do not read `.claude/rules/`, so they get memory, skills and project context appended to the system prompt (`RunContextFiles`).
4. **Versioning**: Multiple memory versions per workflow are retained. Older versions can be restored by activating them.
5. **Editing**: Title and content can be manually refined in the UI (Monaco editor).

#### Automatic Extraction (Auto-Memory)

Configurable per workflow via `metadata.autoMemoryExtraction`:

```json
{
  "metadata": {
    "autoMemoryExtraction": {
      "enabled": true,
      "interval": 5
    }
  }
}
```

- **`enabled`**: Toggle automatic extraction on/off
- **`interval`**: After how many successful runs extraction occurs (default: 5)
- Only on **COMPLETED** runs (not FAILED, CANCELLED etc.)
- Counts successful runs since last extraction
- New memory is automatically activated (replaces the previous active memory)
- Configuration also available via the "Auto-Memory" toggle on the workflow detail page

#### When to Use

Extract memory after a workflow produces a run with valuable insights — patterns, pitfalls, domain knowledge — that should guide future runs. Especially useful for agentic workflows that learn project-specific conventions over time. **Auto-memory recommended** for workflows that run regularly and whose insights accumulate.

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
- **stopOnFailure**: Controls whether the chain stops on workflow failure — stops only when **both** flags are set (chain-level AND entry-level must be `true`)

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
GET    /api/v1/chains                      # List all chains
POST   /api/v1/chains                      # Create chain
GET    /api/v1/chains/{id}                 # Get chain
PUT    /api/v1/chains/{id}                 # Update chain
DELETE /api/v1/chains/{id}                 # Delete chain
POST   /api/v1/chains/{id}/execute         # Start execution
GET    /api/v1/chains/runs                 # List all chain runs
GET    /api/v1/chains/{id}/runs            # List runs for a chain
GET    /api/v1/chains/{id}/runs/{runId}    # Get single run
POST   /api/v1/chains/runs/{runId}/cancel  # Cancel run
```

### Limits

- Polling-based completion check (2s interval)
- 180-minute timeout per workflow in the chain (3 hours, configurable via `automation.chain.workflow-timeout-minutes`)

---

## Integration Block (Plugin System)

Invokes actions from connected external services (Google Calendar, Trello, etc.). Part of the plugin system — each `IntegrationProvider` exposes actions that can be used in workflows as `integration` blocks.

**Prerequisite:** The provider must be connected on the integrations page (`/integrations`).

### Config

| Field | Required | Description |
|-------|----------|-------------|
| `provider` | Yes | Provider ID: `google-calendar`, `trello`, etc. |
| `action` | Yes | Action name, e.g. `list_events`, `create_card` |
| `params` | No | Parameters as key-value, supports `{{ template_vars }}` |

### Example: Fetch Next Google Calendar Events

```json
{
  "type": "integration",
  "config": {
    "provider": "google-calendar",
    "action": "list_events",
    "params": {
      "timeMin": "{{ started_at }}",
      "maxResults": 5
    }
  }
}
```

### Example: Create Trello Card After Workflow Completion

```json
{
  "type": "integration",
  "config": {
    "provider": "trello",
    "action": "create_card",
    "params": {
      "idList": "abc123",
      "name": "Result: {{ workflow_name }}",
      "desc": "{{ previous_output }}"
    }
  }
}
```

### Available Actions

All actions for a provider are visible on the integrations page (provider card -> "Test" button). Action names and parameter schemas are displayed there.

### Error Handling

If the provider is not connected or the API fails, the block is marked as `FAILED` with the error message in the output. Token refresh happens automatically.

### Using a New Provider as a Workflow Block

1. Connect the provider on the integrations page (OAuth flow)
2. Add an integration block in the workflow editor
3. Select provider and action, configure parameters
4. Template variables (`{{ previous_output }}`, `{{ started_at }}`) work in params

---

## 11. Dos and Don'ts

### DO

- **Set `timeout_seconds` explicitly** for complex blocks — don't rely on defaults
- **Use Opus for analysis/audit workflows** — Sonnet hallucinates findings
- **Use `set -euo pipefail`** at the beginning of multi-line shell commands
- **Use absolute paths** in agentic block goals and actions
- **Add cleanup steps** before agentic blocks to avoid stale file pollution
- **Keep agentic block goals specific** — "Create index.html in output/" instead of "Build a website"
- **Use `{{ previous_output }}`** to chain block outputs — data flows forward through the pipeline
- **Set both `timeout_seconds` and `iteration_timeout_seconds`** for agentic blocks — auto-alignment helps, but explicit is better
- **Add a verification block** after agentic blocks (check files, run tests, validate output)

### DON'T

- **Don't use Sonnet for code analysis** — hallucinates false positives (fake SQL injection, fake security issues)
- **Don't set `iteration_timeout_seconds` lower than `timeout_seconds`** — the CLI will succeed but the iteration will be marked as TIMEOUT
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
| No cleanup before agentic block | Stale files from previous runs pollute context | Add `rm -rf output/` shell block before |
| `timeout_seconds: 900` with default `iteration_timeout_seconds` | Iteration times out at 300s even though CLI succeeds | Set both timeouts or rely on auto-alignment |

---

## Appendix: Template Quick Reference

What belongs in the public workflow pool, how it differs from the built-in templates and which criteria the hygiene gate enforces is described in [workflow-pool-kuratierung.md](workflow-pool-kuratierung.md).

All 65+ built-in templates with their primary pattern:

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
| `auto-fix-java` | Multi-Stage + Approval | CODE |
| `auto-fix-python` | Multi-Stage + Approval | CODE |
| `auto-fix-typescript` | Multi-Stage + Approval | CODE |
| `hunt-quality-pipeline` | Multi-Stage Pipeline | CODE |
| `folder-backup` | Shell Chain | UTILITY |
| `github-issues-export` | Shell + Approval (preview, redaction) | TEAM |
| `github-issues-import` | Shell | TEAM |
| `github-issues-sync` | Shell Chain | TEAM |
| `github-project-bootstrap` | Shell Chain | CODE |
| `dependency-update-check` | Shell + Conditional | CODE |
| `log-rotate-cleanup` | Shell Collect + AI Analyze | INFRA |
| `pool-neutrality-audit` | Audit Pipeline | QUALITY |
| `pr-review-summary` | Shell Collect + AI Analyze | TEAM |
| `research-enhance` | Sequential Simple Chain | RESEARCH |
| `saas-auto-fix` | Analyze + Agentic + Verify | CODE |
| `saas-containerize` | Shell + Approval | DEPLOY |
| `saas-continuous-improve` | Shell Collect + AI Analyze | AUTOMATION |
| `saas-doc-update` | Agentic | DOCS |
| `saas-feature-implement` | Analyze + Agentic + Verify | CODE |
| `saas-feature-loop` | Shell Scan + ForEach | TRANSFORM |
| `saas-marketing-site` | Analyze + Agentic | CONTENT |
| `saas-scaffold` | Shell Chain | TRANSFORM |
| `saas-spec-to-buildplan` | Document + Analyze + Approval | BUSINESS |
| `secret-scanner` | Shell + Conditional | CODE |
| `service-health-monitor` | Shell Scan + ForEach + AI | INFRA |
| `sprint-retrospective` | Shell Collect + AI Analyze | TEAM |
| `ssl-cert-check` | ForEach + AI Analyze | INFRA |
| `standup-from-git` | Shell Collect + AI Analyze | TEAM |
| `container-vuln-scan` | Shell + Conditional | CODE |
| `database-backup-verify` | Shell Chain | CODE |
| `directory-sync` | Shell Chain | UTILITY |
| `docker-image-build-push` | Shell + Conditional | CODE |
| `test-matrix` | Shell Scan + ForEach + Skill | CODE |
| `todo-creator` | Agentic | UTILITY |
| `topic-deep-dive` | Sequential Simple Chain | RESEARCH |

---

## Auto-Repair

Auto-Repair is the platform's self-healing mechanism: When a workflow fails, the AI analyzes the error, corrects the workflow blocks, and automatically verifies the repair.

### How It Works

1. **Detection** — A `WorkflowRunCompletedEvent` with status `FAILED` is detected
2. **Analysis** — The AI (`WorkflowTextGenerator`) analyzes the error and the current blocks
3. **Fix** — Corrected blocks are generated as JSON and saved in the workflow
4. **Verification** — The repaired workflow is re-executed (default: up to 2 attempts, configurable 1-5)
5. **Loop protection** — Repair runs are marked with `autoRepairAttempt=true` to prevent infinite loops

### Repair Modes

| Mode | Behavior |
|------|----------|
| `auto` (default) | Prefers test instance, falls back to local execution |
| `test` | Test instance only — skips if test is not available |
| `local` | Always local execution on the same instance |

### Configuration

**Global** (Settings -> General -> Auto-Repair):
- Toggle: Enable/disable auto-repair
- Dropdown: Repair mode (Auto/Test/Local)

**Per workflow** (workflow metadata):
```json
{
  "metadata": {
    "autoRepair": {
      "enabled": true,
      "mode": "auto",
      "maxRetries": 3,
      "timeoutSeconds": 900
    }
  }
}
```

| Field | Default | Range | Description |
|-------|---------|-------|-------------|
| `enabled` | false | — | Enable auto-repair |
| `mode` | `"auto"` | `auto`/`test`/`local` | Repair mode |
| `maxRetries` | 2 | 1-5 | Maximum repair attempts |
| `timeoutSeconds` | 600 (10 min) | 60-3600 | Timeout per repair attempt |

Priority: Workflow metadata > Global setting > Default (`auto`, disabled).

### API Endpoints

| Action | Method | Path |
|--------|--------|------|
| Repair single workflow | POST | `/api/v1/workflows/{id}/repair` |
| Repair all failed | POST | `/api/v1/system/repair-failed?force=false` |
| Set global repair mode | PUT | `/api/v1/system/settings/auto-repair-mode` — body: `{"value": "auto"}` |
| Set max retries | PUT | `/api/v1/system/settings/auto-repair-max-retries` — body: `{"value": "3"}` |
| Set timeout | PUT | `/api/v1/system/settings/auto-repair-timeout-seconds` — body: `{"value": "900"}` |

### Notes

- In `auto` or `local` mode, the test instance does **not** need to be running
- Default 2 repair attempts per failure (configurable 1-5 via metadata or global setting `auto-repair-max-retries`)
- Default 600s timeout per attempt (configurable 60-3600 via metadata or global setting `auto-repair-timeout-seconds`)
- Repair runs appear in the ActivityLog
- AI repair uses the configured `WorkflowTextGenerator` interface (Claude or Codex)

---

## TodoTrigger — Self-Improvement Loop

TodoTriggers close the self-improvement loop: When an audit workflow finds a problem and creates a TODO, a TodoTrigger can automatically start a fix workflow that resolves the issue.

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
  "autoClose": true,
  "enabled": true,
  "processBacklog": true,
  "contextTemplate": {}
}
```

**Tag matching**: Comma-separated with AND semantics. All tags in the pattern must be present in the TODO. Example: `"backend,bug"` matches a TODO with tags `["backend", "bug", "security"]`, but not `["backend", "feature"]`.

**Additional fields**:

| Field | Default | Description |
|-------|---------|-------------|
| `enabled` | `true` | Trigger active/inactive |
| `processBacklog` | `true` | Retroactively process existing open TODOs (polling every 5 min, max 1 per tick) |
| `contextTemplate` | `{}` | Static key-value pairs passed to the fix workflow in addition to the TODO context |

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
| `todo_tags` | Tags as JSON string (e.g. `["backend","bug"]`) |
| `todo_priority` | HIGH / MEDIUM / LOW |
| `todo_project_id` | Project UUID |
| `todo_source` | USER / AI / WORKFLOW |
| `todo_reference_type` | e.g. `WORKFLOW_RUN` |
| `todo_reference_id` | Run UUID |
| `todo_trigger_id` | Trigger UUID |

Additionally, all entries from the trigger's `contextTemplate` are passed as additional variables.

### API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/todo-triggers` | List all triggers |
| GET | `/api/v1/todo-triggers/{id}` | Get single trigger |
| GET | `/api/v1/todo-triggers/workflow/{workflowId}` | Triggers by target workflow |
| POST | `/api/v1/todo-triggers` | Create trigger |
| POST | `/api/v1/todo-triggers/{id}/enable` | Enable trigger |
| POST | `/api/v1/todo-triggers/{id}/disable` | Disable trigger |
| DELETE | `/api/v1/todo-triggers/{id}` | Delete trigger |

### Loop Prevention (3 Layers)

1. **Max attempts**: After `maxAttempts` failed fix attempts, no further triggering occurs
2. **Source filter**: Triggers can be restricted to `sourceFilter: "WORKFLOW"` — manually created TODOs do not trigger a fix
3. **Fix attempt tracking**: `todo.fixAttempts` counts attempts, `todo.lastFixRunId` stores the last run

### Auto-Close

With `autoClose: true` (default): When the fix workflow completes with a **GREEN** gate status, the TODO is automatically set to DONE. With YELLOW or RED, it remains open.

### Best Practices

- **Keep tag patterns specific** — `"backend,security,bug"` instead of just `"bug"`, otherwise the fix workflow triggers on every bug
- **Design fix workflows to be idempotent** — they may run multiple times
- **Set gate status in fix workflows** — so auto-close works correctly
- **Limit maxAttempts** — Default 3 is reasonable, higher values risk infinite loops with systematic failures
