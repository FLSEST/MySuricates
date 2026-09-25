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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hygiene-Gate fuer den Weg <em>Plattform-Daten → KI-Prompt → Shell-Block</em> in den mitgelieferten
 * Definitionen (Built-in-Templates und {@code workflow-pool/}).
 *
 * <p>Seit TODO 22e6bec8 faerbt die Plattform die Ausgabe eines KI-Blocks, dessen Prompt
 * {@code previous_report}/{@code open_todos} enthielt — ein folgender Shell-Block bekommt
 * {@code {{ previous_output }}} also gequotet. Damit ausgelieferte Definitionen unter beiden
 * Regelwerken gleich laufen (und ein Nutzer, der sie als Vorlage kopiert, die sichere Form sieht),
 * verlangt dieses Gate an genau dieser Stelle das Opt-in {@code "shell_escape_variables": true}.
 *
 * <p>Abgegrenzt von {@link TemplateHygieneGateTest} (quoted Platzhalter, datierte Modell-IDs), damit
 * beide Gates je eine Regel tragen.
 */
class PlatformPromptQuotingGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Felder, die ein Block als Kommandozeile ausfuehrt (identisch zu {@link TemplateHygieneGateTest}). */
    private static final List<String> SHELL_FIELDS =
            List.of("command", "condition", "then_command", "else_command", "items_command", "condition_command", "run_if");

    /** Ausgabe-Platzhalter, die ein Shell-Block von einem Vorgaengerblock uebernimmt. */
    private static final java.util.regex.Pattern OUTPUT_PLACEHOLDER = java.util.regex.Pattern.compile(
            "\\{\\{\\s*(previous_output|step_\\d+_output|block\\.[A-Za-z0-9_-]+\\.output)\\s*}}");

    /** Plattform-Daten, die den Prompt eines KI-Blocks faerben ({@code audit_findings}: TODO 3643beac). */
    private static final java.util.regex.Pattern PLATFORM_PLACEHOLDER =
            java.util.regex.Pattern.compile("\\{\\{\\s*(previous_report|open_todos|audit_findings|todo_details)\\s*}}");

    @Test
    void should_requireEscapeOptIn_forShellBlocksAfterAPlatformPrompt() throws IOException {
        List<String> candidates = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (Path file : definitionFiles()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                scan(variant.root(), false, variant.label(), candidates, violations);
            }
        }

        assertThat(candidates)
                .as("das Muster Plattform-Daten → KI-Prompt → Shell-Block muss in den mitgelieferten "
                        + "Definitionen gefunden worden sein, sonst prueft das Gate nichts")
                .isNotEmpty();
        assertThat(violations)
                .as("Shell-Bloecke, die nach einem KI-Block mit Plattform-Daten einen Ausgabe-Platzhalter "
                        + "verwenden, brauchen \"shell_escape_variables\": true")
                .isEmpty();
    }

    /** Synthetische Gegenprobe: ohne Opt-in schlaegt das Gate an, mit Opt-in nicht. */
    @Test
    void should_flagTheMissingOptIn_andAcceptItWhenPresent() throws IOException {
        String withoutOptIn = """
                {"blocks": [
                  {"type": "simple", "config": {"command": "Bewerte: {{ previous_report }}"}},
                  {"type": "shell", "config": {"command": "echo {{ previous_output }}"}}
                ]}""";
        String withOptIn = """
                {"blocks": [
                  {"type": "simple", "config": {"command": "Bewerte: {{ previous_report }}"}},
                  {"type": "shell", "config": {"command": "echo {{ previous_output }}",
                                               "shell_escape_variables": true}}
                ]}""";
        String withoutPlatformPrompt = """
                {"blocks": [
                  {"type": "simple", "config": {"command": "Fasse den Stand zusammen"}},
                  {"type": "shell", "config": {"command": "echo {{ previous_output }}"}}
                ]}""";

        assertThat(violationsOf(withoutOptIn)).containsExactly("unit-test.json -> command");
        assertThat(violationsOf(withOptIn)).isEmpty();
        assertThat(violationsOf(withoutPlatformPrompt)).isEmpty();
    }

    private static List<String> violationsOf(String json) throws IOException {
        List<String> violations = new ArrayList<>();
        scan(MAPPER.readTree(json), false, "unit-test.json", new ArrayList<>(), violations);
        return violations;
    }

    /**
     * Laeuft die Definition in Ausfuehrungsreihenfolge ab und gibt zurueck, ob danach ein KI-Block
     * mit Plattform-Daten im Prompt gesehen wurde. Das Flag wird in verschachtelte Bloecke
     * (foreach/while/conditional/retry/switch) weitergereicht, weil deren Ausgaben in denselben Run
     * verbucht werden.
     */
    private static boolean scan(JsonNode node, boolean platformPromptSeen, String fileLabel,
                                List<String> candidates, List<String> violations) {
        if (node.isArray()) {
            for (JsonNode child : node) {
                platformPromptSeen = scan(child, platformPromptSeen, fileLabel, candidates, violations);
            }
            return platformPromptSeen;
        }
        if (!node.isObject()) return platformPromptSeen;

        if (isBlock(node)) {
            JsonNode config = node.get("config");
            if (runsAsShell(node)) {
                String field = shellFieldWithOutputPlaceholder(node, config);
                if (field != null && platformPromptSeen) {
                    candidates.add(fileLabel + " -> " + field);
                    if (!config.path("shell_escape_variables").asBoolean(false)) {
                        violations.add(fileLabel + " -> " + field);
                    }
                }
            } else if (PLATFORM_PLACEHOLDER.matcher(config.toString()).find()) {
                platformPromptSeen = true;
            }
        }
        for (JsonNode child : node) {
            platformPromptSeen = scan(child, platformPromptSeen, fileLabel, candidates, violations);
        }
        return platformPromptSeen;
    }

    private static boolean isBlock(JsonNode node) {
        return node.has("type") && node.has("config") && node.get("config").isObject();
    }

    /** Wie {@code ExecutionMode}: Typ {@code shell} oder {@code execution_mode: shell}. */
    private static boolean runsAsShell(JsonNode node) {
        String type = node.path("type").asText(null);
        return "shell".equals(type)
                || "shell".equals(node.get("config").path("execution_mode").asText(null))
                || ("foreach".equals(type) && node.get("config").has("items_command"));
    }

    /** Erstes Shell-Feld dieses Blocks, das einen Ausgabe-Platzhalter einsetzt, sonst {@code null}. */
    private static String shellFieldWithOutputPlaceholder(JsonNode node, JsonNode config) {
        for (String field : SHELL_FIELDS) {
            JsonNode value = config.get(field);
            if (value != null && value.isTextual() && OUTPUT_PLACEHOLDER.matcher(value.asText()).find()) {
                return field;
            }
        }
        return null;
    }

    /** Built-in-Templates immer; {@code workflow-pool/} nur, wenn das Repo-Verzeichnis erreichbar ist. */
    private static List<Path> definitionFiles() throws IOException {
        List<Path> files = new ArrayList<>(ShippedDefinitions.templateFiles());
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

    private static Path firstExistingOrNull(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
