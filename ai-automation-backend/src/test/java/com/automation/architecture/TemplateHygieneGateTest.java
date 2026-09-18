package com.automation.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hygiene-Gate fuer Workflow-Definitionen, die mit dem Repo ausgeliefert werden
 * (Built-in-Templates und workflow-pool/).
 *
 * <ol>
 * <li>Output-Platzhalter in Shell-Feldern duerfen nicht in Quotes stehen: in Taint-Runs wird der
 *     Wert bereits single-quoted, eigene Quotes heben das Escaping auf ({@code ''x; id''} fuehrt
 *     {@code id} aus). Korrekte Form: unquoted plus {@code "shell_escape_variables": true},
 *     sonst zerlegt ein mehrzeiliger Output in Runs ohne Taint den Befehl (TODO 1e77e717).
 *     Gleiches gilt fuer {@code previous_report} und {@code open_todos}: sie sind seit TODO c2634caa
 *     immer untrusted und werden in jedem Run single-quoted.</li>
 * <li>Keine datierten Claude-Modell-IDs: der Modell-Katalog liefert Aliase (opus/sonnet/haiku/fable),
 *     datierte IDs veralten und zeigen im UI "nicht im Katalog" (TODO 5770c88c).</li>
 * </ol>
 */
class TemplateHygieneGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SHELL_FIELDS =
            List.of("command", "condition", "then_command", "else_command", "items_command", "condition_command");
    private static final Pattern QUOTED_OUTPUT = Pattern.compile(
            "['\"]\\{\\{\\s*(previous_output|step_\\d+_output|block\\.[A-Za-z0-9_-]+\\.output"
                    + "|previous_report|open_todos)\\s*}}['\"]");
    private static final Pattern DATED_CLAUDE_MODEL = Pattern.compile("claude-(opus|sonnet|haiku|fable)-\\d");

    @Test
    void should_notQuoteOutputPlaceholders_inShellFields() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : definitionFiles()) {
            walkBlocks(MAPPER.readTree(file.toFile()), node -> {
                JsonNode config = node.get("config");
                for (String field : SHELL_FIELDS) {
                    JsonNode value = config.get(field);
                    if (value != null && value.isTextual() && QUOTED_OUTPUT.matcher(value.asText()).find()) {
                        violations.add(file.getFileName() + " -> " + field);
                    }
                }
            });
        }
        assertThat(violations)
                .as("quoted Output-Platzhalter in Shell-Feldern (unquoted + shell_escape_variables verwenden)")
                .isEmpty();
    }

    @Test
    void should_notPinDatedClaudeModelIds() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : definitionFiles()) {
            collectModelFields(MAPPER.readTree(file.toFile()), file, violations);
        }
        assertThat(violations)
                .as("datierte Claude-Modell-IDs (Aliase opus/sonnet/haiku/fable verwenden)")
                .isEmpty();
    }

    private static void collectModelFields(JsonNode node, Path file, List<String> out) {
        if (node.isObject()) {
            JsonNode model = node.get("model");
            if (model != null && model.isTextual() && DATED_CLAUDE_MODEL.matcher(model.asText()).find()) {
                out.add(file.getFileName() + " -> model=" + model.asText());
            }
            node.fields().forEachRemaining(e -> collectModelFields(e.getValue(), file, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectModelFields(child, file, out));
        }
    }

    /** Ruft {@code visitor} fuer jedes Objekt mit "type" und "config" auf (Bloecke, auch verschachtelt). */
    private static void walkBlocks(JsonNode node, Consumer<JsonNode> visitor) {
        if (node.isObject()) {
            if (node.has("type") && node.has("config") && node.get("config").isObject()) {
                visitor.accept(node);
            }
            node.fields().forEachRemaining(e -> walkBlocks(e.getValue(), visitor));
        } else if (node.isArray()) {
            node.forEach(child -> walkBlocks(child, visitor));
        }
    }

    /** Built-in-Templates immer; workflow-pool/ nur, wenn das Repo-Verzeichnis erreichbar ist. */
    private static List<Path> definitionFiles() throws IOException {
        List<Path> files = new ArrayList<>(jsonFiles(firstExisting(
                Paths.get("src/main/resources/templates/builtin"),
                Paths.get("ai-automation-backend/src/main/resources/templates/builtin"))));
        Path pool = firstExistingOrNull(Paths.get("../workflow-pool"), Paths.get("workflow-pool"));
        if (pool != null) {
            files.addAll(jsonFiles(pool));
        }
        assertThat(files).as("keine Definitionen gefunden ab " + Paths.get("").toAbsolutePath()).isNotEmpty();
        return files;
    }

    private static List<Path> jsonFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static Path firstExisting(Path... candidates) {
        Path found = firstExistingOrNull(candidates);
        assertThat(found).as("Verzeichnis nicht gefunden: " + candidates[0]).isNotNull();
        return found;
    }

    private static Path firstExistingOrNull(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
