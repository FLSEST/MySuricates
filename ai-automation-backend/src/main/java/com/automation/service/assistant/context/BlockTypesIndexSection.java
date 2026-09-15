package com.automation.service.assistant.context;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BlockTypesIndexSection implements AssistantContextSection {

    public record BlockTypeEntry(String type, String summary) {}

    /**
     * One entry per registered {@link com.automation.executor.BlockExecutor}. Guarded by
     * {@code BlockTypeListsGuardTest}: a new executor without an entry here fails the build.
     */
    public static final List<BlockTypeEntry> BLOCK_TYPES = List.of(
            new BlockTypeEntry("simple", "AI prompt with a single response (Claude/Codex), uses workflow model"),
            new BlockTypeEntry("shell", "Run shell command (no API access, no DB access)"),
            new BlockTypeEntry("agentic", "Multi-step agent with REASON+ACT phase, long-running, with token budget"),
            new BlockTypeEntry("conditional", "Branch: runs branch_true or branch_false depending on condition"),
            new BlockTypeEntry("foreach", "Iterates over list, runs sub-blocks per element"),
            new BlockTypeEntry("while", "Loop with exit condition and max_iterations"),
            new BlockTypeEntry("switch", "Multi-way branch based on a match value"),
            new BlockTypeEntry("retry", "Retries a sub-block on error with backoff"),
            new BlockTypeEntry("approval", "Pauses until user approval (UI confirm, optional choices)"),
            new BlockTypeEntry("sub-workflow", "Calls another workflow, passes context as inputs"),
            new BlockTypeEntry("skill-capability", "Runs a capability of a skill (declarative)"),
            new BlockTypeEntry("human-input", "Requests value(s) from user, pauses until answer"),
            new BlockTypeEntry("integration", "Action of an integration provider (e.g. github:create_issue)"),
            new BlockTypeEntry("document", "OCR/document processing (PDF, images)"),
            new BlockTypeEntry("todo-create", "Creates a TODO natively (title, priority, tags), stores its id in a variable"),
            new BlockTypeEntry("todo-update", "Updates fields of an existing TODO by id (title, description, priority, tags)"),
            new BlockTypeEntry("todo-close", "Sets an existing TODO to DONE by id"),
            new BlockTypeEntry("compress", "Deterministic compression of ONE earlier block output (stages: text/lines/markdown/json), visible step, no AI"),
            new BlockTypeEntry("compress-ai", "AI compression of ONE earlier block output: single tool-less Claude call, input limit, capped output")
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
