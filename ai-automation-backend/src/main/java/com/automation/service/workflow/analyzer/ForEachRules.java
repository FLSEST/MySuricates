package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.Collection;
import java.util.List;

/**
 * Regeln für {@code foreach}-Blöcke.
 *
 * <p>{@code items-command-without-source}: Ein Block setzt {@code items_command}, aber
 * {@code items_source} ist nicht {@code "command"}. {@code ForEachBlockExecutor} führt den Befehl
 * nur mit dieser Quelle aus und nimmt sonst {@code items} — fehlt die Liste, läuft der Block mit
 * null Items und endet trotzdem COMPLETED. Genau das setzte der Editor-Standard für neue
 * foreach-Blöcke (TODO 2f01ab01). {@code HIGH}, wenn dadurch nichts läuft; {@code MEDIUM}, wenn
 * {@code items} einspringt und nur der Befehl tot ist.
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

    /** Wie der Executor: eine Liste mit Einträgen oder ein Text mit mindestens einer Zeile. */
    private static boolean hasItems(Object items) {
        if (items instanceof Collection<?> list) {
            return list.stream().anyMatch(item -> item != null && !item.toString().isBlank());
        }
        return items instanceof String text && !text.isBlank();
    }
}
