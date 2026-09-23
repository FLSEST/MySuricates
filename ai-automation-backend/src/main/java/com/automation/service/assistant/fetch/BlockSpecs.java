package com.automation.service.assistant.fetch;

import java.util.Map;
import java.util.Set;

/**
 * The block type specifications the assistant fetches with {@code <fetch type="block-spec" id="…"/>}:
 * required config, options and an example per type, taken from docs/WORKFLOW-GUIDE.md. One entry
 * per type of {@code BlockTypesIndexSection.BLOCK_TYPES}; {@code BlockTypeListsGuardTest} fails the
 * build when a type has none (TODO 321b414e: four types answered "Unknown block type").
 */
public final class BlockSpecs {

    private static final Map<String, String> SPECS = Map.ofEntries(
            Map.entry("simple", """
                    **Block type: simple** — Simple AI prompt, one response.
                    Required config: command (string) — prompt text with template variables ({{var}}).
                    Optional: model (string), timeout_seconds (int), label (string).
                    Example config: { "command": "Analyze {{input}} and return JSON" }
                    """),
            Map.entry("shell", """
                    **Block type: shell** — Run a shell command. NO API/DB access allowed.
                    Required config: command (string) — bash command, template variables supported.
                    Optional: timeout_seconds (int), label (string).
                    Example config: { "command": "ls -la /tmp/{{run_id}}" }
                    Note: set -euo pipefail recommended, use actuator/health instead of direct DB.
                    """),
            Map.entry("agentic", """
                    **Block type: agentic** — Multi-stage agent with REASON+ACT phases, long-running.
                    Required config: prompt (string), max_total_tokens (int) — cumulative budget.
                    Optional: model, max_turns (int), timeout_seconds, label.
                    Example config: { "prompt": "Refactor X until tests pass", "max_total_tokens": 50000 }
                    Note: REASON uses --system-prompt, ACT uses --append-system-prompt.
                    """),
            Map.entry("conditional", """
                    **Block type: conditional** — Branch based on a condition.
                    Required config: condition (string expression, JSONPath/template).
                    Required sub-blocks: true_blocks (list), false_blocks (list).
                    Example: { "condition": "{{score}} > 0.8", "true_blocks": [...], "false_blocks": [...] }
                    """),
            Map.entry("foreach", """
                    **Block type: foreach** — Iterate over a list.
                    Required config: items (template variable that resolves to a list).
                    Required sub-blocks: blocks (list).
                    Example: { "items": "{{file_list}}", "blocks": [...] }
                    Note: the iterator variable in the sub-block is called {{item}}.
                    """),
            Map.entry("while", """
                    **Block type: while** — Loop with a stop condition and max_iterations.
                    Required config: condition (string), max_iterations (int).
                    Required sub-blocks: blocks (list).
                    Example: { "condition": "{{retries}} < 3", "max_iterations": 10, "blocks": [...] }
                    """),
            Map.entry("switch", """
                    **Block type: switch** — Multi-way branch based on a match value.
                    Required config: value (string), cases (list of { "match": ..., "blocks": [...] }).
                    Optional: default_blocks (list).
                    """),
            Map.entry("retry", """
                    **Block type: retry** — Retries a sub-block on failure.
                    Required config: max_attempts (int), block (single block definition).
                    Optional: backoff_ms (int), retry_on (list of error-type strings).
                    """),
            Map.entry("approval", """
                    **Block type: approval** — Pauses until user approval.
                    Required config: question (string).
                    Optional: options (list of string answers — e.g. ["Yes","No","Skip"]), timeout_seconds.
                    Approve via POST /api/v1/executions/{id}/approve with { "answer": "Yes" } (field: answer, NOT approved).
                    """),
            Map.entry("sub-workflow", """
                    **Block type: sub-workflow** — Run another workflow as a block.
                    Required config: workflow_id (string).
                    Optional: input_context (Map) — passed as context to the sub-workflow.
                    """),
            Map.entry("skill-capability", """
                    **Block type: skill-capability** — Run a capability of a skill (declarative).
                    Required config: skill_id (string), capability_name (string).
                    Optional: inputs (Map) — validated against capability.inputSchema.
                    """),
            Map.entry("human-input", """
                    **Block type: human-input** — Ask the user for a value, pauses until answered.
                    Required config: prompt (string).
                    Optional: input_type (text/number/boolean/json), default (any).
                    """),
            Map.entry("integration", """
                    **Block type: integration** — Action of an integration provider.
                    Required config: provider_id (string), action (string).
                    Optional: inputs (Map) — validated against action.inputSchema.
                    Example: { "provider_id": "github", "action": "create_issue", "inputs": {...} }
                    Fetch available providers via <fetch type="integration" id="<provider-id>"/>
                    """),
            Map.entry("document", """
                    **Block type: document** — OCR/document processing (PDF, images).
                    Required config: file_path (string or template variable).
                    Optional: extract_mode (text/structured), language (hint for OCR).
                    """),
            Map.entry("todo-create", """
                    **Block type: todo-create** — Creates a TODO natively via the TodoService. No REST self-call, no DB access, 0 tokens.
                    Required config: title (string, template variables supported).
                    Optional: description (markdown), priority (HIGH/MEDIUM/LOW/CRITICAL, default MEDIUM), tags (list or comma-separated),
                    project_id (default: context variable project_id), source (USER/AI/WORKFLOW, default WORKFLOW), source_agent (default "workflow"),
                    variable_name (default "todo_id") — context variable that receives the new TODO id.
                    Example config: { "title": "Fix {{ component }}", "priority": "HIGH", "tags": ["bug"] }
                    Note: for one tracking TODO; dynamically many TODOs from an analysis come from todo_item: markers instead.
                    """),
            Map.entry("todo-update", """
                    **Block type: todo-update** — Changes fields of an existing TODO by id. 0 tokens.
                    Required config: id (string, template variables supported, e.g. {{ todo_id }} from a todo-create block).
                    Optional: title, description, priority, tags — only the fields that are set change.
                    Example config: { "id": "{{ todo_id }}", "priority": "LOW", "tags": ["waiting"] }
                    """),
            Map.entry("todo-close", """
                    **Block type: todo-close** — Sets an existing TODO to DONE by id. 0 tokens.
                    Required config: id (string, template variables supported, e.g. {{ todo_id }} from a todo-create block).
                    Example config: { "id": "{{ todo_id }}" }
                    """),
            Map.entry("compress", """
                    **Block type: compress** — Deterministic compression of ONE earlier block output. No AI, 0 tokens.
                    Required config: source (block name or "previous"), stages (list of {op, …}).
                    Stages: text.normalize, text.truncate{max_chars,keep=head|tail|head_tail}, lines.filter{include,exclude,before,after},
                    lines.replace{pattern,replacement}, lines.dedupe{count,consecutive_only}, lines.head{lines}, lines.tail{lines},
                    markdown.section{headings,level}, json.select{paths}, json.limit{path,count}, json.sort{path,by,order}, json.to_lines{path,template "{field}"}.
                    Optional: max_chars (default 20000, hard cap incl. header), timeout_seconds (default 30).
                    Output: header with the source's stored status/exit code, stage line, blank line, content. Reference it via {{ block.<name>.output }}.
                    Example: { "source": "backend_tests", "stages": [{"op":"lines.filter","exclude":["^\\\\s+at "]},{"op":"text.truncate","max_chars":18000}] }
                    """),
            Map.entry("compress-ai", """
                    **Block type: compress-ai** — AI compression of ONE earlier block output: single Claude call without tools.
                    Required config: source (block name or "previous").
                    Optional: instruction (literal text, no placeholders), max_chars (default 4000), model, effort, timeout_seconds,
                    provider (only "CLAUDE" — set "provider": "CLAUDE" when the workflow uses another provider).
                    Input above setting compress-ai-max-input-chars (default 60000) fails without a call — put a compress block before it.
                    Example: { "source": "backend_kompakt", "instruction": "Name failing tests with cause, one line each", "model": "haiku" }
                    """),
            Map.entry("dashboard-report", """
                    **Block type: dashboard-report** — Fills the run's dashboard result from config fields. No AI, no shell, no marker, 0 tokens.
                    Required config: status (GREEN/YELLOW/RED, template variables supported).
                    Optional: title (max 80 chars), summary (max 500 chars), items (max 10 strings, max 200 chars each),
                    kpis (max 6 × { "label" max 40, "value" max 40 as a string, "trend" UP/DOWN/FLAT optional }).
                    A placeholder that resolves empty drops its field (status: error); an unresolved placeholder or a contract
                    violation fails the step and names the field. Wins over any dashboard_widget: marker; module dashboard-report.
                    Example config: { "status": "{{ gate_status }}", "title": "Nightly QA", "kpis": [{ "label": "Hunts", "value": "{{ hunt_count }}", "trend": "UP" }], "summary": "{{ block.analyse.output }}" }
                    """)
    );

    private BlockSpecs() {
    }

    /** The block types that have a specification. */
    public static Set<String> types() {
        return SPECS.keySet();
    }

    static String format(String blockType) {
        String spec = SPECS.get(blockType);
        if (spec == null) {
            return "Unknown block type '" + blockType + "'. Available: " + SPECS.keySet() + "\n";
        }
        return spec;
    }
}
