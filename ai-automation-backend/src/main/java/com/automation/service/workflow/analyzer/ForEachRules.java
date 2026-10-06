package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.Collection;
import java.util.List;

/**
 * Rules for {@code foreach} blocks.
 *
 * <p>{@code items-command-without-source}: a block sets {@code items_command}, but
 * {@code items_source} is not {@code "command"}. {@code ForEachBlockExecutor} only runs the command
 * with that source and otherwise takes {@code items} - if the list is missing, the block runs with
 * zero items and still ends COMPLETED. That is exactly what the editor default set for new
 * foreach blocks (TODO 2f01ab01). {@code HIGH} if nothing runs as a result; {@code MEDIUM} if
 * {@code items} steps in and only the command is dead.
 */
final class ForEachRules {

    private ForEachRules() {}

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (!"foreach".equals(block.getType()) || block.getConfig() == null) continue;
            String itemsCommand = BlockText.directValue(block, "items_command");
            Object source = block.getConfig().get("items_source");
            if (itemsCommand == null || itemsCommand.isBlank() || "command".equals(source)) continue;

            boolean hasItems = hasItems(block.getConfig().get("items"));
            out.add(AnalyzerFinding.of("items-command-without-source", hasItems ? Severity.MEDIUM : Severity.HIGH,
                    i, block.getName(), "setzt items_command, aber items_source ist "
                            + (source == null ? "nicht gesetzt" : "\"" + source + "\"")
                            + " — der Befehl läuft nie, "
                            + (hasItems ? "die Items kommen aus items" : "der Block läuft mit null Items")
                            + "; items_source: \"command\" setzen",
                    "sets items_command, but items_source is "
                            + (source == null ? "not set" : "\"" + source + "\"")
                            + " — the command never runs, "
                            + (hasItems ? "the items come from items" : "the block runs with zero items")
                            + "; set items_source: \"command\""));
        }
    }

    /** Like the executor: a list with entries or a text with at least one line. */
    private static boolean hasItems(Object items) {
        if (items instanceof Collection<?> list) {
            return list.stream().anyMatch(item -> item != null && !item.toString().isBlank());
        }
        return items instanceof String text && !text.isBlank();
    }
}
