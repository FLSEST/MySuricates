package com.automation.service.assistant.fetch;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The block type specifications the assistant fetches with {@code <fetch type="block-spec" id="…"/>}
 * and the prompts that build whole workflows render with {@link #reference}: required config,
 * options and an example per type. One entry per type of
 * {@code BlockTypesIndexSection.BLOCK_TYPES}; {@code BlockTypeListsGuardTest} fails the build when a
 * type has none (TODO 321b414e: four types answered "Unknown block type").
 *
 * <p>The executor is the source of truth, not the guide: about ten older texts named keys no
 * executor reads (TODO c4b81017), and the assistant built configs from them.
 * {@code BlockSpecConfigKeysGateTest} checks every key a text names against the executor source,
 * so write
 * <ul>
 *   <li>config keys in backticks ({@code `command`}) — "Required config:" and "Optional:" start with one,</li>
 *   <li>values in double quotes ({@code "shell"}) and variables as {@code {{ placeholders }}},</li>
 *   <li>exactly one {@code Example config:} line holding a complete JSON object.</li>
 * </ul>
 */
public final class BlockSpecs {

    private static final String AI_OVERRIDES_LINE = "AI overrides:";

    /** provider/model/effort — {@code BlockAiSettings}, for every block that calls an AI. */
    private static final String AI_OVERRIDES = """
            AI overrides: `provider` ("CLAUDE" | "PI" | "CODEX"), `model` (alias such as "sonnet" or a concrete id), \
            `effort` ("low" | "medium" | "high" | "xhigh"); block > workflow > system default.
            """;

    private static final Map<String, String> SPECS = Map.ofEntries(
            Map.entry("simple", """
                    **Block type: simple** — One AI prompt, one response. No shell.
                    Required config: `command` (string) — the prompt; placeholders such as {{ previous_output }} are resolved.
                    Optional: `timeout_seconds` (int; default: setting automation.host-claude.timeout-seconds).
                    """ + AI_OVERRIDES + """
                    Example config: {"command": "List the three riskiest changes in this diff: {{ previous_output }}"}
                    """),
            Map.entry("shell", """
                    **Block type: shell** — Runs a shell command on the host, or in a Docker container with \
                    `container_image`. 0 tokens. Never call the MySuricates REST API or read its database from here.
                    Required config: `command` (string) — bash; start with set -euo pipefail.
                    Optional: `timeout_seconds` (int, default 300), `shell_escape_variables` (bool — single-quotes every \
                    placeholder, then write placeholders unquoted), `checked_command` (bool — derives the gate from the exit \
                    code and prepends set -e -o pipefail), `check_category` (default "build-check"), `container_image` \
                    (string), `mount_docker_socket` (bool), `mounts` (list of "host:container[:ro|rw]"; the last two only \
                    with `container_image`).
                    Example config: {"command": "set -euo pipefail; git log --oneline -20", "timeout_seconds": 60}
                    Note: placeholders carrying outside input ({{ previous_report }}, {{ open_todos }}, webhook values) are \
                    always single-quoted — never put a placeholder in your own quotes. An earlier output goes in as \
                    set -- {{ previous_output }} with `shell_escape_variables` true.
                    """),
            Map.entry("agentic", """
                    **Block type: agentic** — Goal-driven agent: one REASON and one ACT call per iteration until the goal \
                    is met or a limit hits. Long-running, the most expensive block.
                    Required config: `goal` (string) — self-contained: absolute paths, exact names (ACT does not see what \
                    REASON saw).
                    Optional: `success_criteria` (string), `max_iterations` (default 10), `iteration_timeout_seconds` \
                    (default 300), `total_timeout_seconds` (default 1800), `max_total_tokens` (default 200000, input + output \
                    of all calls), `max_repeated_actions` (default 3), `require_approval` (bool — approval before every \
                    action), `timeout_seconds` (per CLI call, REASON and ACT).
                    """ + AI_OVERRIDES + """
                    Example config: {"goal": "Make mvn test pass in /home/user/app/backend without changing any test", \
                    "success_criteria": "mvn test exits 0", "max_iterations": 6, "max_total_tokens": 150000}
                    Note: afterwards {{ agentic_goal_achieved }} and {{ agentic_iterations }} are set.
                    """),
            Map.entry("conditional", """
                    **Block type: conditional** — If/else: evaluates `condition`, then runs `then_command` or `else_command` \
                    (commands, not nested blocks).
                    Required config: `condition` (string) — shell mode: exit code 0 = true; ai mode: the AI answers TRUE or FALSE.
                    Optional: `then_command` (string), `else_command` (string) — without the chosen one the step is SKIPPED; \
                    `execution_mode` ("ai" default | "shell", for condition and command; legacy "claude" = "ai"), \
                    `shell_escape_variables` (bool, shell mode), `timeout_seconds` (per call; shell default 300).
                    """ + AI_OVERRIDES + """
                    Example config: {"condition": "test -f package.json", "then_command": "npm test", \
                    "else_command": "echo no package.json", "execution_mode": "shell"}
                    """),
            Map.entry("foreach", """
                    **Block type: foreach** — Runs `command` once per item (a command, not nested blocks).
                    Required config: `command` (string) — prompt or shell command per item; the item is {{ item }} (or the \
                    name set in `variable_name`).
                    Optional: `items_source` ("list" default: take `items` | "command": the lines of `items_command`), \
                    `items` (list of strings, or one item per line), `items_command` (shell command printing one item per \
                    line; read only with `items_source` "command", always runs as shell), `variable_name` (default "item"), \
                    `execution_mode` ("ai" default | "shell"; legacy "claude" = "ai"), `shell_escape_variables` (bool, shell \
                    mode), `timeout_seconds` (per item; shell default 300), `continue_on_error` (bool, default false — \
                    failed items do not fail the block).
                    """ + AI_OVERRIDES + """
                    Example config: {"items_source": "command", "items_command": "ls docs/*.md", "execution_mode": "shell", \
                    "command": "wc -l {{ item }}"}
                    Note: blank lines never become items — an empty list means zero iterations.
                    """),
            Map.entry("while", """
                    **Block type: while** — Repeats `command` while `condition_command` holds, at most `max_iterations` times.
                    Required config: `command` (string) — prompt or shell command per iteration.
                    Optional: `condition_command` (string, checked before every iteration — shell mode: exit code 0 = go \
                    on; ai mode: "false", "0" or an empty answer = stop; without it the loop runs until `max_iterations` or \
                    a failed iteration), `max_iterations` (default 100), `execution_mode` ("ai" default | "shell"; legacy \
                    "claude" = "ai"), `shell_escape_variables` (bool, shell mode), `timeout_seconds` (per call; shell \
                    default 300).
                    """ + AI_OVERRIDES + """
                    Example config: {"condition_command": "test ! -f build/done.flag", "command": "./scripts/next-batch.sh", \
                    "execution_mode": "shell", "max_iterations": 10}
                    """),
            Map.entry("switch", """
                    **Block type: switch** — Multi-way branch: compares `expression` with the `value` of each case and runs \
                    the nested blocks of the first match.
                    Required config: `expression` (string; ${var} and {{ var }} are resolved), `cases` (list of {`value`, \
                    `blocks`}; `value` must equal the resolved expression, alternatives separated by "|" as in "YELLOW|RED").
                    Optional: `default` (list of blocks for no match).
                    Nested blocks: {"type": "shell" or "simple", "name": optional, `config`} — no other types. In `config`: \
                    `command`, `timeout_seconds`, `shell_escape_variables`, `on_failure` ("stop" default ends the case at a \
                    failed block, "continue" runs the next), AI overrides for "simple". The blocks of a case pass \
                    {{ previous_output }} along; a named one is {{ block.<name>.output }} later on.
                    Example config: {"expression": "{{ environment }}", "cases": [{"value": "prod", "blocks": [{"type": \
                    "shell", "config": {"command": "echo deploy to prod"}}]}, {"value": "staging|test", "blocks": [{"type": \
                    "simple", "name": "notes", "config": {"command": "Write release notes for {{ environment }}"}}]}], \
                    "default": [{"type": "shell", "config": {"command": "echo unknown environment"}}]}
                    """),
            Map.entry("retry", """
                    **Block type: retry** — Runs `command` and runs it again on failure (a command, not a nested block).
                    Required config: `command` (string) — prompt or shell command.
                    Optional: `max_retries` (default 3; attempts = retries + 1), `delay` (seconds between attempts, default \
                    5), `exponential_backoff` (bool — doubles the delay each time), `execution_mode` ("ai" default | \
                    "shell"; legacy "claude" = "ai"), `shell_escape_variables` (bool, shell mode), `timeout_seconds` (per \
                    attempt; shell default 300).
                    """ + AI_OVERRIDES + """
                    Example config: {"command": "npm test", "execution_mode": "shell", "max_retries": 2, "delay": 10}
                    """),
            Map.entry("approval", """
                    **Block type: approval** — Pauses the run until a person answers (dialog on the run page or on /approvals).
                    Required config: none — every key has a default; set `question` (Markdown, placeholders resolved; \
                    default "Approval required").
                    Optional: `options` (list, default ["Yes", "Yes always", "No"]; the approve call accepts only these), \
                    `reject_options` (list, default ["No", "Nein"] — the answers that count as rejection), `on_no` \
                    ("continue" default: step COMPLETED | "fail" | "skip"), `variable_name` (default "approval_answer" — \
                    receives the answer), `timeout_minutes` (default 30; a timeout fails the step), `mode` ("ask" default | \
                    "auto"), `auto_answer` (the answer in mode "auto").
                    Example config: {"question": "Deploy {{ version }}?", "options": ["Yes", "No", "Abort"], \
                    "reject_options": ["No", "Abort"], "on_no": "fail", "timeout_minutes": 60}
                    Approve via POST /api/v1/executions/{id}/approve with { "answer": "Yes" } (field: answer, NOT approved). \
                    "Yes always" also answers the same question for the rest of the run.
                    """),
            Map.entry("sub-workflow", """
                    **Block type: sub-workflow** — Runs another workflow as one step and waits for it (up to 30 minutes).
                    Required config: `workflow_id` (string) — id of an existing workflow.
                    There is no input mapping: the child run starts with all variables of this run plus \
                    {{ previous_output }}. At most 5 levels deep; a circular call fails.
                    Output: the last step output of the child run; its "gate_status:" and "todo_item:" markers count in \
                    the child only.
                    Example config: {"workflow_id": "<workflow-uuid>"}
                    """),
            Map.entry("skill-capability", """
                    **Block type: skill-capability** — Runs a capability of a skill as one AI call, read-only unless it may write.
                    Required config: `capability` (string) — capability name such as "analyze". List its skill in the \
                    block's top-level skillIds (next to config) so the capability's description, AI context and writes \
                    flag apply.
                    Optional: `input` (string, placeholders resolved), `params` (map, handed to the AI as parameters), \
                    `allow_writes` (bool, default false — required for capabilities that write; without it the CLI gets \
                    read-only tools), `timeout_seconds` (int).
                    """ + AI_OVERRIDES + """
                    Example config: {"capability": "analyze", "input": "{{ previous_output }}", "params": {"max_entries": 5}}
                    Needs module skills. A skill's capabilities: <fetch type="skill" id="<skill-id>"/>.
                    """),
            Map.entry("human-input", """
                    **Block type: human-input** — Pauses the run until a person enters a value or fills a form.
                    Required config: none — every key has a default; set `prompt` (question, placeholders resolved; \
                    default "Please provide input:").
                    Optional: `input_type` ("text" default | "textarea" | "number" | "select" | "checkbox" | "date"), \
                    `options` (list, choices for "select"), `variable_name` (default "user_input" — receives the answer), \
                    `default_value` (string), `required` (bool, default true), `validation_pattern` (regex), \
                    `timeout_minutes` (default 30), `use_default_on_timeout` (bool, default true — a timeout takes \
                    `default_value` if set, else the step fails), `fields` (list — a form; each field gets its own \
                    `variable_name`, which receives its value, plus `input_type`, `options`, `default_value`, `required`, \
                    `validation_pattern` and the display keys label, placeholder, description).
                    Example config: {"prompt": "Which language should the docs use?", "input_type": "select", \
                    "options": ["English", "Deutsch"], "variable_name": "doc_language", "default_value": "English"}
                    """),
            Map.entry("integration", """
                    **Block type: integration** — Calls an action of a connected integration provider. No AI, 0 tokens.
                    Required config: `provider` (string) — integration provider id such as "google-calendar", "github" or \
                    "trello" (not the AI provider), `action` (string) — action name such as "list_events".
                    Optional: `params` (map) — the inputs of the action; placeholders in string values are resolved.
                    Example config: {"provider": "google-calendar", "action": "list_events", \
                    "params": {"timeMin": "{{ started_at }}", "maxResults": 5}}
                    Output: the action result as JSON. Actions and their inputs: <fetch type="integration" \
                    id="<provider-id>"/>. Needs module integrations and a connected provider.
                    """),
            Map.entry("document", """
                    **Block type: document** — Extracts the plain text of a file via Apache Tika (PDF with a text layer, \
                    DOCX, XLSX, PPTX, HTML, TXT, EPUB, RTF, ODT). No AI, 0 tokens. No OCR: a scanned PDF yields empty text.
                    Required config: `file_path` (string) — absolute, or relative to the project (a relative path must not \
                    leave it); placeholders resolved.
                    Optional: `variable_name` (string — also stores the text in this variable), `fail_on_empty` (bool, \
                    default false), `min_characters` (default 1 — fewer characters count as empty).
                    Example config: {"file_path": "{{ invoice_path }}", "variable_name": "invoice_text", \
                    "fail_on_empty": true, "min_characters": 50}
                    """),
            Map.entry("todo-create", """
                    **Block type: todo-create** — Creates a TODO natively via the TodoService. No REST self-call, no DB access, 0 tokens.
                    Required config: `title` (string, template variables supported).
                    Optional: `description` (markdown), `priority` ("HIGH" | "MEDIUM" | "LOW" | "CRITICAL", default "MEDIUM"), \
                    `tags` (list or comma-separated), `project_id` (default: {{ project_id }} of the run), `source` \
                    ("USER" | "AI" | "WORKFLOW", default "WORKFLOW"), `source_agent` (default "workflow"), \
                    `variable_name` (default "todo_id") — context variable that receives the new TODO id.
                    Example config: {"title": "Fix {{ component }}", "priority": "HIGH", "tags": ["bug"]}
                    Note: for one tracking TODO; dynamically many TODOs from an analysis come from "todo_item:" markers instead.
                    """),
            Map.entry("todo-update", """
                    **Block type: todo-update** — Changes fields of an existing TODO by id. 0 tokens.
                    Required config: `id` (string, template variables supported, e.g. {{ todo_id }} from a todo-create block).
                    Optional: `title`, `description`, `priority`, `tags` — only the fields that are set change.
                    Example config: {"id": "{{ todo_id }}", "priority": "LOW", "tags": ["waiting"]}
                    """),
            Map.entry("todo-close", """
                    **Block type: todo-close** — Sets an existing TODO to DONE by id. 0 tokens.
                    Required config: `id` (string, template variables supported, e.g. {{ todo_id }} from a todo-create block).
                    Example config: {"id": "{{ todo_id }}"}
                    """),
            Map.entry("compress", """
                    **Block type: compress** — Deterministic compression of ONE earlier block output. No AI, 0 tokens.
                    Required config: `source` (block name or "previous"), `stages` (list of {`op`, …}, run in order; JSON \
                    stages before text stages).
                    Stages: "text.normalize", "text.truncate" {`max_chars`, `keep` "head" | "tail" | "head_tail"}, \
                    "lines.filter" {`include`, `exclude` (regex lists), `before`, `after`}, "lines.replace" {`pattern`, \
                    `replacement`}, "lines.dedupe" {`count`, `consecutive_only`}, "lines.head" and "lines.tail" {`lines`}, \
                    "markdown.section" {`headings`, `level`}, "json.select" {`paths`}, "json.limit" {`path`, `count`}, \
                    "json.sort" {`path`, `by`, `order`}, "json.to_lines" {`path`, `template` such as "{title} — {url}"}.
                    Optional: `max_chars` (default 20000, hard cap incl. header), `timeout_seconds` (default 30).
                    Output: header with the source's stored status/exit code, stage line, blank line, content. Reference it \
                    via {{ block.<name>.output }}.
                    Example config: {"source": "backend_tests", "stages": [{"op": "lines.filter", "exclude": ["^\\\\s+at "]}, \
                    {"op": "text.truncate", "max_chars": 18000}]}
                    """),
            Map.entry("compress-ai", """
                    **Block type: compress-ai** — AI compression of ONE earlier block output: single Claude call without tools.
                    Required config: `source` (block name or "previous").
                    Optional: `instruction` (literal text, no placeholders), `max_chars` (default 4000, at least 500), `model`, \
                    `effort`, `timeout_seconds`, `provider` (only "CLAUDE" — set "provider": "CLAUDE" when the workflow uses \
                    another provider).
                    Input above setting compress-ai-max-input-chars (default 60000) fails without a call — put a compress block before it.
                    Example config: {"source": "backend_kompakt", "instruction": "Name failing tests with cause, one line each", "model": "haiku"}
                    """),
            Map.entry("dashboard-report", """
                    **Block type: dashboard-report** — Fills the run's dashboard result from config fields. No AI, no shell, no marker, 0 tokens.
                    Required config: `status` (GREEN/YELLOW/RED, template variables supported).
                    Optional: `title` (max 80 chars), `summary` (max 500 chars), `items` (max 10 strings, max 200 chars each), \
                    `kpis` (max 6 × { `label` max 40, `value` max 40 as a string, `trend` UP/DOWN/FLAT optional }).
                    A placeholder that resolves empty drops its field (status: error); an unresolved placeholder or a contract
                    violation fails the step and names the field. Wins over any "dashboard_widget:" marker; module dashboard-report.
                    Example config: {"status": "GREEN", "title": "Disk space", "kpis": [{"label": "Free (MB)", \
                    "value": "{{ block.measure.output }}", "trend": "DOWN"}], "summary": "{{ block.analyse.output }}"}
                    """)
    );

    private BlockSpecs() {
    }

    /** The block types that have a specification. */
    public static Set<String> types() {
        return SPECS.keySet();
    }

    /**
     * The specifications of {@code types} for a prompt that builds whole workflows — wish, factory,
     * dialog import, which described a schema of their own (TODO cf8db6f8). The texts of
     * {@code <fetch type="block-spec"/>} without the AI override line (those workflows set the model per
     * workflow), a blank line after each type.
     *
     * @throws IllegalArgumentException for a type without a specification
     */
    public static String reference(List<String> types) {
        StringBuilder sb = new StringBuilder();
        for (String type : types) {
            String spec = SPECS.get(type);
            if (spec == null) {
                throw new IllegalArgumentException("No block specification for '" + type + "'");
            }
            spec.lines().filter(line -> !line.startsWith(AI_OVERRIDES_LINE))
                    .forEach(line -> sb.append(line).append('\n'));
            sb.append('\n');
        }
        return sb.toString();
    }

    static String format(String blockType) {
        String spec = SPECS.get(blockType);
        if (spec == null) {
            return "Unknown block type '" + blockType + "'. Available: " + SPECS.keySet() + "\n";
        }
        return spec;
    }
}
