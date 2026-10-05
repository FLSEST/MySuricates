# Workflow Building Guide

Practical guide for creating effective workflows in MySuricates. Covers all block types, proven patterns, timeout configuration, and common pitfalls.

**Target audience**: AI agents (Claude, Codex) and developers who create or debug workflows.

> Short identifiers in parentheses such as `(182bd224)` or `TODO 63ecc124` are
> provenance markers: commits of this repository, or items in the accompanying
> MySuricates instance. They record when and why something came about — from the
> outside, only the commit can be looked up.

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

Every call needs the `X-API-Key` header. The backend creates the key on first start; for an instance
set up by the installer `scripts/instance.sh key --name <name>` shows it, with the source setup it is
in `~/.mysuricates/api-key`. The `projectId` is the ID of an
existing project — `GET /api/v1/projects` lists the projects with their `id` field, and in the UI it
is part of the project page's address (`/projects/<id>`). Full reference:
[api-referenz.md](api-referenz.md).

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

MySuricates supports 20 block types. Each block has a `type` and a `config` map. **Every config key** with type, required flag, default and allowed values is in the generated [block reference](block-referenz.md). This chapter gives, per type, what it is good for, a minimal example and what you need to know about its behaviour.

**Checked at save time.** Unknown block types are refused (HTTP 400; the message names the type and the allowed ones), checked against the executor registry (`BlockTypeRegistry`), recursively in `switch` `cases[].blocks` and `default` as well. A missing or empty `type` counts as unknown. This holds for every write path (UI, REST API, import, pool installation, seeder, factory) and for restoring an old version too: a definition that can no longer run does not come back.

