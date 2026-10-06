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
 * Config keys against the block config schema that the editor also uses (TODO f5153c47) - top-level and
 * nested (switch cases, {@code default}, {@code blocks}).
 *
 * <ul>
 *   <li>{@code unknown-config-key} ({@code MEDIUM}) - no executor reads the key, the setting
 *       has no effect: {@code command} instead of {@code goal} in the agentic block, {@code max_chars} in the
 *       document block, {@code container_image} of a retry block without {@code execution_mode: shell}.
 *       With a suggestion for a near spelling.</li>
 *   <li>{@code missing-required-config-key} ({@code HIGH}) - without it the block cannot run.</li>
 *   <li>{@code invalid-config-value} ({@code HIGH} for {@code on_failure}, otherwise {@code MEDIUM}) - value
 *       outside the key's fixed values. Values with {@code {{} (placeholder) only become known at
 *       runtime and stay unchecked; legacy values count as valid.</li>
 *   <li>{@code legacy-config-key} ({@code LOW}) - old name of a key ({@code legacyFor} in the schema,
 *       e.g. {@code workflowId} on sub-workflow): still works but is being phased out (TODO 5ee07e94). The message
 *       names the new name; if that is also set, only it applies.</li>
 * </ul>
 *
 * <p>Block types without a schema stay unchecked. {@code items_source} of a foreach with
 * {@code items_command} is checked more precisely by {@link ForEachRules} ({@code items-command-without-source}); the
 * value finding is dropped here then, so the same error does not appear twice.
 */
final class ConfigKeyRules {

    private ConfigKeyRules() {}

    /** Keys that belong on the block level next to {@code config}. */
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

