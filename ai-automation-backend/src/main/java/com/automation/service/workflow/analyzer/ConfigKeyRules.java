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
            check(schema, block.getType(), block.getConfig(), new Where(index, outer, null, null), out);
            NestedBlocks.visit(block.getConfig(), nested -> {
                String path = "Block " + (index + 1) + " › " + nested.path();
                boolean named = nested.name() != null && !nested.name().isBlank();
                check(schema, nested.type(), nested.config(), new Where(index, outer,
                        path + (named ? " „" + nested.name() + "“" : ""),
                        path + (named ? " \"" + nested.name() + "\"" : "")), out);
            });
        }
    }

    /** Wo ein Befund hängt: Index und Name des äußeren Blocks, bei verschachtelten der Pfad dazu (de/en). */
    private record Where(int index, String name, String path, String pathEn) {
        String prefix() {
            return path == null ? "" : path + ": ";
        }

        String prefixEn() {
            return pathEn == null ? "" : pathEn + ": ";
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
                // config.blocks meldet BlockShapeRules genauer (dead-nested-blocks, HIGH)
                if ("blocks".equals(key)) continue;
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.MEDIUM, where.index(), where.name(),
                        where.prefix() + "unbekannter Config-Schlüssel " + key + " — der " + type
                                + "-Block liest ihn nicht, die Einstellung wirkt nicht" + hint(key, suggestable(fields), false),
                        where.prefixEn() + "unknown config key " + key + " — the " + type
                                + " block does not read it, the setting has no effect" + hint(key, suggestable(fields), true)));
            } else if (!onlyWithMet(field, cfg)) {
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.MEDIUM, where.index(), where.name(),
                        where.prefix() + key + " wirkt nur mit " + condition(field) + " — so liest der " + type
                                + "-Block ihn nicht, die Einstellung wirkt nicht",
                        where.prefixEn() + key + " only takes effect with " + condition(field) + " — as it is, the "
                                + type + " block does not read it, the setting has no effect"));
            } else if (!skipValueCheck(type, key, cfg)) {
                checkValue(field, entry.getValue(), where, out);
            }
        }

        for (String required : schema.requiredKeys(type)) {
            if (cfg.get(required) != null || legacyKeyPresent(fields, required, cfg)) continue;
            String instead = presentInstead(cfg, fields, required);
            out.add(AnalyzerFinding.of("missing-required-config-key", Severity.HIGH, where.index(), where.name(),
                    where.prefix() + "Pflichtschlüssel " + required + " fehlt — ohne ihn kann der " + type
                            + "-Block nicht laufen" + (instead == null ? "" : " (gesetzt ist " + instead + ")"),
                    where.prefixEn() + "required key " + required + " is missing — without it the " + type
                            + " block cannot run" + (instead == null ? "" : " (" + instead + " is set instead)")));
        }
        checkOneOf(schema, type, cfg, where, out);
    }

    /** {@code requiredOneOf}: genau einer der Schlüssel – keiner oder mehrere lassen den Block abbrechen. */
    private static void checkOneOf(BlockConfigSchema schema, String type, Map<String, Object> cfg, Where where,
                                   List<AnalyzerFinding> out) {
        List<String> oneOf = schema.requiredOneOfKeys(type);
        // Ein leerer Text gilt wie im Executor als nicht gesetzt
        long present = oneOf.stream().filter(key -> cfg.get(key) != null
                && !(cfg.get(key) instanceof String text && text.isBlank())).count();
        if (oneOf.isEmpty() || present == 1) return;
        String de = String.join(" oder ", oneOf);
        String en = String.join(" or ", oneOf);
        out.add(AnalyzerFinding.of("missing-required-config-key", Severity.HIGH, where.index(), where.name(),
                where.prefix() + (present == 0
                        ? "Pflichtschlüssel " + de + " fehlt — ohne einen davon kann der " + type + "-Block nicht laufen"
                        : de + " schließen sich aus — nur einer darf gesetzt sein, sonst bricht der " + type
                                + "-Block ab"),
                where.prefixEn() + (present == 0
                        ? "required key " + en + " is missing — without one of them the " + type + " block cannot run"
                        : en + " exclude each other — set only one, otherwise the " + type + " block fails")));
    }

    private static void checkValue(BlockConfigSchema.Field field, Object value, Where where, List<AnalyzerFinding> out) {
        if (field.values().isEmpty() || value == null) return;
        if (value instanceof String text && (text.contains("{{") || field.allows(text))) return;
        String shown = value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        Severity severity = "on_failure".equals(field.key()) ? Severity.HIGH : Severity.MEDIUM;
        out.add(AnalyzerFinding.of("invalid-config-value", severity, where.index(), where.name(),
                where.prefix() + field.key() + ": " + shown + " ist kein gültiger Wert, erlaubt: "
                        + String.join(", ", field.values()),
                where.prefixEn() + field.key() + ": " + shown + " is not a valid value, allowed: "
                        + String.join(", ", field.values())));
    }

    /** {@code onlyWith}: jeder genannte Schlüssel hat den Wert — {@code "*"} heißt: ist gesetzt. */
    private static boolean onlyWithMet(BlockConfigSchema.Field field, Map<String, Object> cfg) {
        return field.onlyWith().entrySet().stream().allMatch(e -> ANY.equals(e.getValue())
                ? cfg.get(e.getKey()) != null && !(cfg.get(e.getKey()) instanceof String s && s.isBlank())
                : e.getValue().equals(cfg.get(e.getKey())));
    }

    private static String condition(BlockConfigSchema.Field field) {
        return String.join(", ", field.onlyWith().entrySet().stream()
                .map(e -> ANY.equals(e.getValue()) ? e.getKey() : e.getKey() + ": " + e.getValue()).toList());
    }

    private static final String ANY = "*";

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

    /** Steht statt des Pflichtschlüssels ein unbekannter, der ihm ähnelt, nennt die Meldung ihn gleich mit; sonst {@code null}. */
    private static String presentInstead(Map<String, Object> cfg, Map<String, BlockConfigSchema.Field> fields,
                                         String required) {
        for (String key : cfg.keySet()) {
            if (!fields.containsKey(key) && required.equals(suggestion(key, suggestable(fields)))) {
                return key;
            }
        }
        return null;
    }

    /** Vorgeschlagen werden nur aktuelle Namen, nie die alte Schreibweise eines Schlüssels. */
    private static Set<String> suggestable(Map<String, BlockConfigSchema.Field> fields) {
        Set<String> keys = new LinkedHashSet<>();
        fields.values().forEach(f -> {
            if (f.legacyFor() == null) keys.add(f.key());
        });
        return keys;
    }

    private static String hint(String key, Set<String> known, boolean en) {
        if (BLOCK_LEVEL_KEYS.contains(key)) {
            return en ? "; " + key + " belongs on the block level, next to config"
                    : "; " + key + " gehört neben config auf die Blockebene";
        }
        String suggestion = suggestion(key, known);
        if (suggestion == null) return "";
        return en ? "; did you mean " + suggestion + "?" : "; meintest du " + suggestion + "?";
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
