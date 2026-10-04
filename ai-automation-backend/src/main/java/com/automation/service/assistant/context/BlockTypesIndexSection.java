package com.automation.service.assistant.context;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BlockTypesIndexSection implements AssistantContextSection {

    public record BlockTypeEntry(String type, String summary) {}

    /**
     * One entry per registered {@link com.automation.executor.BlockExecutor}. Guarded by
     * {@code BlockTypeListsGuardTest}: a new executor without an entry here fails the build. Config
     * keys in a summary go in backticks; {@code BlockSpecConfigKeysGateTest} checks that the
     * executor reads them (TODO c4b81017).
     */
    public static final List<BlockTypeEntry> BLOCK_TYPES = List.of(
            new BlockTypeEntry("simple", "One AI prompt (`command`), one response; provider and model from the block or the workflow"),
            new BlockTypeEntry("shell", "Runs a shell command, optionally in a container (no API access, no DB access)"),
            new BlockTypeEntry("agentic", "Goal-driven agent (`goal`) with REASON+ACT per iteration, long-running, with token budget"),
            new BlockTypeEntry("conditional", "If/else: evaluates `condition` (AI or shell), runs `then_command` or `else_command`"),
            new BlockTypeEntry("foreach", "Runs `command` — or workflow `workflow_id` — once per item (`items` or the lines of `items_command`)"),
            new BlockTypeEntry("while", "Repeats `command` while `condition_command` holds, at most `max_iterations` times"),
            new BlockTypeEntry("switch", "Matches `expression` against case values, runs the nested shell/simple blocks of the match"),
            new BlockTypeEntry("retry", "Runs `command` again on failure (`max_retries`, `delay`, optional exponential backoff)"),
            new BlockTypeEntry("approval", "Pauses until a person answers (UI dialog, custom options, rejection handling)"),
            new BlockTypeEntry("sub-workflow", "Runs another workflow (`workflow_id`) with this run's variables and waits for it"),
            new BlockTypeEntry("skill-capability", "Runs a capability of a skill (`capability`) as one AI call, read-only unless `allow_writes`"),
            new BlockTypeEntry("human-input", "Requests value(s) from user, pauses until answer"),
            new BlockTypeEntry("integration", "Calls an action of a connected integration provider (`provider`, `action`, `params`)"),
            new BlockTypeEntry("document", "Extracts plain text from PDF/DOCX/XLSX/HTML/TXT files via Apache Tika, no OCR, 0 tokens"),
            new BlockTypeEntry("todo-create", "Creates a TODO natively (title, priority, tags), stores its id in a variable"),
            new BlockTypeEntry("todo-update", "Updates fields of an existing TODO by id, or several TODOs of the run's project via `updates` (status, tags, note)"),
            new BlockTypeEntry("todo-close", "Sets an existing TODO to DONE by id"),
            new BlockTypeEntry("compress", "Deterministic compression of ONE earlier block output (stages: text/lines/markdown/json), visible step, no AI"),
            new BlockTypeEntry("compress-ai", "AI compression of ONE earlier block output: single tool-less Claude call, input limit, capped output"),
            new BlockTypeEntry("dashboard-report", "Fills the dashboard result contract from config fields (status/title/kpis/items/summary), no AI, no marker")
    );

    @Override
    public void append(StringBuilder sb, boolean compact) {
        if (compact) {
            sb.append("**Block Types:** ").append(BLOCK_TYPES.size()).append(" available\n");
            return;
        }
        sb.append("**Block Types (").append(BLOCK_TYPES.size()).append("):**\n");
        BLOCK_TYPES.forEach(e -> sb
                .append("  - [").append(e.type()).append("] ")
                .append(e.summary()).append("\n"));
        sb.append("  Detail/Schema via <fetch type=\"block-spec\" id=\"<block-type>\"/>\n\n");
    }
}