**Editor and analyzer.** The config field of the block editor suggests keys and values (Ctrl+Space, a second time shows the explanation) and marks unknown keys, wrong values and missing required fields in yellow; saving stays possible. The analyzer uses the same source, `blockConfigSchema.json`; the workflow page shows its findings below the header: `unknown-config-key`, `missing-required-config-key`, `invalid-config-value`, `legacy-config-key` (LOW, an old key name such as `workflowId`), `dead-nested-blocks` (HIGH: no type runs a `config.blocks` list) and `unknown-block-field` (HIGH when a config key sits next to `config` instead of inside it – such fields are stored, not dropped). Plus access to the platform itself: `self-api-call` (shell code calls the instance's REST API or reads its key file; the key-free probes excepted) and `direct-db-access`. A `HIGH` finding of these two blocks unattended starts (`403 UNATTENDED_PLATFORM_ACCESS`, a SECURITY entry, no run); started by hand the workflow still runs ([architektur.md](architektur.md) → Workflow hardening). Instead: context variables such as `{{ open_todos }}`, the `todo-*` blocks and, for other services, the `integration` block. Workflows an AI creates go through the same check right after creation. Whoever teaches an executor a new config key adds it to `blockConfigSchema.json` (`ExecutorConfigKeyDriftTest`).

### Fields on a block

Besides `type` and `config` a block knows these fields:

| Field | Meaning |
|---|---|
| `name` | Name under which later blocks read the output via `{{ block.<name>.output }}`. Recommended: letters, digits, `_` and `-`; only the reserved name `previous_output` is refused (`BlockNameRules`). Keep it unique within the workflow – on a clash the value written last wins. |
| `skillIds` | Skills active for this block **in addition** to the workflow skills; the `WorkflowExecutor` restores the baseline afterwards |

The keys every block understands in `config` are listed under [Common fields](block-referenz.md#common-fields). What you need to know about them:

- **`on_failure`**: a FAILED block ends the run (FAILED). Only `continue` (case-insensitive) lets the workflow carry on; any other value, a typo such as `"fail"` included, stops like `stop`.
- **`run_if`** (top level only): an exit code ≠ 0 makes the block `SKIPPED` with the output `Übersprungen (run_if: exit N)`; its executor is not called, a `variable_name` variable stays unset. Typical: `test -s .git/state/ids.txt`.
- **`provider`/`model`/`effort`** (AI-capable blocks): precedence block > workflow > system default, see [Reasoning Effort](#reasoning-effort). On the `integration` block, `provider` means the integration provider instead.

### `simple` — AI Text Processing

One prompt to the AI CLI on the host, one answer; no shell. For analysis, summaries, reviews.

```json
{ "type": "simple", "config": { "command": "Summarize:\n\n{{ previous_output }}" } }
```

### `shell` — Shell Command Execution

Runs a command on the host, with `container_image` in a container ([Execution Environment](#7-execution-environment)). `checked_command` derives a gate from the exit code ([Quality Gate Markers](#quality-gate-markers)).

```json
{ "type": "shell", "config": { "command": "set -euo pipefail\ngit log --oneline -20" } }
```

**Placeholders in shell commands** – the rules you need to know:

- Values from outside (webhook payload including `git_*`) as well as `{{ previous_report }}` and `{{ open_todos }}` are **always** put in single quotes by the platform in shell commands (never in AI prompts). A run that carries such data is *tainted*: from then on block outputs (`{{ previous_output }}`, `{{ block.<name>.output }}`, `{{ step_N_output }}`, `foreach` items) are quoted too, in sub-workflows and after a resume as well. The same applies once platform data stood in the prompt of an AI block and its output is recorded.
- So write such placeholders **unquoted** and give the block `"shell_escape_variables": true`. Inside your own quotes the escaping does nothing: `PREV='{{ previous_output }}'` becomes `PREV=''x; id''` for the value `x; id` and runs `id`. Without the option the value arrives raw in clean runs. Safe form: `set -- {{ previous_output }}` and then `ANSWER="${1:-}"` (for the shipped definitions `TemplateHygieneGateTest` and `PlatformPromptQuotingGateTest` enforce it).
- An unset variable stays literally in place: `set -- {{ source_dir }}; SOURCE_DIR="$*"`, recognisable by `[ "$SOURCE_DIR" = "{{ source_dir }}" ]`.
- In a quoted heredoc (`<<'PY'`) a placeholder ends up as source code in the interpreter. Pass values on the shell line (`PROJECT_ID={{ project_id }} python3 - <<'PY'`, in the script `os.environ['PROJECT_ID']`); shell blocks get no other environment variables.
- `scripts/lib/shell_placeholder_audit.py <definitions.json>` classifies every output placeholder in shell fields by its role and reports only what needs a change (exit 1 = finding).

Trust model: [deployment.md → Three trust classes](deployment.md#three-trust-classes-for-context-values).

### `agentic` — Goal-Driven AI Agent

An autonomous loop of REASON and ACT per iteration – the most powerful and most expensive type, for multi-step tasks that need judgement. Details: [Agentic Block Deep Dive](#8-agentic-block-deep-dive).

```json
{ "type": "agentic", "config": { "goal": "Create portfolio/index.html from {{ filepath }}.",
  "success_criteria": "index.html contains every section.", "max_iterations": 8, "timeout_seconds": 600 } }
```

- `max_total_tokens` counts per iteration `inputTokens` + `outputTokens` of REASON and ACT, cache tokens (`cacheReadInputTokens`, `cacheCreationInputTokens`) deliberately not – counted, the usual budgets would break in the first iteration already. Codex and Pi report no real numbers; there the estimate counts (characters / 4, `estimated: true`). It is checked after every iteration; if the sum is above the budget, the block ends with `[TOKEN BUDGET EXCEEDED: …]`.
- Two timeouts: `timeout_seconds` per CLI call, `iteration_timeout_seconds` per iteration – see [The Dual-Timeout Trap](#the-dual-timeout-trap).

### `retry` — Retry on Failure

Repeats `command` up to `max_retries` times with `delay` seconds in between; with `exponential_backoff` the pause doubles on every attempt (`delay`, `delay * 2`, `delay * 4`, …). For flaky tests and network calls.

```json
{ "type": "retry", "config": { "command": "npm test", "max_retries": 3, "delay": 5, "execution_mode": "shell" } }
```

### `conditional` — If/Then/Else

Evaluates `condition` and runs `then_command` or `else_command`. `execution_mode` applies to condition and branches: `shell` → exit 0 = true; `ai` (default) → the AI answers TRUE or FALSE.

```json
{ "type": "conditional", "config": { "condition": "test -f package.json", "then_command": "npm test",
  "else_command": "echo 'No tests'", "execution_mode": "shell" } }
```

### `switch` — Multi-Way Branching

Compares the resolved `expression` with the `value` of every case (alternatives with `|`) and runs the blocks of the match, otherwise `default`. In the example an earlier block `traffic_light` only prints `GREEN`, `YELLOW` or `RED`.

```json
{ "type": "switch", "config": { "expression": "{{ block.traffic_light.output }}",
  "cases": [ { "value": "GREEN", "blocks": [{ "type": "shell", "config": { "command": "echo 'All good'" } }] },
             { "value": "YELLOW|RED", "blocks": [{ "type": "shell", "config": { "command": "echo 'Problems'" } }] } ],
  "default": [{ "type": "shell", "config": { "command": "echo 'Unknown'" } }] } }
```

- Compared without leading and trailing whitespace (an AI answer `GREEN` with a line break matches `GREEN`); case matters.
- Nested blocks may only be `shell` or `simple`, other types are refused.
- The blocks of a case pass `{{ previous_output }}` along; whoever needs the state before the switch names the block before it. A `name` on a case block makes its output available as `{{ block.<name>.output }}`; the output of the switch is the concatenation of all case blocks that ran.
- `gate_status` does not work as an expression: the run evaluates the markers only at the end.

### `foreach` — Iterate Over Items

Runs `command` for every item from `items` or from the lines of `items_command` (which always runs as shell) – or, with `workflow_id`, one run of another workflow per item.

```json
{ "type": "foreach", "config": { "variable_name": "repo", "items_source": "command",
  "items_command": "find . -maxdepth 2 -name package.json | xargs -n1 dirname | sort -u",
  "execution_mode": "shell", "shell_escape_variables": true, "command": "cd {{ repo }} && npm outdated --json || true" } }
```

- Item variables: `{{ <variable_name> }}`, `{{ <variable_name>_index }}` (from 0), for a JSON object its top-level fields as `{{ <variable_name>.<field> }}`. Empty lines are dropped; an empty list means zero iterations (COMPLETED, `totalItems: 0`).
- Failures: if an item fails, the block fails (the remaining items still run); with `continue_on_error: true` it ends COMPLETED (`N/M items failed: …`). After `max_consecutive_failures` failures **in a row** (default 3, since 2026-10) the loop stops and the block fails, even with `continue_on_error`; `0` turns this off, e.g. for reachability checks across many hosts. `on_failure: "continue"`, in contrast, only lets the workflow carry on after the FAILED block.
- The error message starts with the failed items (`Step 1 (foreach) failed: 1/37 items failed: erkner`); the block output stays the log of all items in their order.
- **Sub-workflow per item** (`workflow_id`): one run of its own per item, as with the `sub-workflow` block (`SubWorkflowLauncher`, admitted as `SUB_WORKFLOW`). The child run gets this run's variables without platform data, the item variables and the values from `context`; the taint travels along. An item fails when its child run does not end `COMPLETED`. The output `[item]: <last output>` carries `derivedOutput: true` – markers count only in the child runs. `execution_mode`, `timeout_seconds` and the AI keys have no effect in this mode.
- A placeholder in `workflow_id` is resolved **once before the first item** and only from trusted context variables – not from webhook data or block outputs –, otherwise the block fails before the first item. `context` must not set reserved names (keys with `__`, platform data, values of the run, the item variables).

### `while` — Loop Until Condition

Repeats `command` while `condition_command` holds, at most `max_iterations` times; without `condition_command` until `max_iterations` or the first failure. `shell`: exit 0 = continue; `ai` (default): `false`, `0` or an empty answer = stop.

```json
{ "type": "while", "config": { "condition_command": "Answer ONLY 'true' if the quality is below 8.",
  "command": "Improve the text. Output the complete version.", "max_iterations": 3 } }
```

### `approval` — Human Approval

Pauses until a person answers – in the dialog on the run page or on the **Approvals** page (`/approvals`), whose sidebar entry pulses while a run is waiting. For steps before push, deploy or delete.

```json
{ "type": "approval", "config": { "question": "## Result\n\n{{ previous_output }}\n\nContinue?",
  "options": ["Yes", "No", "Abort"], "reject_options": ["No", "Abort"], "on_no": "fail" } }
```

- `POST /api/v1/executions/{id}/approve` accepts only the `options` (case-insensitive), otherwise 400. A rejection is whatever `reject_options` lists (default “No”/“Nein”) – list your own rejections there, or the workflow carries on despite `on_no`.
- “Yes always” remembers the answer to the same question until the run ends. `mode: "auto"` with `auto_answer` answers at once, without a person.

### `human-input` — Collect User Input

Pauses until a person enters a value or fills in a form (`fields`); the answer always lands as text in the variable `variable_name`.

```json
{ "type": "human-input", "config": { "prompt": "Which language should the docs be in?", "input_type": "select",
  "variable_name": "doc_language", "options": ["Deutsch", "English"], "default_value": "English" } }
```

On timeout the block ends COMPLETED with `default_value` if `use_default_on_timeout` applies (default) and a default is set, otherwise FAILED.

### `sub-workflow` — Nested Workflow

Runs another workflow as a step and waits for it: at most 5 levels, cycles are detected, 30 minutes. If the parent run is cancelled, it cancels the child run too. To run the same workflow per list entry, use `foreach` with `workflow_id`.

```json
{ "type": "sub-workflow", "config": { "workflow_id": "<workflow-uuid>" } }
```

- The child run gets the variables and `{{ previous_output }}` of the parent run, but not its platform data (`previous_report`, `open_todos`, `request_metrics`, `goal_status`/`goal_guard_*`, `audit_findings`) – the platform computes those for it. The taint travels along.
- The output is the child run's last output (`derivedOutput: true`); `gate_status:` and `todo_item:` markers in it count only in the child run. Placeholders in `workflow_id`: as with `foreach`.

### `skill-capability` — Invoke a Skill

Runs a capability of a skill as one AI call.

```json
{ "type": "skill-capability", "skillIds": ["builtin-code-review"],
  "config": { "capability": "review_code", "input": "Review: {{ previous_output }}" } }
```

- The capability is resolved only through the **block's** `skillIds`. Without a matching skill only the capability name runs as the task – without description, AI context and `writes` check.
- Without `allow_writes` the CLI runs with a read-only whitelist (Read/Grep/Glob/WebFetch/WebSearch/Task/TodoWrite); a `writes:true` capability (`builtin-test-generator/*`, `builtin-feature-implementer/*`) then fails at once.
- If the AI CLI exits with a code ≠ 0, the step fails like a `simple` block.

### `integration` — External Service

Calls an action of a connected integration provider: [Integration Block](#integration-block-plugin-system).

### `document` — Extract Text from Documents

Extracts the text of a file (PDF with a text layer, DOCX/XLSX/PPTX, HTML/XML, TXT, EPUB, RTF, ODT) via Apache Tika, without native dependencies, 0 tokens.

```json
{ "type": "document", "config": { "file_path": "{{ invoice_path }}", "variable_name": "invoice_text", "fail_on_empty": true } }
```

Limits: 50 MB per file (`automation.document.max-bytes`), 5 million characters of output (`automation.document.max-characters`); `../` out of the project path is blocked. Scanned PDFs without a text layer return empty text – no OCR.

### `todo-create` / `todo-update` / `todo-close` — Native TODO Operations

Create, change or close TODOs directly through the TodoService – no REST self-call, no DB access, 0 tokens.

```json
[ { "type": "todo-create", "config": { "title": "Fix {{ component }}", "priority": "HIGH", "tags": ["bug"] } },
  { "type": "simple", "config": { "command": "Fix the problem ..." } },
  { "type": "todo-close", "config": { "id": "{{ todo_id }}" } } ]
```

- `todo-update` needs exactly one of: `id` (one TODO; only set, non-empty fields change) or `updates`, a JSON list for up to 50 TODOs **of the run's project**. `note` is appended to the description (`— <date> · <workflow> (run <id8>): <note>`, date in the instance's time zone); at most 10 tags each. Broken JSON, no list or an unknown key fail the step before anything is written; an unsuitable entry is skipped with a reason (`✓ N aktualisiert, M übersprungen`).
- For a **dynamic number** of TODOs from analysis workflows use `todo_item:` markers ([TODO markers](#todo-markers-todo_item)); audit templates use the `mysuricates:audit-result:v1` envelope.

### `compress` / `compress-ai` — Visibly Condense Outputs

Condense the output of **exactly one** earlier block: `source` is a block name (a named `switch` case block too) or `"previous"`. The original stays available as `{{ block.<source>.output }}`, the condensing is final.

```json
[ { "type": "shell", "name": "backend_tests", "config": { "command": "mvn -q test", "on_failure": "continue", "checked_command": true } },
  { "type": "compress", "name": "backend_compact", "config": { "source": "backend_tests", "stages": [
      { "op": "text.normalize" }, { "op": "lines.filter", "exclude": ["^\\s+at "] },
      { "op": "text.truncate", "max_chars": 18000, "keep": "head_tail" } ] } },
  { "type": "simple", "config": { "command": "Assess:\n{{ block.backend_compact.output }}" } } ]
```

- The output starts with a header line of source, status, exit code, duration and characters before → after, and a line with the stages (in English); status and exit code come from the stored step data. Gate and TODO markers in it are not evaluated.
- `compress`: the [stages](block-referenz.md#stages) run in order, JSON stages before text stages.
- `compress-ai`: one tool-less call, provider `CLAUDE` only (otherwise set `"provider": "CLAUDE"` on the block), in a fresh, private working directory (`--tools ""`, `--strict-mcp-config`, `--setting-sources local` – user-level Claude settings do not apply). Inputs above the setting `compress-ai-max-input-chars` (default 60,000) fail without a call. The more isolated `--bare` does not read an OAuth login and is therefore not used (TODO edb38a38).
- Neither is allowed as a nested block in `switch`.

### `dashboard-report` — report the dashboard result without a marker

Fills the dashboard result of the run directly from config – the same fields, bounds and validation as the [`dashboard_widget:` marker](#dashboard-marker-dashboard_widget), but without text search; no AI call, 0 tokens. Module `dashboard-report` (on by default).

```json
{ "type": "dashboard-report", "config": { "status": "{{ block.traffic_light.output }}", "title": "Nightly QA",
  "kpis": [{ "label": "Hunts", "value": "{{ hunt_count }}", "trend": "UP" }], "summary": "{{ block.analysis.output }}" } }
```

- A placeholder that is empty at run time drops the field (an error for `status`); a KPI is dropped only when all its values are empty. An **unresolved** placeholder or a contract violation (too long, unknown field in a KPI, number instead of string, invisible characters) fails the step; the message names the field, never the value.
- Once a `dashboard-report` block has written, no `dashboard_widget:` marker is evaluated any more; of two blocks the one executed last wins. `{{ gate_status }}` does not exist during the run.
- In the editor the block has a form with an optional “Proposal from the last run” button (one AI call, Settings → Execution, provider `CLAUDE`); it is applied only after confirmation.

---

## 3. Template Variables

### Variable Syntax

Variables use double braces: `{{ variable_name }}` or `{{variable_name}}`. The space must be the same on both sides: `{{ variable_name}}` is **not** resolved and stays literally.

**Help in the block editor:** After `{{` the config field suggests exactly the variables bound at that position: outputs of earlier blocks, their `variable_name`, system variables and start values. Behind each placeholder it shows where the value comes from (`{{ previous_output }} ← #3 backend_tests`), and the hover explains it. Underlined in yellow: references to blocks that do not exist or run later, uneven spaces, and quoted output placeholders in shell commands. The logic lives in `ai-automation-frontend/src/utils/placeholderContext.ts` and follows `BlockStepRunner`/`ExecutionContextFactory`: whoever adds a variable there adds it here too.

### Built-in Variables

| Variable | Source | Description |
|---|---|---|
| `{{ previous_output }}` | Auto | Output of the previous block (step N-1) |
| `{{ step_N_output }}` | Auto | Output of step N (e.g. `{{ step_0_output }}` for the first block) |
| `{{ block.<name>.output }}` | Auto | Output of a named block (block must have a `name` field) |
| `{{ previous_chain_output }}` | Auto | Output of the previous chain entry (chains only) |
| `{{ chain_run_id }}` | Auto | ID of the chain run that started the entry (chains only); the run the chain starts carries the same value as `chainRunId` — runs that only inherit the context do not (retry, resume without a completed step, sub-workflow): there the placeholder is set, `chainRunId` stays `null` |
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
| `{{ trigger_source }}` | What started the run: `MANUAL`, `RETRY`, `RESUME`, `SCHEDULE`, `WEBHOOK`, `TODO_TRIGGER`, `TODO_RESOLUTION`, `COMPLETION_TRIGGER`, `CHAIN`, `SUB_WORKFLOW`, `ASSISTANT`, `AUTO_REPAIR`, `AUTO_RESUME`, `WISH` |
| `{{ previous_report }}` | Output of the report block (`metadata.audit.reportBlock`) from the last COMPLETED run of the same workflow in which that block completed; without `reportBlock` the longest step output from the second step on (over 200 characters). Max 3000 characters, otherwise `"(no previous report available)"`. An audit envelope is cut off, platform markers lose their colon (`gate_status GREEN` stays readable but is no longer taken as a verdict when a model repeats the line), block boundaries such as `</audit_findings>` read `[audit_findings]`. Always treated as untrusted → always single-quoted in shell blocks, so write it unquoted |
| `{{ open_todos }}` | Markdown list of open/in-progress TODOs for the project inside an `<open_todos>…</open_todos>` block, or `"(keine offenen TODOs)"`. Titles and tags are flattened to one line, capped at 200/80 chars and stripped of platform markers (`todo_item:`, `gate_*:`, `dashboard_widget:`) — they may originate from imported issues and are data, not instructions. At most 150 entries. Always treated as untrusted → always single-quoted in shell blocks, so write it unquoted |
| `{{ audit_findings }}` | **Only in audit workflows** (valid `metadata.audit`): the known findings of the workflow's own audit in the project — open, awaiting verification, accepted, dismissed — inside an `<audit_findings>…</audit_findings>` block, one line per finding with status, severity, `ruleKey`, `subject`, title and disposition, at most 250 lines and 60,000 bytes (classified findings first, the rest as a count per status); without findings `"(keine bekannten Befunde)"`. Other workflows do not have the variable, not even through the variable hand-over to a sub-workflow; there the placeholder would stay literally (analyzer rule `audit-only-placeholder`). Format and purpose: [audit-workflow-protocol.md](audit-workflow-protocol.md) → "Known findings in the prompt". Always treated as untrusted → always single-quoted in shell blocks, so write it unquoted |
| `{{ todo_details }}` | **Only with a filter** (`todo_details_tags` and/or `todo_details_exclude_tags` in the run context, usually `defaultContext`): the TODOs of the run's project **with description** as a JSON list inside a `<todo_details>…</todo_details>` block – per entry `id`, `status`, `priority`, `tags`, `title`, `complexity`, `suggestedEffort`, `openQuestion`, `updatedAt`, `description`. More filters: `todo_details_status` (OPEN/IN_PROGRESS/BACKLOG, default OPEN), `todo_details_first_tags` (moved to the front; they do not count against the limit, only against the byte cap), `todo_details_limit` (≤ 150, default 40), `todo_details_chars` (per description ≤ 8000, default 1500); the whole list at most 100,000 bytes of UTF-8, so a shell command stays below the 128 KiB per-argument limit. Tags are compared exactly; `batch:*` means "any tag starting like this". Order: `first_tags`, then priority, then age. Titles flattened, descriptions stripped of markers line by line. Always treated as untrusted → always single-quoted in shell blocks, so write it unquoted |

> **Platform data is never silently absent.** `previous_report`, `open_todos` and
> `request_metrics` (in audit workflows also `audit_findings`) are injected one by one: if one of
> those loads fails, the others still arrive, and the failed variable is set to
> `NICHT GEMESSEN: <name> (nicht verfügbar: <error type>)` — plus a WARN entry in the activity log
> carrying the run id. A gate block therefore sees the stated gap rather than the raw `{{ … }}`
> placeholder or an empty value that would pass as GREEN.

#### Goal context: `goal_status` and `goal_guard_*`

Only when a run's merged context carries `goal_id` (default context or input, for example the goal
guard's schedule) does the platform inject that goal's state (TODO 259f4f66). Without `goal_id` these
variables do not exist. All of them are untrusted (titles may come from imported issues) → always
single-quoted in shell blocks, so write them unquoted. An unknown `goal_id` sets **each** of them to
`NICHT GEMESSEN: <name> (nicht verfügbar: …)` and writes a WARN entry to the activity log. The text
language follows `goal_locale` (`de`/`en`, German when absent). No REST call needed – and none allowed.

| Variable | Content |
|----------|---------|
| `{{ goal_status }}` | Block `<goal_status>…</goal_status>` with goal, target date, progress, milestones (dates of the goal, with completed/linked work packages) and work packages (period or “no period” – then never due –, done/assigned TODOs), each with state (`OPEN`/`DUE_SOON`/`OVERDUE`/`REACHED`, `REOPENED`), overdue and soon-due (≤ 3 days) active TODOs. Titles flattened, capped and stripped of platform markers (`todo_item:`, `gate_*:`, `dashboard_widget:`) and block delimiters; at most 20 lines per list |
| `{{ goal_guard_status }}` | `RED` (something overdue or the target date passed with work left), `YELLOW` (something due within ≤ 3 days), else `GREEN` |
| `{{ goal_guard_title }}` | `Goal: <title>`, at most 80 characters |
| `{{ goal_guard_summary }}` | One line: `7/12 done (58 %) · 2 overdue · 1 due soon · next milestone “Beta” on 2026-10-01`, at most 500 characters |
| `{{ goal_guard_progress }}` | `7/12 (58 %)` |
| `{{ goal_guard_overdue }}` / `{{ goal_guard_due_soon }}` | Number of overdue / soon-due TODOs, milestones and work packages (based on their end) |
| `{{ goal_guard_item_1 }}` … `{{ goal_guard_item_5 }}` | The most urgent items (overdue milestones, overdue work packages, overdue TODOs, target date, soon-due items in the same order – “Milestone …”, “Work package …”), at most 200 characters each; free slots are empty and drop out in `dashboard-report` |
| `{{ goal_guard_items }}` | All items, one line each |

The tile values carry no control or format characters and keep the dashboard contract's lengths – they
fit a `dashboard-report` block unchanged. That is exactly what the shipped template **Goal guard**
(`goal-guard`, no model) does: a shell block writes `{{ goal_status }}` to the log with
`shell_escape_variables`, a `dashboard-report` block fills the tile. It is created by the "Guard"
switch on the goal page (`POST /api/v1/goals/{id}/guard`), one workflow per goal with a daily schedule.

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

| Gate | Auto-todo priority | Meaning |
|------|-------------------|---------|
| GREEN | — | All checks passed |
| YELLOW | MEDIUM | Warnings, non-blocking problems |
| RED | HIGH | Critical problems (handled via `AutoTodoService`) |

**A gate does not change the run status.** A run that reports RED still ends up `COMPLETED` — the
gate is a finding about the result, not a failure of the execution. The run status comes from
elsewhere: `COMPLETED_WITH_ERRORS` is set when a step with `checked_command` fails
(`RunCompletionHandler`), and `FAILED` when the execution itself breaks off. An earlier version of
this table carried a "run status" column that implied a causality the code does not have.

**Merely mentioning the key orders a verdict.** If `gate_status` (or `gate-status`) appears anywhere
in a block config — even in a prohibition such as "do not use the word gate_status" — the run
expects a verdict (`GateExpectation`). If none arrives, it turns YELLOW `gate-missing` and creates a
follow-up TODO. Word AI prompts that must not write markers without the key ("no status lines") and
set the verdict in the closing shell block. Seen 2026-09-20 in the documentation review.

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

When multiple blocks emit gates, the highest severity wins (RED > YELLOW > GREEN). On a tie, RED and YELLOW keep the first gate – the cause, whose category also names the auto-TODO – while GREEN takes the last one, the closing verdict rather than the `Command exit 0` of a `checked_command` before it – unless it says less: the synthetic `Command exit N` never replaces a description in words of its own, not even after the verdict, and a GREEN without `gate_description` does not replace one that has it. If the later one has no `gate_category`, the current one stays (TODO 2aa9ddd5).

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
curl -s --noproxy '*' -H "X-API-Key: $KEY" "$BASE/executions/$RUN1" | jq '.metadata.gate_summary' > a.json
curl -s --noproxy '*' -H "X-API-Key: $KEY" "$BASE/executions/$RUN2" | jq '.metadata.gate_summary' > b.json
diff <(jq -S . a.json) <(jq -S . b.json)
```

### TODO markers (`todo_item:`)

Besides the gate, every block can propose any number of TODOs, one line each in its output:

```
todo_item: <title> | <CRITICAL|HIGH|MEDIUM|LOW> | <tag1,tag2> | <description, optional>
```

```
todo_item: Test ChainExecutor | HIGH | backend,testing | Execution loop without a test
todo_item: Test the useWebSocket hook | MEDIUM | frontend,testing
```

- The output of **all** steps is read (`TodoOutputParser`); Markdown in front (`#`, `**`, backticks)
  and the `[<item>]: ` prefix of a `foreach` iteration do no harm.
- The TODOs are created only when auto-TODO is on for the workflow — workflow metadata
  `autoTodo.enabled`, otherwise the global setting `auto-todo-default` — and not for audit
  workflows (`audit.enabled`), whose findings the Audit Center manages (`AutoTodoService`).
- The title is the identity: the same title in a later run updates the TODO; what a run with markers
  no longer names, it closes (`GranularTodoService`). An AI similarity search links a new TODO to an
  identical or related open TODO (`relatedTodoId`); it is created all the same.

### Dashboard marker (`dashboard_widget:`)

A workflow can also report its dashboard result as a marker line in a block output. The deliberately configured way is the [`dashboard-report` block](#dashboard-report--report-the-dashboard-result-without-a-marker); once it has written, no marker is evaluated. The JSON may span several lines:

```
dashboard_widget: {"title": "Nightly QA", "status": "YELLOW",
  "kpis": [{"label": "Hunts", "value": "1361", "trend": "UP"}],
  "items": ["3 hunts without route"], "summary": "Two findings."}
```

- Fields and bounds as for the `dashboard-report` block ([block reference](block-referenz.md#dashboard-report)): `status` is required (`GREEN`, `YELLOW`, `RED`), KPI `label` and `value` at most 40 characters each, `value` is a **string**, `trend` `UP`/`DOWN`/`FLAT`. An unknown field – in a KPI object too – rejects the whole contract, as does an empty, too long or not single-line text (control, zero-width, bidi, separator, private-use characters). It is checked at the end of the run and again when read.
- At most 8192 characters are read. If several blocks report a marker, the last one wins; outputs of compression blocks (`derivedOutput`) do not count. **Two markers in the same block output reject that output** (`resultState: INVALID`): a quoted marker must never override the report's own result.
- Write the marker at the start of a line, the JSON right behind it (a code block on the next line works too). The dashboard shows the content as text only – no Markdown, HTML, link or image.
- Reading: `GET /api/v1/dashboard/workflow-results?workflowIds=…` returns per workflow the last run and `resultState` (`VALID`, `INVALID` or `MISSING`).

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

The defaults of the config keys are in the [block reference](block-referenz.md), the constants behind them in `ExecutionDefaults.java`. Without `timeout_seconds`, shell and simple blocks end after 300 s; approval and human input wait 30 minutes, a sub-workflow likewise.

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

**Sandbox does not start:** if Codex' own output reports a sandbox start failure (a line that starts with `bwrap:`, or with `ERROR`/`exec failed` and contains `…: bwrap:`, e.g.
`bwrap: setting up uid map: Permission denied` or, now and then, `bwrap: loopback: Failed RTM_NEWADDR: Operation not
permitted`), the step fails with "Codex sandbox could not start on this host (…)" — even with exit code 0 and even
when the answer consists of nothing but that error line. The provider does not retry the call; a blind answer
without file access is not passed on to the next step. Lines that also appear in the prompt or in the actual
answer (the model writing *about* bwrap) do not count.

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

Rule of thumb: `high`/`xhigh` for analysis/audit blocks, `low` for mechanical transformations. The effort actually used is visible in the step metadata (`aiRequest.effort`) of the run details when the provider applies it (Codex does not). Without a configured effort the CLI's default applies; the CLI does not report it, so `aiRequest.effort` is absent then. Exception: Pi in the `agentic` block always reasons with `--thinking` (without an effort from the maxTurns heuristic or `automation.host-pi.default-thinking-level`); the level used is on the step as `aiRequest.reasonThinking`.

Tokens in the step metadata: for Claude, `inputTokens` is only the part of the input the prompt cache did not serve, often a single digit for a Claude Code call. The rest is in `cacheReadInputTokens` (read from the cache) and `cacheCreationInputTokens` (written to the cache); the run's insights card shows both as "+… cache". Codex and Pi report no real figures (`estimated: true`), so they have no cache keys. The `max_total_tokens` budget of the `agentic` block does not count cache tokens. `thinkingTokens` is the part of `outputTokens` Claude spent on extended thinking (from `output_tokens_details`) — already included in `outputTokens`; the insights card shows it as "incl. … thinking". The workflow insights (`GET /api/v1/learning/workflows/{id}/execution-insights`) sum cache and thinking tokens like the run insights (`totalCacheReadInputTokens`, `totalCacheCreationInputTokens`, `totalThinkingTokens`).

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
- Wrapper blocks (`retry`, `foreach`, `while`, `conditional`, `switch`, `skill-capability`) carry the `aiRequest`, `provider`, and `requestedModel` of their **first inner AI call** into their aggregate step (provider and model are constant per block); the run page shows them as pills. Additional keys: `foreach` → `totalItems` + `items` (`{index, item, status, durationMs}`, plus `subRunId` with `workflow_id`; max. 500, item label truncated to 120 characters), `while` → `iterationCount` + `iterations`, `retry` → `attempts` + `maxAttempts`, `conditional` → `conditionMet`, `switch` → `expression` + `matchedCase`. Pure shell iterations carry no `aiRequest`.

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
| `automation-agent-node:latest` | Node.js 22 | npm (full ecosystem), git, curl, jq |
| `automation-agent-java:latest` | Temurin 21 | maven, git, curl, jq, python3, LibreOffice (DOCX→PDF) |

### Rules

- Shell blocks WITHOUT `container_image` can ONLY use host tools
- If a command needs pip, go, docker, gh, or other specialized tools -> set `container_image`
- Containers mount the project directory to `/workspace`
- `conditional`, `retry`, `foreach`, `while` and `switch` in shell mode also pass `container_image`, `mount_docker_socket` and `mounts` on to their command. The condition itself (`condition` or `condition_command`) runs on the host
- `container_image` only applies to shell commands: AI calls (Claude, Codex, Pi) always run on the host in the project directory
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

If the AI CLI ends the REASON phase with a non-zero exit code (limit, missing login, crash), the block fails right away: the output shows `[iteration N - REASON ERROR]` with the CLI's message. The message is never executed as an ACTION.

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

Iterations (`max_iterations`), time per iteration and in total, token budget (`max_total_tokens`), abort after repeated identical actions (`max_repeated_actions`) and an approval before every action (`require_approval`) – defaults in the [block reference](block-referenz.md#agentic).

### Action Compression

When an ACTION exceeds 100,000 characters, it is compressed via a separate Claude CLI call (60s timeout) before being sent to the ACT phase. If that call fails (timeout, non-zero exit code), the ACTION is cut to 100,000 characters instead.

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

1. **Extract** (manually or automatically): After one or more runs, trigger extraction via UI ("Memory" tab on the workflow detail page) or API. Select runs and choose a provider (Claude or Codex). The `ConversationPreprocessor` condenses the step outputs before they go to the AI; the AI turns them into a structured knowledge entry (title, Markdown content, tags).
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
- **Chain link**: Each entry knows its chain run: `{{ chain_run_id }}` in the context, `chainRunId` on the run (`GET /api/v1/executions/{id}`). The chain sets both itself, after its input context — a `chain_run_id` passed in does not override it. Started on its own, the placeholder stays literally (analyzer rule `chain-only-placeholder`). Only the run the chain starts carries `chainRunId`, also after a resume; a retry, a resume without a completed step (which starts a new run) and sub-workflows inherit `{{ chain_run_id }}` with the context, their `chainRunId` stays `null`
- **stopOnFailure**: Controls whether the chain stops on workflow failure — stops only when **both** flags are set (chain-level AND entry-level must be `true`)
- **Wait time per entry**: `timeoutMinutes` (5–1440) on an entry limits how long the chain waits for that workflow; without it `chain-workflow-timeout-minutes` applies (default 180). Needed for entries with an approval that waits overnight. When the limit runs out, the entry counts as FAILED – the workflow run itself keeps going

### Chain Structure

```json
{
  "name": "Complete Audit + Fix Pipeline",
  "entries": [
    {
      "workflowId": "<audit-workflow-id>",
      "sortOrder": 0,
      "inputContext": { "scope": "backend" },
      "stopOnFailure": true
    },
    {
      "workflowId": "<fix-workflow-id>",
      "sortOrder": 1,
      "inputContext": { "scope": "fix" },
      "stopOnFailure": false,
      "timeoutMinutes": 600
    }
  ]
}
```

Values in `inputContext` are not resolved – `{{ previous_chain_output }}` belongs in the blocks of the workflow itself.

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

**Module:** `integrations` (on only in the `full` preset). While it is off, the block is refused, the editor does not offer the type, and the template picker hides templates with this block.

### Config

`provider` (provider id such as `google-calendar` or `trello`), `action` (e.g. `list_events`, `create_card`), `params` (placeholders allowed) and optionally `connection` – the named connection when several accounts or services of the same provider are set up (default `default`). All keys: [block reference](block-referenz.md#integration).

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

All built-in templates with their primary pattern. The SaaS factory workflows (`saas-*`) live in the pool (scope `external`) because they need a tool from outside the repository:

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
| `folder-backup` | Shell Chain | UTILITY |
| `github-project-bootstrap` | Shell Chain | CODE |
| `dependency-update-check` | Shell + Conditional | CODE |
| `log-rotate-cleanup` | Shell Collect + AI Analyze | INFRA |
| `pool-neutrality-audit` | Audit Pipeline | QUALITY |
| `pr-review-summary` | Shell Collect + AI Analyze | TEAM |
| `research-enhance` | Sequential Simple Chain | RESEARCH |
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

### Bilingual templates

A template's name and description are always `{"de": …, "en": …}`. A template or pool file with the marker `"i18n": ["de", "en"]` may use the same form **inside its steps** too, in `blocks` and `instructions`. When a workflow is created, the platform picks one language: the UI language, falling back to English, then German. The new workflow is single-language from then on and yours to change.

```json
{
  "i18n": ["de", "en"],
  "name": {"de": "Beispiel: Freigabe mit Messung", "en": "Example: approval with a measurement"},
  "blocks": [
    {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
    {"type": "approval", "name": {"de": "freigabe", "en": "approval"},
     "config": {"question": {"de": "{{ block.messen.output }} – weiter?", "en": "{{ block.measure.output }} – continue?"},
                "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}], "on_no": "fail"}}
  ]
}
```

**Rules** (enforced by the gate `LocalizedDefinitionGateTest`):

- **Every map carries exactly the declared languages, none left blank.** Without the marker nothing is resolved.
- **These stay single-language:** shell fields (`command`, `condition` …; otherwise two commands would need review) **and everything that is compared or evaluated:** `type`, `variable_name`, `auto_answer`, `reject_options`, `mode`, `on_no`, `execution_mode`, `model`, `provider`, and `metadata`.
- **Step names can be translated but must stay consistent within each language.** `{{ block.X.output }}` and compress `source` must point to an existing name in every variant. An audit's report block (`metadata.audit.reportBlock`) stays single-language. A block referenced from a **shell field** gets a language-neutral name (e.g. `data`), because the shell field itself stays single-language.
- **Translated approval `options` must not be compared anywhere.** The answer variable must not appear in any shell, conditional, switch or while field, because in German the answer would be `Nein` instead of `No`. The approval block recognises the rejection itself (`reject_options`, default `No`/`Nein`).
- **Existing databases get a changed built-in template only through a version bump** (`version`), like any change to a built-in template.

The API returns the steps in the requested language: `GET /api/v1/workflow-templates?locale=de`. Pool and chain installs take `"locale"` in the body.

### Blueprints

Besides **templates** (finished tasks) and the **pool** there are **blueprints**: empty scaffolds with runnable placeholders that show how block types connect – form → branch, loop with approval, fallback on errors. They are the first tab of the create dialog (before templates and pool), grouped into *Basic connections*, *Combinations*, *Around workflows* and *With AI*; the blank workflow sits on top. You run a blueprint, see in the run how the steps work together, then replace the placeholders with your own commands.

- **Location:** `src/main/resources/templates/blueprints/*.json`, same format and loader as templates, `kind: BLUEPRINT` (column `workflow_templates.kind`). The API returns both kinds together with the field `kind`.
- **Always bilingual** (`"i18n": ["de","en"]`), `id` prefixed `blueprint-`, category `BASICS`, `COMBINATIONS`, `AROUND` or `AI`.
- **Placeholders:** shell steps do something harmless and carry a comment `# placeholder - …`. Scratch files live in `/tmp/mysuricates-blueprints/<run id>`, the last step removes them.
- **0 tokens outside `AI`:** no AI block types; `foreach`, `while`, `conditional` and `retry` need `"execution_mode": "shell"`, otherwise they ask the AI. `BlueprintGateTest` checks this.
- **Packages (sub-workflow):** a blueprint's `sub-workflow` block names the called blueprint instead of a workflow id (`"workflow_id": "blueprint-sub-workflow-part"`). On creation the server creates both in the same project and fills in the new id (`POST /api/v1/workflow-templates/{id}/install`, like the chain installation). The reference sits on the top level only and names a blueprint that calls no further one; outside `AI` the part is model-free too – `BlueprintGateTest` checks both. Only inside a blueprint does such a call therefore not count as AI (`ModelUse.needsAiAsBlueprint`; AI badge only for a shipped part blueprint). A workflow that still carries `blueprint-…` as `workflow_id` is refused on create and update with 400 `UNWIRED_BLUEPRINT_REFERENCE`.
- **Around workflows** (`AROUND`, 0 tokens): one blueprint each for what is set up outside the blocks; the how-to sits briefly in its description and in the steps' explanations. *Starting on a schedule* (CRON, `maxRuns`, conflict behaviour `SKIP`), *Starting via webhook (signed)* (body fields as variables, `X-Signature`), *Started by a TODO trigger* (`todo_*` variables, GREEN closes via `autoClose`), *Link in a chain* (`{{ previous_chain_output }}`; the chain template `blueprint-chain` “Chain of two blueprints” puts *Traffic light* and this link in a row), *Using workflow memory*, *Querying an integration* (Open-Meteo, no credentials) and *Running isolated in a clone* (`workspace.mode: isolated`). The hints on the schedules, webhooks and integrations pages link the matching blueprint (`/workflows?new=1&template=<id>` opens the create dialog with it).
- **With AI** (`AI`): besides *Collect → AI summarizes* also *Model per step* (`provider`/`model`/`effort` on the block), *AI condenses a long output* (`compress-ai`), *Using a skill* (`skill-capability` with `skillIds`) and *Agent with approval (expensive)* – an approval before the start, then `agentic` with `require_approval` and a small budget (3 rounds, 40,000 tokens).
- **Explanation per step:** `config.description` (bilingual) says what the step does in the pattern and what to replace. The editor shows it under the block, the run view under the step header (`metadata.blockDescription`). This works in every workflow, since `description` is a common key with no effect on execution. The exceptions are `todo-create` and `todo-update`, where `description` is the TODO's text.

---

## Resume automatically after a restart or standby

A backend restart or a system standby interrupts running runs; they end up `INTERRUPTED`. Without the opt-in they only continue via the "Resume from Step" button. With it, the platform resumes the run by itself (TODO 90d10ba1):

```json
{ "metadata": { "autoResume": { "enabled": true } } }
```

In the UI: workflow detail page → "Resume automatically after a restart". The key is part of the definition — it changes the definition hash (a new version), is locked on a sealed workflow, and a workflow without it keeps its previous hash.

**What is resumed** — only runs that the restart cleanup or the standby watchdog marked `INTERRUPTED` in this backend lifetime **and that were `RUNNING` before**. Not resumed: paused runs (a person pauses), chain entries and sub-workflows (their parent run decides — if it resumes, the sub-workflow block runs again), runs of deleted workflows and older `INTERRUPTED` runs. Every run gets **at most one** automatic attempt (`autoResumeAttempts` in the run metadata, counted before the start); if it is interrupted again, it waits for a person.

**Side effects** — The run continues after its last finished block; **the interrupted block runs again from the start**. Blocks with external effects (appending to files, sending messages, deploying) must tolerate a repeat. If the run was waiting for an approval (`approval`) or input (`human-input`), that block had not finished: the question is asked again and the run waits again — the unattended rules do not answer it (a block's `auto_answer` applies as always). Without any finished block — as with the button — a new run of the same workflow starts. That new run takes the counter over (`autoResumeAttempts`, origin in `autoResumedFrom`) and is not resumed automatically again if it is interrupted too; the old run stays `INTERRUPTED` and names the new one in `autoRestartedAs`.

**Unattended** — The run then carries `trigger_source = AUTO_RESUME`. With `automation.hardening.require-sealed-for-unattended` the workflow needs a seal; otherwise `RunAdmission` refuses, the run stays `INTERRUPTED`, the activity log gets a `SECURITY` entry, and there is no second attempt.

**Global** — Settings → Execution: "Resume interrupted runs automatically" (`run-auto-resume-enabled`, default on) and "Wait before each resume" (`run-auto-resume-delay-seconds`, default 30 s, 0–3600). Runs are resumed one after another with this wait, also before the first. Startup values: `automation.execution.auto-resume.enabled` / `.delay-seconds`.

**Together with a schedule** — If the workflow has a schedule with conflict behaviour `SKIP` (the default), the resume wins: while an automatic resume of the workflow is pending or running, a due occurrence is dropped — a catch-up run (`catchUpOnResume`) after a restart or standby as well. The activity log notes "Schedule skipped (interrupted run is being resumed automatically)" with `reason: auto-resume-pending`. With `OVERLAP` the occurrence starts next to it as usual (TODO 070ccc3d).

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

1. **Metadata flag**: A fix run started by the TodoTrigger carries `todoTriggerRun` in its run metadata; `AutoTodoService` creates no TODO for such runs — this cuts the recursion TODO → run → TODO
2. **Max attempts**: `todo.fixAttempts` counts the attempts; after `maxAttempts` (default 3) this TODO is not triggered again
3. **Running check**: While the last fix run (`todo.lastFixRunId`) is still running, no second one starts

In addition, but not part of the loop brake: the pause window `automation.todo-trigger.skip-until`, one candidate per poll tick, and the trigger's source, tag, project and priority filters (`sourceFilter: "WORKFLOW"` excludes manually created TODOs, for example).

### Auto-Close

With `autoClose: true` (default): When the fix workflow completes with a **GREEN** gate status, the TODO is automatically set to DONE. With YELLOW or RED, it remains open.

### Best Practices

- **Keep tag patterns specific** — `"backend,security,bug"` instead of just `"bug"`, otherwise the fix workflow triggers on every bug
- **Design fix workflows to be idempotent** — they may run multiple times
- **Set gate status in fix workflows** — so auto-close works correctly
- **Limit maxAttempts** — Default 3 is reasonable, higher values risk infinite loops with systematic failures
