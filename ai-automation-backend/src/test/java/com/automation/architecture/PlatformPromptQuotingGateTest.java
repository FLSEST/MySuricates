package com.automation.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hygiene gate for the path <em>platform data → AI prompt → shell block</em> in the shipped
 * definitions (built-in templates and {@code workflow-pool/}).
 *
 * <p>Since TODO 22e6bec8 the platform taints the output of an AI block whose prompt
 * contained {@code previous_report}/{@code open_todos} — a following shell block therefore gets
 * {@code {{ previous_output }}} quoted. So that shipped definitions run the same under both
 * rule sets (and a user who copies them as a template sees the safe form),
 * this gate demands the opt-in {@code "shell_escape_variables": true} at exactly this place.
 *
 * <p>Distinguished from {@link TemplateHygieneGateTest} (quoted placeholders, dated model IDs), so that
 * both gates carry one rule each.
 */
class PlatformPromptQuotingGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Fields that a block executes as a command line (identical to {@link TemplateHygieneGateTest}). */
    private static final List<String> SHELL_FIELDS =
            List.of("command", "condition", "then_command", "else_command", "items_command", "condition_command", "run_if");

    /** Output placeholders that a shell block takes over from a predecessor block. */
    private static final java.util.regex.Pattern OUTPUT_PLACEHOLDER = java.util.regex.Pattern.compile(
            "\\{\\{\\s*(previous_output|step_\\d+_output|block\\.[A-Za-z0-9_-]+\\.output)\\s*}}");

    /** Platform data that taints the prompt of an AI block ({@code audit_findings}: TODO 3643beac). */
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

    /** Synthetic counter-check: without the opt-in the gate trips, with the opt-in it does not. */
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
     * Walks the definition in execution order and returns whether an AI block
     * with platform data in its prompt was seen afterwards. The flag is passed on into nested blocks
     * (foreach/while/conditional/retry/switch) because their outputs are booked into the same
     * run.
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

    /** Like {@code ExecutionMode}: type {@code shell} or {@code execution_mode: shell}. */
    private static boolean runsAsShell(JsonNode node) {
        String type = node.path("type").asText(null);
        return "shell".equals(type)
                || "shell".equals(node.get("config").path("execution_mode").asText(null))
                || ("foreach".equals(type) && node.get("config").has("items_command"));
    }

    /** First shell field of this block that inserts an output placeholder, otherwise {@code null}. */
    private static String shellFieldWithOutputPlaceholder(JsonNode node, JsonNode config) {
        for (String field : SHELL_FIELDS) {
            JsonNode value = config.get(field);
            if (value != null && value.isTextual() && OUTPUT_PLACEHOLDER.matcher(value.asText()).find()) {
                return field;
            }
        }
        return null;
    }

    /** Built-in templates always; {@code workflow-pool/} only if the repo directory is reachable. */
    private static List<Path> definitionFiles() throws IOException {
        List<Path> files = new ArrayList<>(ShippedDefinitions.templateFiles());
        Path pool = ShippedDefinitions.poolDirectoryOrNull();
        if (pool != null) {
            files.addAll(ShippedDefinitions.poolFiles(pool));
        }
        assertThat(files).as("keine Definitionen gefunden ab " + Paths.get("").toAbsolutePath()).isNotEmpty();
        return files;
    }
}
