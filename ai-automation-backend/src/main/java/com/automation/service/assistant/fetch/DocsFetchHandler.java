package com.automation.service.assistant.fetch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Fetch handler for static reference documentation: bundled docs with
 * per-section paging and the block type specifications
 * ({@code docs}, {@code block-spec}).
 */
@Component
@Slf4j
public class DocsFetchHandler {

    private static final Map<String, String> DOC_FILES = Map.of(
            "workflow-guide", "docs/WORKFLOW-GUIDE.md",
            "architektur",    "docs/architektur.md",
            "vision",         "docs/vision.md"
    );

    private static final Map<String, String> WF_GUIDE_SECTIONS = Map.ofEntries(
            Map.entry("quick-start",    "## 1."),
            Map.entry("block-types",    "## 2."),
            Map.entry("variables",      "## 3."),
            Map.entry("patterns",       "## 4."),
            Map.entry("timeouts",       "## 5."),
            Map.entry("models",         "## 6."),
            Map.entry("environment",    "## 7."),
            Map.entry("agentic",        "## 8."),
            Map.entry("memory",         "## 9."),
            Map.entry("chains",         "## 10."),
            Map.entry("dos-and-donts",  "## 11."),
            Map.entry("appendix",       "## Anhang")
    );

    public String formatDocsSection(String docId) {
        String baseId = docId;
        String sectionKey = null;
        int slash = docId.indexOf('/');
        if (slash >= 0) {
            baseId = docId.substring(0, slash);
            sectionKey = docId.substring(slash + 1);
        }

        String filename = DOC_FILES.get(baseId);
        if (filename == null) return "Unknown documentation: " + baseId + "\n";

        try {
            var resource = new ClassPathResource(filename);
            String fullContent = resource.getContentAsString(StandardCharsets.UTF_8);

            if (sectionKey == null) {
                return buildDocsToc(baseId, fullContent);
            }

            String sectionHeader = "workflow-guide".equals(baseId)
                    ? WF_GUIDE_SECTIONS.get(sectionKey) : null;
            if (sectionHeader == null) {
                String content = fullContent.length() > 8000
                        ? fullContent.substring(0, 8000) + "\n\n[Output truncated: 8000 of " + fullContent.length() + " characters]" : fullContent;
                return "**Documentation: " + baseId + "**\n\n" + content + "\n";
            }

            String section = extractSection(fullContent, sectionHeader);
            if (section.isBlank()) return "Section '" + sectionKey + "' not found in " + baseId + ".\n";
            if (section.length() > 12000) section = section.substring(0, 12000) + "\n\n[Output truncated: 12000 of " + section.length() + " characters]";
            return "**" + baseId + " / " + sectionKey + ":**\n\n" + section + "\n";

        } catch (Exception e) {
            log.debug("Could not load doc '{}': {}", docId, e.getMessage());
            return "Documentation '" + docId + "' not available.\n";
        }
    }

    private String buildDocsToc(String docId, String content) {
        var sb = new StringBuilder();
        sb.append("**Documentation: ").append(docId).append("** — Table of contents\n\n");
        boolean inFence = false;
        for (String line : content.split("\n")) {
            if (line.startsWith("```")) inFence = !inFence;
            else if (!inFence && line.startsWith("## ")) sb.append(line).append("\n");
        }
        if ("workflow-guide".equals(docId)) {
            sb.append("\nAvailable section IDs for `<fetch type=\"docs\" id=\"workflow-guide/<id>\"/>`:\n");
            WF_GUIDE_SECTIONS.forEach((k, v) -> sb.append("  ").append(k).append("\n"));
        }
        sb.append("\nFetch a section to get details.\n");
        return sb.toString();
    }

    static String extractSection(String content, String headerPrefix) {
        String[] lines = content.split("\n");
        var sb = new StringBuilder();
        boolean inSection = false;
        boolean inFence = false; // "## …" in einem Codeblock ist Beispieltext, keine Überschrift
        for (String line : lines) {
            if (line.startsWith("```")) {
                inFence = !inFence;
            } else if (!inFence) {
                if (!inSection) {
                    if (line.startsWith(headerPrefix)) inSection = true;
                } else if (line.startsWith("## ") && !line.startsWith(headerPrefix)) {
                    break;
                }
            }
            if (inSection) sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    // ── Block type specs ─────────────────────────────────────────────────────

    private static final Map<String, String> BLOCK_SPECS = Map.ofEntries(
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
                    """)
    );

    public String formatBlockSpec(String blockType) {
        String spec = BLOCK_SPECS.get(blockType);
        if (spec == null) {
            return "Unknown block type '" + blockType + "'. Available: " + BLOCK_SPECS.keySet() + "\n";
        }
        return spec;
    }
}