    /** Where a finding hangs: index and name of the outer block, for nested ones the path to it (de/en). */
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
                // config.blocks is reported more precisely by BlockShapeRules (dead-nested-blocks, HIGH)
                if ("blocks".equals(key)) continue;
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.MEDIUM, where.index(), where.name(),
                        where.prefix() + "unbekannter Config-Schlüssel " + key + " — der " + type
                                + "-Block liest ihn nicht, die Einstellung wirkt nicht" + hint(key, suggestable(fields), false),
                        where.prefixEn() + "unknown config key " + key + " — the " + type
                                + " block does not read it, the setting has no effect" + hint(key, suggestable(fields), true)));
            } else if (field.topLevelOnly() && where.path() != null) {
                out.add(AnalyzerFinding.of("unknown-config-key", Severity.MEDIUM, where.index(), where.name(),
                        where.prefix() + key + " wirkt nur auf oberster Ebene, nicht in einem verschachtelten "
                                + type + "-Block — hier liest ihn niemand",
                        where.prefixEn() + key + " only works on a top-level block, not in a nested " + type
                                + " block — nothing reads it here"));
            } else if (field.legacyFor() != null) {
                out.add(legacyKey(field, cfg, where));
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

        // When nested, topLevelOnly keys drop out of requiredOneOf; if one remains, it is required
        List<String> oneOf = schema.requiredOneOfKeys(type);
        List<String> requiredKeys = new java.util.ArrayList<>(schema.requiredKeys(type));
        if (where.path() != null) {
            List<String> nestedOneOf = oneOf.stream()
                    .filter(key -> fields.get(key) == null || !fields.get(key).topLevelOnly()).toList();
            if (nestedOneOf.size() == 1 && oneOf.size() > 1) {
                requiredKeys.add(nestedOneOf.get(0));
                oneOf = List.of();
            }
        }
        for (String required : requiredKeys) {
            if (cfg.get(required) != null || legacyKeyPresent(fields, required, cfg)) continue;
            String instead = presentInstead(cfg, fields, required);
            out.add(AnalyzerFinding.of("missing-required-config-key", Severity.HIGH, where.index(), where.name(),
                    where.prefix() + "Pflichtschlüssel " + required + " fehlt — ohne ihn kann der " + type
                            + "-Block nicht laufen" + (instead == null ? "" : " (gesetzt ist " + instead + ")"),
                    where.prefixEn() + "required key " + required + " is missing — without it the " + type
                            + " block cannot run" + (instead == null ? "" : " (" + instead + " is set instead)")));
        }
        checkOneOf(oneOf, type, cfg, fields, where, out);
    }

    /** {@code requiredOneOf}: exactly one of the keys - none or several make the block abort. */
    private static void checkOneOf(List<String> oneOf, String type, Map<String, Object> cfg,
                                   Map<String, BlockConfigSchema.Field> fields, Where where, List<AnalyzerFinding> out) {
        // An empty text counts as not set, as in the executor
        long present = oneOf.stream().filter(key -> cfg.get(key) != null
                && !(cfg.get(key) instanceof String text && text.isBlank())).count();
        if (oneOf.isEmpty() || present == 1) return;
        String de = String.join(" oder ", oneOf);
        String en = String.join(" or ", oneOf);
        String instead = present > 0 ? null : oneOf.stream().map(key -> presentInstead(cfg, fields, key))
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        out.add(AnalyzerFinding.of("missing-required-config-key", Severity.HIGH, where.index(), where.name(),
                where.prefix() + (present == 0
                        ? "Pflichtschlüssel " + de + " fehlt — ohne einen davon kann der " + type + "-Block nicht laufen"
                                + (instead == null ? "" : " (gesetzt ist " + instead + ")")
                        : de + " schließen sich aus — nur einer darf gesetzt sein, sonst bricht der " + type
                                + "-Block ab"),
                where.prefixEn() + (present == 0
                        ? "required key " + en + " is missing — without one of them the " + type + " block cannot run"
                                + (instead == null ? "" : " (" + instead + " is set instead)")
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

    /** {@code onlyWith}: every named key has the value - {@code "*"} means: is set. */
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

    /** foreach with items_command: {@link ForEachRules} already reports a wrong items_source value more precisely. */
    private static boolean skipValueCheck(String type, String key, Map<String, Object> cfg) {
        return "foreach".equals(type) && "items_source".equals(key)
                && cfg.get("items_command") instanceof String command && !command.isBlank();
    }

    /** Old key name: still works, being phased out - with the name that replaces it. */
    private static AnalyzerFinding legacyKey(BlockConfigSchema.Field field, Map<String, Object> cfg, Where where) {
        String current = field.legacyFor();
        boolean both = cfg.get(current) != null;
        return AnalyzerFinding.of("legacy-config-key", Severity.LOW, where.index(), where.name(),
                where.prefix() + field.key() + " ist die alte Schreibweise von " + current + " — sie wird noch gelesen, "
                        + "fällt aber später weg" + (both
                        ? "; " + current + " ist auch gesetzt und gilt, " + field.key() + " entfernen"
                        : "; den Schlüssel in " + current + " umbenennen"),
                where.prefixEn() + field.key() + " is the old spelling of " + current + " — still read, but it will "
                        + "be dropped later" + (both
                        ? "; " + current + " is set as well and wins, remove " + field.key()
                        : "; rename the key to " + current));
    }

    /** Whether an old name of the required key is set that the executor still reads ({@code workflowId}). */
    private static boolean legacyKeyPresent(Map<String, BlockConfigSchema.Field> fields, String required,
                                            Map<String, Object> cfg) {
        return fields.values().stream()
                .anyMatch(f -> required.equals(f.legacyFor()) && cfg.get(f.key()) != null);
    }

    /** If an unknown key that resembles it stands in place of the required key, the message names it right away; otherwise {@code null}. */
    private static String presentInstead(Map<String, Object> cfg, Map<String, BlockConfigSchema.Field> fields,
                                         String required) {
        for (String key : cfg.keySet()) {
            if (!fields.containsKey(key) && required.equals(suggestion(key, suggestable(fields)))) {
                return key;
            }
        }
        return null;
    }

    /** Only current names are suggested, never the old spelling of a key. */
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
     * The closest known key: equal up to camelCase/snake_case, otherwise Levenshtein ≤ 2 -
     * stricter for short keys, so that {@code mode} does not become a suggestion for {@code name}.
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
