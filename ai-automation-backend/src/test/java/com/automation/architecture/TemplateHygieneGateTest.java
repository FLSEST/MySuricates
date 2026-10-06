package com.automation.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hygiene gate for workflow definitions that ship with the repo
 * (built-in templates and workflow-pool/).
 *
 * <ol>
 * <li>Output placeholders in shell fields must not be inside quotes: in taint runs the
 *     value is already single-quoted, own quotes cancel the escaping ({@code ''x; id''} executes
 *     {@code id}). Correct form: unquoted plus {@code "shell_escape_variables": true},
 *     otherwise a multi-line output in runs without taint breaks the command (TODO 1e77e717).
 *     The same applies to {@code previous_report} and {@code open_todos}: since TODO c2634caa they are
 *     always untrusted and are single-quoted in every run — likewise {@code audit_findings} in
 *     audit workflows (TODO 3643beac).</li>
 * <li>No dated Claude model IDs: the model catalog delivers aliases (opus/sonnet/haiku/fable),
 *     dated IDs go stale and show "nicht im Katalog" in the UI (TODO 5770c88c).</li>
 * <li>No {@code $(grep -c … || echo 0)} in shell fields: with zero matches grep itself writes
 *     "0" and exits with 1, the {@code echo} appends a second line. The value {@code "0\n0"} makes
 *     every following {@code [ … -gt … ]} fail — in the architecture review this left class coupling
 *     unmeasured hundreds of times (TODO 0c4b4eb5). Correct: {@code x=$(grep -c …); x=${x:-0}}.</li>
 * </ol>
 */
class TemplateHygieneGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern QUOTED_OUTPUT = Pattern.compile(
            "['\"]\\{\\{\\s*(previous_output|step_\\d+_output|block\\.[A-Za-z0-9_-]+\\.output"
                    + "|previous_report|open_todos|audit_findings|todo_details)\\s*}}['\"]");
    private static final Pattern DATED_CLAUDE_MODEL = Pattern.compile("claude-(opus|sonnet|haiku|fable)-\\d");
    private static final Pattern GREP_COUNT_ECHO_FALLBACK = Pattern.compile(
            "grep\\s+(?:-[A-Za-z]*c[A-Za-z]*|--count)\\s[^\\n|]*\\|\\|\\s*echo\\b");

    @Test
    void should_notQuoteOutputPlaceholders_inShellFields() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : definitionFiles()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                walkBlocks(variant.root(),
                        node -> collectQuotedOutputViolations(node, variant.label(), violations));
            }
        }
        assertThat(violations)
                .as("quoted Output-Platzhalter in Shell-Feldern (unquoted + shell_escape_variables verwenden)")
                .isEmpty();
    }

    /**
     * A simple block with an output placeholder in {@code command} is not a false positive: the
     * field is a Claude prompt there ({@code SimpleBlockExecutor} resolves it via
     * {@code resolveTemplate}, never through a shell), so quoting it changes nothing about shell
     * injection. Only blocks that actually run {@code command}/{@code condition}/… through a shell
     * (type {@code shell}, or a foreach/while/conditional/retry/switch-nested block with
     * {@code execution_mode: shell} — see {@link com.automation.executor.ExecutionMode}; {@code
     * items_command} on {@code foreach} always runs as shell, see {@code ForEachBlockExecutor})
     * must be checked (TODO aeac3bb1).
     */
    @Test
    void should_ignoreShellFields_onBlockTypesThatDoNotRunThemAsShell() {
        ObjectNode simpleBlock = MAPPER.createObjectNode();
        simpleBlock.put("type", "simple");
        simpleBlock.putObject("config").put("command", "Summarize '{{ previous_output }}' briefly.");

        List<String> simpleViolations = new ArrayList<>();
        collectQuotedOutputViolations(simpleBlock, "unit-test.json", simpleViolations);
        assertThat(simpleViolations)
                .as("simple block 'command' is an AI prompt, not a shell field")
                .isEmpty();

        ObjectNode shellBlock = MAPPER.createObjectNode();
        shellBlock.put("type", "shell");
        shellBlock.putObject("config").put("command", "echo '{{ previous_output }}'");

        List<String> shellViolations = new ArrayList<>();
        collectQuotedOutputViolations(shellBlock, "unit-test.json", shellViolations);
        assertThat(shellViolations)
                .as("an actual shell block with a quoted output placeholder must still be flagged")
                .containsExactly("unit-test.json -> command");
    }

    /** Visits {@code node} itself (not its nested blocks — the caller's {@link #walkBlocks} does
     * the recursion) and records a violation per quoted-output {@link ShippedDefinitions#SHELL_FIELDS} entry that is
     * actually executed as shell for this block. */
    private static void collectQuotedOutputViolations(JsonNode node, String fileLabel, List<String> out) {
        collectShellViolations(node, fileLabel, QUOTED_OUTPUT, out);
    }

    private static void collectShellViolations(JsonNode node, String fileLabel, Pattern forbidden, List<String> out) {
        JsonNode config = node.get("config");
        String type = node.path("type").asText(null);
        for (String field : ShippedDefinitions.SHELL_FIELDS) {
            if (!ShippedDefinitions.isShellField(field, type, config)) {
                continue;
            }
            JsonNode value = config.get(field);
            if (value != null && value.isTextual() && forbidden.matcher(value.asText()).find()) {
                out.add(fileLabel + " -> " + field);
            }
        }
    }

    @Test
    void should_notPinDatedClaudeModelIds() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : definitionFiles()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                collectModelFields(variant.root(), variant.label(), violations);
            }
        }
        assertThat(violations)
                .as("datierte Claude-Modell-IDs (Aliase opus/sonnet/haiku/fable verwenden)")
                .isEmpty();
    }

    private static void collectModelFields(JsonNode node, String label, List<String> out) {
        if (node.isObject()) {
            JsonNode model = node.get("model");
            if (model != null && model.isTextual() && DATED_CLAUDE_MODEL.matcher(model.asText()).find()) {
                out.add(label + " -> model=" + model.asText());
            }
            node.fields().forEachRemaining(e -> collectModelFields(e.getValue(), label, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectModelFields(child, label, out));
        }
    }

    @Test
    void should_notFallBackToEcho_afterGrepCount_inShellFields() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : definitionFiles()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                walkBlocks(variant.root(), node -> collectShellViolations(
                        node, variant.label(), GREP_COUNT_ECHO_FALLBACK, violations));
            }
        }
        assertThat(violations)
                .as("grep -c … || echo ergibt bei null Treffern \"0\\n0\" (x=$(grep -c …); x=${x:-0} verwenden)")
                .isEmpty();
    }

    @Test
    void should_flagGrepCountEchoFallback_butNotTheSafeForm() {
        assertThat(GREP_COUNT_ECHO_FALLBACK.matcher("n=$(grep -c 'x' \"$f\" 2>/dev/null || echo 0)").find()).isTrue();
        assertThat(GREP_COUNT_ECHO_FALLBACK.matcher("n=$(grep -cE '^a:' \"$f\" || echo 0)").find()).isTrue();
        assertThat(GREP_COUNT_ECHO_FALLBACK.matcher("n=$(grep -c 'x' \"$f\" 2>/dev/null); n=${n:-0}").find()).isFalse();
        assertThat(GREP_COUNT_ECHO_FALLBACK.matcher("grep -rn 'x' . || echo '(keine)'").find()).isFalse();
    }

    /** Calls {@code visitor} for every object with "type" and "config" (blocks, also nested). */
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

    /** Built-in templates always; workflow-pool/ only if the repo directory is reachable. */
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
