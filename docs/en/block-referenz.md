<!-- Erzeugt von scripts/docs-reference.py – nicht von Hand ändern -->

# Block Reference

> **Scope:** Every config key of every block type, generated from “Common fields” on from
> `ai-automation-frontend/src/data/blockConfigSchema.json` (`python3 scripts/docs-reference.py --write`,
> see [Development → Generated references](entwicklung.md#generated-references)). The block editor takes
> its suggestions and the analyzer its checks from the same file. What a type is good for, an example
> and the pitfalls are in the [Workflow Guide, chapter 2](WORKFLOW-GUIDE.md#2-block-type-reference).

A block is `{ "type": …, "name": …, "config": { … } }`; the tables describe the keys in `config`.
**Required** “yes” means it must be set; “one of”: exactly one of these keys, an empty text counts as
not set. **Values** lists the allowed values with their meaning, plus lower bounds (“≥ 1”). “Read only
with …” means the executor reads the key only when the other keys named are set that way. The backend
ignores unknown keys; editor and analyzer report them.

## Common fields

Apply to every block type.

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `on_failure` | string |  | `stop` | `stop`: Stop the workflow, the run becomes FAILED (default). · `continue`: Carry on with the next block. The block itself stays FAILED. | What happens when this block fails (status FAILED). Any value other than `continue` stops like `stop`. |
| `label` | string |  |  |  | Display label of the step, description only. No effect on execution. |
| `description` | string |  |  |  | Note on the step, description only. No effect on execution. |
| `run_if` | string |  |  |  | Shell test before the block (top level only): exit 0 → the block runs, otherwise it is skipped (SKIPPED, no AI call). Runs in the run working directory, at most 60 s; placeholders as in any shell field. |
| `shell_escape_variables` | boolean |  | `false` |  | Put every `{{ }}` placeholder of the `run_if` condition in POSIX-safe single quotes. On blocks without a shell of their own the key only takes effect together with `run_if`. Read only with `run_if`. |
| `workspace` | object |  |  |  | Working directory of the run — only honoured on the **first** block. `{"mode": "isolated"}` runs it in its own Git clone (more keys: `source`, `source_path`, `branch`, `install_npm`, `additional_sources`). |
| `validation` | object |  |  |  | Checks the output of the block after it succeeded: `contains` (substring), `min_length`, `max_length`. With `retry_on_fail: true` the block runs again up to `max_retries` times on a violation (default 1), otherwise the violation is only appended to the output. |

## AI fields

Apply in addition to `simple`, `agentic`, `skill-capability`, `retry`, `foreach`, `while`, `conditional`, `switch`, `compress-ai`.

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `provider` | string |  |  | `CLAUDE`: Claude CLI · `PI`: pi CLI (model as `<backend>/<model>`) · `CODEX`: Codex CLI (read-only) · case-insensitive | AI provider for this block only. Precedence: block > workflow > system default. |
| `model` | string |  |  |  | Model for this block only: alias `opus`/`sonnet`/`haiku`/`fable`, a concrete id, or `<backend>/<model>` for pi. If the block sets a different `provider` than the workflow, the workflow model is not inherited. |
| `effort` | string |  |  | `low`: Little reasoning, fast and cheap. · `medium`: Medium reasoning. · `high`: High reasoning. · `xhigh`: Very high reasoning, most expensive. · case-insensitive | Reasoning effort for this block only. Unset ⇒ workflow or provider default. Codex ignores it. |

## `simple`

Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `command` | string | yes |  |  | The prompt sent to the AI. Placeholders like `{{ previous_output }}` are resolved. |
| `timeout_seconds` | integer |  |  | ≥ 1 | Timeout in seconds for the AI call. Unset ⇒ `automation.host-claude.timeout-seconds` applies. |

## `shell`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `command` | string | one of `command`, `container_args` |  |  | Shell command(s). Tip: start with `set -euo pipefail`. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in seconds (default 300). |
| `shell_escape_variables` | boolean |  | `false` |  | Put every `{{ }}` placeholder in POSIX-safe single quotes. Then write placeholders **without** your own quotes. |
| `container_image` | string |  |  |  | Docker image to run the command in. Unset ⇒ runs on the host. |
| `mount_docker_socket` | boolean |  | `false` |  | Mount the Docker socket into the container (only with `container_image`). |
| `mounts` | string[] |  |  |  | Extra volumes `host:container[:ro\|rw]` (only with `container_image`). |
| `container_args` | string[] | one of `command`, `container_args` |  |  | Tool mode instead of `command`: arguments for the image's own entrypoint, no shell. The project is mounted read-only at /workspace. Untrusted placeholders (webhook input, platform data) are refused. Read only with `container_image`. |
| `output_format` | string |  | `raw` | `raw`: The tool's output, the exit code decides (default). · `sarif`: Fact sheet from the SARIF files in the output directory (`FAKT`/`TREFFER`, otherwise `NICHT GEMESSEN`). | What counts as the step's output. Read only with `container_args`. |
| `output_dir` | string |  | `/out` |  | Path in the container where the platform mounts an empty output directory per step; the tool writes its SARIF file there. Read only with `output_format: sarif`. |
| `cache_dir` | string |  |  |  | Path in the container for a persistent cache per image (such as a vulnerability database). Read only with `container_args`. |
| `fact_prefix` | string |  |  |  | Prefix of the FAKT/TREFFER names; defaults to the block name. Read only with `output_format: sarif`. |
| `checked_command` | boolean |  | `false` |  | Derives a gate (`gate_status`) from the exit code and prepends `set -e -o pipefail`. |
| `check_category` | string |  | `build-check` |  | Gate category for `checked_command` if the script prints no `gate_category` itself. |

## `agentic`

Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `goal` | string | yes |  |  | What the agent should achieve. |
| `success_criteria` | string |  |  |  | How the agent recognises that it is done. |
| `max_iterations` | integer |  | `10` | ≥ 1 | Hard cap on reason/act rounds (default 10). |
| `iteration_timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout per round in seconds (default 300). |
| `total_timeout_seconds` | integer |  | `1800` | ≥ 1 | Timeout for the whole block in seconds (default 1800). |
| `max_total_tokens` | integer |  | `200000` | ≥ 1 | Token budget across all rounds: uncached input + output, cache tokens do not count (default 200,000). |
| `max_repeated_actions` | integer |  | `3` | ≥ 1 | Stop when the same action repeats this many times. |
| `require_approval` | boolean |  | `false` |  | Ask for approval before every action. |
| `timeout_seconds` | integer |  |  | ≥ 1 | Timeout in seconds for the AI call. Unset ⇒ `automation.host-claude.timeout-seconds` applies. |

## `retry`

Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `command` | string | yes |  |  | Command retried on failure (prompt or shell, depending on `execution_mode`). |
| `max_retries` | integer |  | `3` | ≥ 0 | Maximum number of retries. |
| `delay` | integer |  | `5` | ≥ 0 | Pause between attempts in seconds. |
| `exponential_backoff` | boolean |  | `false` |  | Double the pause on every attempt. |
| `execution_mode` | string |  | `ai` | `ai`: As an AI prompt via the provider from `provider` (default). · `shell`: As a shell command. No AI call, no tokens. · legacy, still accepted: `claude` | How condition and command run: as an AI prompt or as a shell command. |
| `shell_escape_variables` | boolean |  | `false` |  | Put every `{{ }}` placeholder in POSIX-safe single quotes. Then write placeholders **without** your own quotes. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in seconds (default 300). |
| `container_image` | string |  |  |  | Docker image the command runs in in shell mode (`execution_mode: shell`). Unset ⇒ runs on the host. Read only with `execution_mode: shell`. |
| `mount_docker_socket` | boolean |  | `false` |  | Mount the Docker socket into the container (shell mode with `container_image` only). Read only with `execution_mode: shell`. |
| `mounts` | string[] |  |  |  | Extra volumes `host:container[:ro\|rw]` (shell mode with `container_image` only). Read only with `execution_mode: shell`. |

## `conditional`

Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `condition` | string | yes |  |  | Condition. Shell: exit code 0 = true. AI: answer TRUE/FALSE. |
| `then_command` | string |  |  |  | Command when the condition is true. |
| `else_command` | string |  |  |  | Command when the condition is false. |
| `execution_mode` | string |  | `ai` | `ai`: As an AI prompt via the provider from `provider` (default). · `shell`: As a shell command. No AI call, no tokens. · legacy, still accepted: `claude` | How condition and command run: as an AI prompt or as a shell command. |
| `shell_escape_variables` | boolean |  | `false` |  | Put every `{{ }}` placeholder in POSIX-safe single quotes. Then write placeholders **without** your own quotes. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in seconds (default 300). |
| `container_image` | string |  |  |  | Docker image the command runs in in shell mode (`execution_mode: shell`). Unset ⇒ runs on the host. Read only with `execution_mode: shell`. |
| `mount_docker_socket` | boolean |  | `false` |  | Mount the Docker socket into the container (shell mode with `container_image` only). Read only with `execution_mode: shell`. |
| `mounts` | string[] |  |  |  | Extra volumes `host:container[:ro\|rw]` (shell mode with `container_image` only). Read only with `execution_mode: shell`. |

## `switch`

Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `expression` | string | yes |  |  | Expression whose value is matched against the cases (`${var}` or `{{ var }}`). |
| `cases` | array | yes |  |  | Cases with `value` (alternatives with `\|`, e.g. `"YELLOW\|RED"`) and `blocks`. |
| `default` | array |  |  |  | Blocks when no case matches. |

## `foreach`

Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `command` | string | one of `command`, `workflow_id` |  |  | Command per item. The item is available as `{{ <variable_name> }}`, its index as `{{ <variable_name>_index }}`, and for a JSON object its fields as `{{ <variable_name>.<field> }}`. Either `command` or `workflow_id`. |
| `workflow_id` | string | one of `command`, `workflow_id` |  |  | Instead of `command`: one run of this workflow per item, as with the sub-workflow block (its own run per item, max. 5 levels, no cycles). The child run gets this run's variables including the item. A placeholder such as `{{ feature_workflow_id }}` is allowed, but only from trusted context variables. |
| `context` | object |  |  |  | Only with `workflow_id`: extra variables for the child run per item, name → template, e.g. `{"feature_id": "{{ feature.id }}"}`. Read only with `workflow_id`. |
| `variable_name` | string |  | `item` |  | Name of the loop variable (default `item`). |
| `items_source` | string |  | `list` | `list`: From `items` (default). · `command`: From the output of `items_command`, one item per line. | Where the items come from. |
| `items_command` | string |  |  |  | Shell command printing the items (one line = one item). Always runs as shell. |
| `items` | array |  |  |  | Fixed list of items. |
| `execution_mode` | string |  | `ai` | `ai`: As an AI prompt via the provider from `provider` (default). · `shell`: As a shell command. No AI call, no tokens. · legacy, still accepted: `claude` | How condition and command run: as an AI prompt or as a shell command. |
| `shell_escape_variables` | boolean |  | `false` |  | Put every `{{ }}` placeholder in POSIX-safe single quotes. Then write placeholders **without** your own quotes. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in seconds (default 300). |
| `continue_on_error` | boolean |  | `false` |  | Failed items do not fail the block, it ends COMPLETED. |
| `max_consecutive_failures` | integer |  | `3` | ≥ 0 | After this many failed items in a row the loop stops, the remaining items do not run and the block fails, even with `continue_on_error` (default 3, 0 = off). |
| `container_image` | string |  |  |  | Docker image the command runs in in shell mode (`execution_mode: shell`). Unset ⇒ runs on the host. Read only with `execution_mode: shell`. |
| `mount_docker_socket` | boolean |  | `false` |  | Mount the Docker socket into the container (shell mode with `container_image` only). Read only with `execution_mode: shell`. |
| `mounts` | string[] |  |  |  | Extra volumes `host:container[:ro\|rw]` (shell mode with `container_image` only). Read only with `execution_mode: shell`. |

## `while`

Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `command` | string | yes |  |  | Command per iteration. |
| `condition_command` | string |  |  |  | Condition before every iteration. Shell: exit 0 = continue. AI: "false"/"0"/empty = stop. |
| `max_iterations` | integer |  | `100` | ≥ 1 | Maximum number of iterations. |
| `execution_mode` | string |  | `ai` | `ai`: As an AI prompt via the provider from `provider` (default). · `shell`: As a shell command. No AI call, no tokens. · legacy, still accepted: `claude` | How condition and command run: as an AI prompt or as a shell command. |
| `shell_escape_variables` | boolean |  | `false` |  | Put every `{{ }}` placeholder in POSIX-safe single quotes. Then write placeholders **without** your own quotes. |
| `timeout_seconds` | integer |  | `300` | ≥ 1 | Timeout in seconds (default 300). |
| `container_image` | string |  |  |  | Docker image the command runs in in shell mode (`execution_mode: shell`). Unset ⇒ runs on the host. Read only with `execution_mode: shell`. |
| `mount_docker_socket` | boolean |  | `false` |  | Mount the Docker socket into the container (shell mode with `container_image` only). Read only with `execution_mode: shell`. |
| `mounts` | string[] |  |  |  | Extra volumes `host:container[:ro\|rw]` (shell mode with `container_image` only). Read only with `execution_mode: shell`. |

## `approval`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `question` | string |  | `Approval required` |  | Approval question (Markdown, placeholders are resolved). |
| `options` | string[] |  | `["Yes", "Yes always", "No"]` |  | Allowed answers. |
| `reject_options` | string[] |  | `["No", "Nein"]` |  | Answers that count as rejection and trigger `on_no`. |
| `variable_name` | string |  | `approval_answer` |  | Context variable that receives the result (default `approval_answer`). Usable afterwards as `{{ name }}`. |
| `timeout_minutes` | integer |  | `30` | ≥ 1 | Waiting time in minutes. |
| `mode` | string |  | `ask` | `ask`: Waits for an answer in the UI (default). · `auto`: Answers immediately with `auto_answer`. | Ask interactively or answer automatically. |
| `auto_answer` | string \| null |  |  |  | Answer in mode `auto`. |
| `on_no` | string |  | `continue` | `continue`: Continue, block COMPLETED (default). · `fail`: Block FAILED. · `skip`: Block SKIPPED. | Behaviour on rejection (see `reject_options`). |

## `human-input`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `prompt` | string |  | `Please provide input:` |  | Question to the user. |
| `input_type` | string |  | `text` | `text`: Free text (default). · `textarea`: Multi-line text. · `number`: Number field. · `select`: Choice from `options`. · `checkbox`: Checkbox, answer `"true"` or `"false"`. · `date`: Date, answer as `YYYY-MM-DD`. | Kind of input. |
| `variable_name` | string |  | `user_input` |  | Context variable that receives the result (default `user_input`). Usable afterwards as `{{ name }}`. |
| `options` | string[] |  |  |  | Choices for `select`. |
| `default_value` | string |  |  |  | Default value, e.g. on timeout. |
| `required` | boolean |  | `true` |  | Input required. |
| `validation_pattern` | string |  |  |  | Regex the input is validated against. |
| `timeout_minutes` | integer |  | `30` | ≥ 1 | Waiting time in minutes. |
| `use_default_on_timeout` | boolean |  | `true` |  | On timeout use `default_value` instead of failing. |
| `fields` | array |  |  |  | Form with several fields (`variable_name`, `label`, `input_type`, `required`, …). |

## `sub-workflow`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `workflow_id` | string | yes |  |  | ID of the workflow to run as a step (max. 5 levels deep). A placeholder such as `{{ child_workflow_id }}` is allowed, but only from trusted context variables – not from webhook data or block outputs. |
| `workflowId` | string |  |  |  | Old spelling of `workflow_id`: still read, will be dropped later. Use `workflow_id` instead. |

## `skill-capability`

Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `capability` | string | yes |  |  | Name of the skill capability, e.g. `review_code`. Its skill must be in the block's `skillIds` (next to `config`); workflow skills do not count here. |
| `input` | string |  |  |  | Input for the capability, e.g. `{{ previous_output }}`. |
| `params` | object |  |  |  | Additional parameters. |
| `allow_writes` | boolean |  | `false` |  | Allow writing to the repo. Required for capabilities with `writes: true`. |
| `timeout_seconds` | integer |  |  | ≥ 1 | Timeout in seconds for the AI call. Unset ⇒ `automation.host-claude.timeout-seconds` applies. |

## `integration`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `provider` | string | yes |  |  | Integration provider, e.g. `google-calendar` (not the AI provider). |
| `action` | string | yes |  |  | Action of the provider. |
| `params` | object |  |  |  | Parameters of the action. |
| `connection` | string |  | `default` |  | Named connection of the provider, e.g. `work` — when several accounts or services of the same provider are set up (Integrations page). Without it, `default` is used. |

## `document`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `file_path` | string | yes |  |  | File (PDF, DOCX, XLSX, HTML, TXT, …), absolute or relative to the project. |
| `variable_name` | string |  |  |  | Context variable that receives the result. Usable afterwards as `{{ name }}`. |
| `fail_on_empty` | boolean |  | `false` |  | Fail if less than `min_characters` of text comes out. |
| `min_characters` | integer |  | `1` | ≥ 0 | Threshold for `fail_on_empty`. |

## `todo-create`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `title` | string | yes |  |  | Title of the TODO. |
| `description` | string |  |  |  | Description (Markdown). |
| `priority` | string |  | `MEDIUM` | `CRITICAL`: Critical · `HIGH`: High, blocking · `MEDIUM`: Medium (default) · `LOW`: Low · case-insensitive | Priority. |
| `tags` | array \| string |  |  |  | Tags as a list or comma-separated. |
| `project_id` | string |  |  |  | Target project. Default: the workflow project. |
| `source` | string |  | `WORKFLOW` | `WORKFLOW`: Workflow (default) · `AI`: AI · `USER`: User · case-insensitive | Origin of the TODO. |
| `source_agent` | string |  | `workflow` |  | Label of the creating agent. |
| `variable_name` | string |  | `todo_id` |  | Context variable that receives the result (default `todo_id`). Usable afterwards as `{{ name }}`. |

## `todo-update`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `id` | string | one of `id`, `updates` |  |  | ID of the TODO, e.g. `{{ todo_id }}`. Either `id` or `updates`. |
| `title` | string |  |  |  | New title (only set, non-empty fields are changed). |
| `description` | string |  |  |  | New description. |
| `priority` | string |  |  | `CRITICAL`: Critical · `HIGH`: High · `MEDIUM`: Medium · `LOW`: Low · case-insensitive | New priority. |
| `tags` | array \| string |  |  |  | New tags. |
| `updates` | array \| string | one of `id`, `updates` |  |  | Many TODOs of the run project at once: a JSON list, usually `{{ block.<shell>.output }}` (if the whole output is no JSON, its last line counts). Per entry `id`, optional `status` (OPEN/IN_PROGRESS/DONE/BACKLOG), `add_tags`, `remove_tags`, `note`, `require_tags`, `require_status`. At most 50; an entry that does not fit is skipped with a reason. |

## `todo-close`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `id` | string | yes |  |  | ID of the TODO set to DONE, e.g. `{{ todo_id }}`. |

## `compress`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `source` | string | yes |  |  | Name of an earlier block or `"previous"`. |
| `stages` | array | yes |  |  | Stages run in order. JSON stages before text stages. |
| `max_chars` | integer |  | `20000` | ≥ 1 | Hard limit including header lines. |
| `timeout_seconds` | integer |  | `30` | ≥ 1 | Timeout in seconds (default 30). |

### Stages

| Stage | Effect |
|---|---|
| `text.normalize` | Strip ANSI, CR, trailing whitespace and blank-line runs. |
| `text.truncate` | Cut at line boundaries: `max_chars`, `keep` (`head`/`tail`/`head_tail`). |
| `lines.filter` | Keep/remove lines: `include`, `exclude` (regex lists), `before`, `after`. |
| `lines.replace` | Regex replacement per line: `pattern`, `replacement`. |
| `lines.dedupe` | Remove repeats: `count`, `consecutive_only`. |
| `lines.head` | First `lines` lines. |
| `lines.tail` | Last `lines` lines. |
| `markdown.section` | Only matching sections: `headings` (regex), `level`. |
| `json.select` | Only these fields: `paths`. |
| `json.limit` | Shorten an array: `path`, `count`. |
| `json.sort` | Sort an array: `path`, `by`, `order`. |
| `json.to_lines` | One entry per line: `path`, `template` (`{title} — {url}`). |

## `compress-ai`

Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `source` | string | yes |  |  | Name of an earlier block or `"previous"`. |
| `instruction` | string |  |  |  | Compression instruction (placeholders are **not** resolved). |
| `max_chars` | integer |  | `4000` | ≥ 500 | Target size, at least 500. |
| `timeout_seconds` | integer |  |  | ≥ 1 | Timeout in seconds for the AI call. Unset ⇒ `automation.host-claude.timeout-seconds` applies. |

## `dashboard-report`

Plus the [common fields](#common-fields).

| Key | Type | Required | Default | Values | Meaning |
|---|---|---|---|---|---|
| `status` | string | yes |  | `GREEN`: Green · `YELLOW`: Yellow · `RED`: Red | Traffic light of the run (placeholders allowed, e.g. `{{ block.<name>.output }}` of a block printing GREEN, YELLOW or RED). |
| `title` | string |  |  |  | Title, at most 80 characters. |
| `summary` | string |  |  |  | Summary, at most 500 characters. |
| `kpis` | array |  |  |  | Up to 6 KPIs `{ label, value, trend? }`. |
| `items` | string[] |  |  |  | Up to 10 entries, each at most 200 characters. |
