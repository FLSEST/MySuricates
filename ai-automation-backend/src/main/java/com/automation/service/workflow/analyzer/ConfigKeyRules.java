package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.executor.NestedBlocks;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Config-Schlüssel gegen das Block-Config-Schema, das auch der Editor nutzt (TODO f5153c47) — oben wie
 * verschachtelt (switch-Cases, {@code default}, {@code blocks}).
 *
 * <ul>
 *   <li>{@code unknown-config-key} ({@code MEDIUM}) — kein Executor liest den Schlüssel, die Einstellung
 *       wirkt nicht: {@code command} statt {@code goal} im agentic-Block, {@code max_chars} im
 *       document-Block, {@code container_image} eines retry-Blocks ohne {@code execution_mode: shell}.
 *       Bei naher Schreibweise mit Vorschlag.</li>
 *   <li>{@code missing-required-config-key} ({@code HIGH}) — ohne ihn kann der Block nicht laufen.</li>
 *   <li>{@code invalid-config-value} ({@code HIGH} bei {@code on_failure}, sonst {@code MEDIUM}) — Wert
 *       außerhalb der festen Werte des Schlüssels. Werte mit {@code {{} (Platzhalter) werden erst zur
 *       Laufzeit bekannt und bleiben ungeprüft; Legacy-Werte gelten als gültig.</li>
 * </ul>
 *
 * <p>Blocktypen ohne Schema bleiben ungeprüft. {@code items_source} eines foreach mit
 * {@code items_command} prüft {@link ForEachRules} genauer ({@code items-command-without-source}); hier
 * entfällt der Wert-Befund dann, damit derselbe Fehler nicht zweimal erscheint.
 */
final class ConfigKeyRules {

    private ConfigKeyRules() {}

    /** Schlüssel, die neben {@code config} auf die Blockebene gehören. */
    private static final Set<String> BLOCK_LEVEL_KEYS = Set.of("name", "type", "skillIds", "requiredCapabilities");

    static void apply(List<BlockDefinition> blocks, List<AnalyzerFinding> out) {
        BlockConfigSchema schema = BlockConfigSchema.get();
        for (int i = 0; i < blocks.size(); i++) {
            BlockDefinition block = blocks.get(i);
            if (block == null) continue;
            int index = i;
            String outer = block.getName();
            check(schema, block.getType(), block.getConfig(), new Where(index, outer, null), out);
            NestedBlocks.visit(block.getConfig(), nested -> check(schema, nested.type(), nested.config(),
                    new Where(index, outer, "Block " + (index + 1) + " › " + nested.path()
                            + (nested.name() != null && !nested.name().isBlank() ? " „" + nested.name() + "“" : "")),
                    out));
        }
    }

    /** Wo ein Befund hängt: Index und Name des äußeren Blocks, bei verschachtelten der Pfad dazu. */
    private record Where(int index, String name, String path) {
        String prefix() {
            return path == null ? "" : path + ": ";
        }
    }

    private static void check(BlockConfigSchema schema, String type, Map<String, Object> config, Where where,
                              List<AnalyzerFinding> out) {
        if (!schema.knowsType(type)) return;
        Map<String, BlockConfigSchema.Field> fields = schema.fieldsFor(type);
        Map<String, Object> cfg = config != null ? config : Map.of();

        for (Map.Entry<String, Object> entry : cfg.entrySet()) {
            String key = entry.getKey();
            BlockConfigSchema.Field field = fields.get(key);
            if (field == null) {
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.MEDIUM, where.index(), where.name(),
                        where.prefix() + "unbekannter Config-Schlüssel " + key + " — der " + type
                                + "-Block liest ihn nicht, die Einstellung wirkt nicht" + hint(key, suggestable(fields))));
            } else if (!onlyWithMet(field, cfg)) {
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.MEDIUM, where.index(), where.name(),
                        where.prefix() + key + " wirkt nur mit " + condition(field) + " — so liest der " + type
                                + "-Block ihn nicht, die Einstellung wirkt nicht"));
            } else if (!skipValueCheck(type, key, cfg)) {
                checkValue(field, entry.getValue(), where, out);
            }
        }

        for (String required : schema.requiredKeys(type)) {
            if (cfg.get(required) != null || legacyKeyPresent(fields, required, cfg)) continue;
            out.add(AnalyzerFinding.of("missing-required-config-key", Severity.HIGH, where.index(), where.name(),
                    where.prefix() + "Pflichtschlüssel " + required + " fehlt — ohne ihn kann der " + type
                            + "-Block nicht laufen" + presentInstead(cfg, fields, required)));
        }
    }

    private static void checkValue(BlockConfigSchema.Field field, Object value, Where where, List<AnalyzerFinding> out) {
        if (field.values().isEmpty() || value == null) return;
        if (value instanceof String text && (text.contains("{{") || field.allows(text))) return;
        String shown = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        Severity severity = "on_failure".equals(field.key()) ? Severity.HIGH : Severity.MEDIUM;
        out.add(AnalyzerFinding.of("invalid-config-value", severity, where.index(), where.name(),
                where.prefix() + field.key() + ": " + shown + " ist kein gültiger Wert, erlaubt: "
                        + String.join(", ", field.values())));
    }

    private static boolean onlyWithMet(BlockConfigSchema.Field field, Map<String, Object> cfg) {
        return field.onlyWith().entrySet().stream().allMatch(e -> e.getValue().equals(cfg.get(e.getKey())));
    }

    private static String condition(BlockConfigSchema.Field field) {
        return String.join(", ", field.onlyWith().entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).toList());
    }

    /** foreach mit items_command: {@link ForEachRules} meldet einen falschen items_source-Wert schon genauer. */
    private static boolean skipValueCheck(String type, String key, Map<String, Object> cfg) {
        return "foreach".equals(type) && "items_source".equals(key)
                && cfg.get("items_command") instanceof String command && !command.isBlank();
    }

    /** Ob ein alter Name des Pflichtschlüssels gesetzt ist, den der Executor weiter liest ({@code workflowId}). */
    private static boolean legacyKeyPresent(Map<String, BlockConfigSchema.Field> fields, String required,
                                            Map<String, Object> cfg) {
        return fields.values().stream()
                .anyMatch(f -> required.equals(f.legacyFor()) && cfg.get(f.key()) != null);
    }

    /** Steht statt des Pflichtschlüssels ein unbekannter, der ihm ähnelt, sagt die Meldung das gleich mit. */
    private static String presentInstead(Map<String, Object> cfg, Map<String, BlockConfigSchema.Field> fields,
                                         String required) {
        for (String key : cfg.keySet()) {
            if (!fields.containsKey(key) && required.equals(suggestion(key, suggestable(fields)))) {
                return " (gesetzt ist " + key + ")";
            }
        }
        return "";
    }

    /** Vorgeschlagen werden nur aktuelle Namen, nie die alte Schreibweise eines Schlüssels. */
    private static Set<String> suggestable(Map<String, BlockConfigSchema.Field> fields) {
        Set<String> keys = new LinkedHashSet<>();
        fields.values().forEach(f -> {
            if (f.legacyFor() == null) keys.add(f.key());
        });
        return keys;
    }

    private static String hint(String key, Set<String> known) {
        if (BLOCK_LEVEL_KEYS.contains(key)) {
            return "; " + key + " gehört neben config auf die Blockebene";
        }
        String suggestion = suggestion(key, known);
        return suggestion != null ? "; meintest du " + suggestion + "?" : "";
    }

    /**
     * Der nächstgelegene bekannte Schlüssel: gleich bis auf camelCase/snake_case, sonst Levenshtein ≤ 2 —
     * bei kurzen Schlüsseln strenger, damit aus {@code mode} kein Vorschlag für {@code name} wird.
     */
    static String suggestion(String key, Set<String> known) {
        String normalized = normalize(key);
        for (String candidate : known) {
            if (normalize(candidate).equals(normalized)) return candidate;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            int distance = levenshtein(key.toLowerCase(Locale.ROOT), candidate);
            int limit = Math.min(2, (Math.min(key.length(), candidate.length()) - 1) / 2);
            if (distance <= limit && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static String normalize(String key) {
        return key.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
