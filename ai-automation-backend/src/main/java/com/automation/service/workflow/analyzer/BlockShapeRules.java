package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.NestedBlocks;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is on the shape of a block but never runs - top-level and nested (switch cases, {@code default},
 * {@code blocks}).
 *
 * <ul>
 *   <li>{@code dead-nested-blocks} ({@code HIGH}) - {@code config.blocks}: no executor runs a
 *       {@code blocks} list, on no block type (5536c5aa). foreach, retry, conditional and while
 *       run their {@code command}, switch only {@code cases[].blocks} and {@code default}. The
 *       nested blocks never run, the block does something other than the definition shows.
 *       {@link ConfigKeyRules} therefore does not report the key again as unknown.</li>
 *   <li>{@code unknown-block-field} - a field next to {@code type}, {@code name}, {@code config},
 *       {@code requiredCapabilities} and {@code skillIds} (d3aa0b28). It has no effect; the
 *       ObjectMapper used to drop it silently, since {@link BlockDefinition#unknownFields()} it is kept and reported
 *       here. {@code HIGH} if it is a config key of the block type ({@code "on_failure": "continue"}
 *       next to instead of inside {@code config}: the run stops anyway), {@code MEDIUM} for a near spelling of a
 *       block field ({@code skill_ids}), otherwise {@code LOW}.</li>
 * </ul>
 */
final class BlockShapeRules {

    private BlockShapeRules() {}

    /** The fields a block may have besides {@code config} - {@link BlockDefinition}. */
    static final Set<String> BLOCK_FIELDS = BlockDefinition.FIELDS;

    private static final String DEAD_LIST = "blocks";

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        BlockConfigSchema schema = BlockConfigSchema.get();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            int index = i;
            String outer = block.getName();
            check(schema, block.getType(), block.getConfig(), block.unknownFields().keySet(),
                    new Where(index, outer, "", ""), out);
            NestedBlocks.visit(block.getConfig(), nested -> {
                String path = "Block " + (index + 1) + " › " + nested.path();
                boolean named = nested.name() != null && !nested.name().isBlank();
                Set<String> unknown = new LinkedHashSet<>(nested.fields());
                unknown.removeAll(BLOCK_FIELDS);
                check(schema, nested.type(), nested.config(), unknown, new Where(index, outer,
                        path + (named ? " „" + nested.name() + "“" : "") + ": ",
                        path + (named ? " \"" + nested.name() + "\"" : "") + ": "), out);
            });
        }
    }

    /** Index and name of the outer block, for nested ones the path to it as a prefix (de/en). */
    private record Where(int index, String name, String prefix, String prefixEn) {}

    private static void check(BlockConfigSchema schema, String type, Map<String, Object> config,
                              Set<String> unknownFields, Where where, List<AnalyzerFinding> out) {
        if (config != null && config.get(DEAD_LIST) != null) {
            String what = config.get(DEAD_LIST) instanceof List<?> list ? list.size() + " " : "";
            String label = type == null ? "" : " (" + type + ")";
            out.add(AnalyzerFinding.of("dead-nested-blocks", Severity.HIGH, where.index(), where.name(),
                    where.prefix() + "config.blocks" + label + " mit " + what + "Block-Einträgen läuft nie — kein "
                            + "Blocktyp führt eine blocks-Liste aus (switch nur cases[].blocks und default); den Auftrag "
                            + "in command schreiben oder die Schritte als eigene Blöcke anlegen",
                    where.prefixEn() + "config.blocks" + label + " with " + what + "block entries never runs — no "
                            + "block type runs a blocks list (switch only cases[].blocks and default); put the task "
                            + "into command or make the steps blocks of their own"));
        }
        Map<String, BlockConfigSchema.Field> configKeys = schema.fieldsFor(type);
        for (String field : unknownFields) {
            if (configKeys.containsKey(field)) {
                out.add(AnalyzerFinding.of("unknown-block-field", Severity.HIGH, where.index(), where.name(),
                        where.prefix() + field + " steht neben config statt darin — als Blockfeld liest es kein "
                                + "Lauf, die Einstellung wirkt nicht; in config verschieben",
                        where.prefixEn() + field + " sits next to config instead of inside it — as a block field no "
                                + "run reads it, the setting has no effect; move it into config"));
                continue;
            }
            String suggestion = ConfigKeyRules.suggestion(field, BLOCK_FIELDS);
            if (suggestion != null) {
                out.add(AnalyzerFinding.of("unknown-block-field", Severity.MEDIUM, where.index(), where.name(),
                        where.prefix() + "unbekanntes Blockfeld " + field + " — kein Lauf liest es; meintest du "
                                + suggestion + "?",
                        where.prefixEn() + "unknown block field " + field + " — no run reads it; did you mean "
                                + suggestion + "?"));
                continue;
            }
            out.add(AnalyzerFinding.of("unknown-block-field", Severity.LOW, where.index(), where.name(),
                    where.prefix() + "unbekanntes Blockfeld " + field + " — kein Lauf liest es (erlaubt neben config: "
                            + "type, name, requiredCapabilities, skillIds)",
                    where.prefixEn() + "unknown block field " + field + " — no run reads it (allowed next to config: "
                            + "type, name, requiredCapabilities, skillIds)"));
        }
    }
}
