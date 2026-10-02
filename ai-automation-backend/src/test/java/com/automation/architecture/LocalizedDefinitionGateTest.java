package com.automation.architecture;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.BlockNameRules;
import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate for bilingual templates and pool files (TODO a129a661).
 *
 * <p>A file with {@code "i18n": ["de", "en"]} may carry {@code {de, en}} maps in its steps;
 * {@link LocalizedJson} resolves them when a workflow is created. What can go wrong is not the
 * resolver but the content. This gate checks:
 * <ul>
 * <li>every map carries exactly the declared languages, none left blank, and near misses such as a
 *     map with only {@code de} are reported</li>
 * <li>values the platform compares or executes stay single-language: shell fields, {@code type},
 *     {@code variable_name}, {@code auto_answer}, {@code reject_options} …</li>
 * <li>per language variant, block names stay valid and distinct, and every reference resolves:
 *     {@code {{ block.X.output }}}, compress {@code source}, {@code metadata.audit.reportBlock}</li>
 * <li>localized approval {@code options} are not compared anywhere downstream. Otherwise
 *     {@code = 'No'} in a shell block would silently change its meaning in German.</li>
 * </ul>
 */
class LocalizedDefinitionGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Config keys whose values the platform compares, parses or passes on verbatim. */
    static final Set<String> FIXED_CONFIG_KEYS = Set.of(
            "variable_name", "mode", "auto_answer", "reject_options", "on_no", "on_failure", "execution_mode",
            "model", "provider", "effort", "timeout_seconds", "timeout_minutes", "shell_escape_variables",
            "checked_command", "workspace", "input_type", "validation_pattern", "allow_writes");
    static final Set<String> FIXED_BLOCK_KEYS = Set.of("type", "requiredCapabilities", "skillIds");

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern BLOCK_REF = Pattern.compile("\\{\\{\\s*block\\.([A-Za-z0-9_-]+)\\.output\\s*}}");

    @Test
    void shippedBilingualDefinitions_areConsistent() throws IOException {
        List<String> problems = new ArrayList<>();
        int bilingual = 0;
        for (Path file : definitionFiles()) {
            JsonNode root = MAPPER.readTree(file.toFile());
            if (LocalizedJson.isLocalized(root)) {
                bilingual++;
                problems.addAll(problems(root, file.getFileName().toString()));
            }
        }
        assertThat(bilingual).as("mindestens eine zweisprachige Definition (example-approval), sonst prüft das Gate nichts")
                .isPositive();
        assertThat(problems).as("zweisprachige Vorlagen/Pool-Dateien").isEmpty();
    }

    /** Built-in templates are ours: a {de, en} map without the marker would reach a workflow unresolved. */
    @Test
    void builtInTemplatesWithoutMarker_carryNoBilingualMaps() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path file : jsonFiles(builtInDir())) {
            JsonNode root = MAPPER.readTree(file.toFile());
            if (LocalizedJson.isLocalized(root)) {
                continue;
            }
            for (String field : List.of("blocks", "instructions")) {
                walk(root.path(field), node -> {
                    if (looksLikeTranslation(node)) {
                        problems.add(file.getFileName() + ": zweisprachige Map " + node + " ohne \"i18n\"-Marker");
                    }
                });
            }
        }
        assertThat(problems).isEmpty();
    }

    // --- Fixtures: das Gate darf nicht leer grün sein ---

    private static final String GOOD = """
            {"i18n": ["de", "en"],
             "metadata": {"audit": {"reportBlock": "report"}},
             "blocks": [
               {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
               {"type": "compress", "name": "short", "config": {"source": {"de": "messen", "en": "measure"}}},
               {"type": "approval", "name": {"de": "freigabe", "en": "approval"},
                "config": {"question": {"de": "{{ block.messen.output }}?", "en": "{{ block.measure.output }}?"},
                           "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}]}},
               {"type": "simple", "name": "report", "config": {"prompt": {"de": "Bericht", "en": "Report"}}}
             ]}""";

    @Test
    void goodFixture_hasNoProblems() throws IOException {
        assertThat(problems(MAPPER.readTree(GOOD), "good.json")).isEmpty();
    }

    @Test
    void incompleteOrBlankTranslations_areReported() throws IOException {
        String json = """
                {"i18n": ["de", "en"], "blocks": [
                  {"type": "simple", "name": "a", "config": {"prompt": {"de": "Nur deutsch"}}},
                  {"type": "simple", "name": {"de": "b", "en": " "}, "config": {"prompt": {"de": "x", "en": "y"}}}]}""";

        assertThat(problems(MAPPER.readTree(json), "bad.json"))
                .anyMatch(p -> p.contains("unvollständige Übersetzung"))
                .anyMatch(p -> p.contains("leer"));
    }

    @Test
    void localizedShellFieldsAndFixedValues_areReported() throws IOException {
        String json = """
                {"i18n": ["de", "en"], "blocks": [
                  {"type": "shell", "name": "a", "config": {"command": {"de": "echo hallo", "en": "echo hello"}}},
                  {"type": "approval", "name": "b", "config": {"question": {"de": "?", "en": "?"},
                   "auto_answer": {"de": "Ja", "en": "Yes"}}}]}""";

        List<String> problems = problems(MAPPER.readTree(json), "bad.json");

        assertThat(problems).anyMatch(p -> p.contains("command") && p.contains("Shell-Feld"))
                .anyMatch(p -> p.contains("auto_answer"));
    }

    @Test
    void brokenReferencesPerLanguage_areReported() throws IOException {
        String json = """
                {"i18n": ["de", "en"], "metadata": {"audit": {"reportBlock": "report"}}, "blocks": [
                  {"type": "shell", "name": {"de": "messen", "en": "measure"}, "config": {"command": "date"}},
                  {"type": "compress", "name": "c", "config": {"source": "measure"}},
                  {"type": "approval", "name": "q", "config": {"question": {"de": "{{ block.measure.output }}", "en": "{{ block.measure.output }}"}}},
                  {"type": "simple", "name": {"de": "bericht", "en": "report"}, "config": {"prompt": "x"}}]}""";

        List<String> problems = problems(MAPPER.readTree(json), "bad.json");

        assertThat(problems).anyMatch(p -> p.contains("[de]") && p.contains("block.measure.output"))
                .anyMatch(p -> p.contains("[de]") && p.contains("source 'measure'"))
                .anyMatch(p -> p.contains("[de]") && p.contains("reportBlock 'report'"));
    }

    @Test
    void namesThatCollideOrBreakTheRules_areReported() throws IOException {
        String json = """
                {"i18n": ["de", "en"], "blocks": [
                  {"type": "shell", "name": {"de": "schritt", "en": "first"}, "config": {"command": "a"}},
                  {"type": "shell", "name": {"de": "schritt", "en": "second"}, "config": {"command": "b"}},
                  {"type": "shell", "name": {"de": "mit leerzeichen", "en": "previous_output"}, "config": {"command": "c"}}]}""";

        List<String> problems = problems(MAPPER.readTree(json), "bad.json");

        assertThat(problems).anyMatch(p -> p.contains("[de]") && p.contains("doppelt"))
                .anyMatch(p -> p.contains("[de]") && p.contains("ungültig"))
                .anyMatch(p -> p.contains("[en]") && p.contains("reserviert"));
    }

    @Test
    void localizedOptionsComparedDownstream_areReported() throws IOException {
        String json = """
                {"i18n": ["de", "en"], "blocks": [
                  {"type": "approval", "name": "ask", "config": {"question": {"de": "?", "en": "?"},
                   "options": [{"de": "Ja", "en": "Yes"}, {"de": "Nein", "en": "No"}], "variable_name": "decision"}},
                  {"type": "shell", "name": "act", "config": {"command": "[ {{ decision }} = No ] && exit 1"}}]}""";

        assertThat(problems(MAPPER.readTree(json), "bad.json"))
                .anyMatch(p -> p.contains("options") && p.contains("decision"));
    }

    @Test
    void markerWithoutBilingualContent_isReported() throws IOException {
        String json = """
                {"i18n": ["de", "en"], "blocks": [{"type": "shell", "name": "a", "config": {"command": "date"}}]}""";

        assertThat(problems(MAPPER.readTree(json), "bad.json")).anyMatch(p -> p.contains("ohne zweisprachigen Inhalt"));
    }

    // --- Prüflogik ---

    static List<String> problems(JsonNode root, String label) {
        List<String> locales = LocalizedJson.declaredLocales(root);
        List<String> problems = new ArrayList<>();
        if (!LocalizedJson.SUPPORTED.containsAll(locales) || locales.size() < 2) {
            problems.add(label + ": \"i18n\" " + locales + " — erlaubt sind mindestens zwei aus " + LocalizedJson.SUPPORTED);
            return problems;
        }
        int[] maps = {0};
        for (String field : List.of("blocks", "instructions")) {
            checkPositions(root.path(field), locales, label + ":" + field, null, maps, problems);
        }
        walk(root.path("metadata"), node -> {
            if (LocalizedJson.isLocalizedText(node, locales)) {
                problems.add(label + ": metadata wird nicht aufgelöst, enthält aber " + node);
            }
        });
        if (maps[0] == 0) {
            problems.add(label + ": \"i18n\"-Marker ohne zweisprachigen Inhalt in blocks/instructions");
        }
        checkLocalizedOptions(root, locales, label, problems);
        for (String locale : locales) {
            checkVariant(LocalizedJson.resolveDefinition(root, locale), label + "[" + locale + "]", problems);
        }
        return problems;
    }

    /** Walks blocks/instructions; {@code fixedReason} != null means localizing here is not allowed. */
    private static void checkPositions(JsonNode node, List<String> locales, String path, String fixedReason,
                                       int[] maps, List<String> problems) {
        if (node.isObject()) {
            if (LocalizedJson.isLocalizedText(node, locales)) {
                maps[0]++;
                if (fixedReason != null) {
                    problems.add(path + ": lokalisiert, obwohl " + fixedReason);
                }
                node.fields().forEachRemaining(f -> {
                    if (f.getValue().asText().isBlank()) {
                        problems.add(path + "." + f.getKey() + ": leer");
                    }
                });
                return;
            }
            if (looksLikeTranslation(node)) {
                problems.add(path + ": unvollständige Übersetzung " + node + " (erwartet " + locales + ")");
            }
            boolean block = node.has("type") && node.path("config").isObject();
            String type = node.path("type").asText(null);
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                String reason = fixedReason;
                if (block && FIXED_BLOCK_KEYS.contains(f.getKey())) {
                    reason = "'" + f.getKey() + "' wird verglichen";
                }
                if (block && "config".equals(f.getKey())) {
                    JsonNode config = f.getValue();
                    config.fields().forEachRemaining(c -> {
                        String configReason = fixedReason;
                        if (FIXED_CONFIG_KEYS.contains(c.getKey())) {
                            configReason = "'" + c.getKey() + "' wird verglichen oder ausgewertet";
                        } else if (ShippedDefinitions.isShellField(c.getKey(), type, config)) {
                            configReason = "'" + c.getKey() + "' ist ein Shell-Feld (ein Befehl je Sprache hieße doppelte Sicherheitsprüfung)";
                        }
                        checkPositions(c.getValue(), locales, path + ".config." + c.getKey(), configReason, maps, problems);
                    });
                    continue;
                }
                checkPositions(f.getValue(), locales, path + "." + f.getKey(), reason, maps, problems);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                checkPositions(node.get(i), locales, path + "[" + i + "]", fixedReason, maps, problems);
            }
        }
    }

    /** A localized approval answer must not be compared anywhere: in German it would be "Nein", not "No". */
    private static void checkLocalizedOptions(JsonNode root, List<String> locales, String label, List<String> problems) {
        Set<String> variables = new LinkedHashSet<>();
        walk(root.path("blocks"), node -> {
            if ("approval".equals(node.path("type").asText()) && node.path("config").path("options").isArray()) {
                boolean localized = false;
                for (JsonNode option : node.path("config").path("options")) {
                    localized |= LocalizedJson.isLocalizedText(option, locales);
                }
                if (localized) {
                    variables.add(node.path("config").path("variable_name").asText("approval_answer"));
                }
            }
        });
        for (String variable : variables) {
            Pattern use = Pattern.compile("\\{\\{\\s*" + Pattern.quote(variable) + "\\s*}}");
            walk(root.path("blocks"), node -> {
                String type = node.path("type").asText("");
                JsonNode config = node.path("config");
                if (!config.isObject()) {
                    return;
                }
                boolean comparing = "conditional".equals(type) || "switch".equals(type) || "while".equals(type);
                config.fields().forEachRemaining(f -> {
                    boolean shell = ShippedDefinitions.isShellField(f.getKey(), type, config);
                    if ((comparing || shell) && f.getValue().isTextual() && use.matcher(f.getValue().asText()).find()) {
                        problems.add(label + ": lokalisierte options, aber '" + variable + "' wird in "
                                + type + "." + f.getKey() + " verglichen");
                    }
                });
            });
        }
    }

    private static void checkVariant(JsonNode root, String label, List<String> problems) {
        List<String> names = new ArrayList<>();
        List<BlockDefinition> blocks = new ArrayList<>();
        walk(root.path("blocks"), node -> {
            if (node.has("type") && node.path("config").isObject() && node.path("name").isTextual()) {
                names.add(node.path("name").asText());
                blocks.add(BlockDefinition.builder().type(node.path("type").asText()).name(node.path("name").asText()).build());
            }
        });
        Set<String> seen = new HashSet<>();
        for (String name : names) {
            if (!NAME.matcher(name).matches()) {
                problems.add(label + ": Blockname '" + name + "' ungültig (erlaubt: Buchstaben, Ziffern, _ und -)");
            }
            if (!seen.add(name)) {
                problems.add(label + ": Blockname '" + name + "' doppelt");
            }
        }
        try {
            BlockNameRules.rejectReservedNames(blocks);
        } catch (IllegalArgumentException e) {
            problems.add(label + ": reservierter Blockname — " + e.getMessage());
        }
        walk(root.path("blocks"), node -> {
            if (!node.has("type") || !node.path("config").isObject()) {
                return;
            }
            textsOf(node.path("config"), text -> {
                Matcher m = BLOCK_REF.matcher(text);
                while (m.find()) {
                    if (!seen.contains(m.group(1))) {
                        problems.add(label + ": Verweis {{ block." + m.group(1) + ".output }} trifft keinen Block");
                    }
                }
            });
            String type = node.path("type").asText();
            JsonNode source = node.path("config").path("source");
            if (type.startsWith("compress") && source.isTextual() && !"previous".equals(source.asText())
                    && !seen.contains(source.asText())) {
                problems.add(label + ": compress-source '" + source.asText() + "' trifft keinen Block");
            }
        });
        JsonNode reportBlock = root.path("metadata").path("audit").path("reportBlock");
        if (reportBlock.isTextual() && !seen.contains(reportBlock.asText())) {
            problems.add(label + ": metadata.audit.reportBlock '" + reportBlock.asText() + "' trifft keinen Block"
                    + " (der Report-Block darf nicht übersetzt werden)");
        }
    }

    /** An object whose keys are all supported languages and whose values are all strings. */
    private static boolean looksLikeTranslation(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            if (!LocalizedJson.SUPPORTED.contains(f.getKey()) || !f.getValue().isTextual()) {
                return false;
            }
        }
        return true;
    }

    /** Visits every object below {@code node}, including {@code node} itself. */
    private static void walk(JsonNode node, Consumer<JsonNode> visitor) {
        if (node.isObject()) {
            visitor.accept(node);
            node.fields().forEachRemaining(f -> walk(f.getValue(), visitor));
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, visitor));
        }
    }

    private static void textsOf(JsonNode node, Consumer<String> visitor) {
        if (node.isTextual()) {
            visitor.accept(node.asText());
        } else if (node.isContainerNode()) {
            node.forEach(child -> textsOf(child, visitor));
        }
    }

    /** Built-in templates always; workflow-pool/ when the repo directory is reachable. */
    private static List<Path> definitionFiles() throws IOException {
        List<Path> files = new ArrayList<>(ShippedDefinitions.templateFiles());
        Path pool = ShippedDefinitions.poolDirectoryOrNull();
        if (pool != null) {
            files.addAll(ShippedDefinitions.poolFiles(pool));
        }
        return files;
    }

    private static Path builtInDir() {
        for (Path candidate : List.of(Paths.get("src/main/resources/templates/builtin"),
                Paths.get("ai-automation-backend/src/main/resources/templates/builtin"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("templates/builtin nicht gefunden ab " + Paths.get("").toAbsolutePath());
    }

    private static List<Path> jsonFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }
}
