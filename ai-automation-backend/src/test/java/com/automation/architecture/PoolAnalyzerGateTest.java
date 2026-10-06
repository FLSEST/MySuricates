package com.automation.architecture;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What MySuricates ships must not contain its own construction errors (TODO 182bd224).
 *
 * <p>The definitions under {@code workflow-pool/} and the built-in templates are checked with
 * the same deterministic rule set that the REST endpoint applies on demand — since f5153c47
 * including the config keys of nested blocks (switch cases, {@code default}, {@code blocks}), since
 * d3aa0b28 including the fields next to {@code config} ({@code unknown-block-field}). The
 * analyzer measures, it does not judge — that is why this test is a gate and not a recommendation.
 *
 * <p>The threshold is at {@code HIGH}: a reference to a block that does not exist and a
 * path with a real name are errors that are noticed immediately on someone else's machine. Lighter
 * findings are counted and printed but do not break the build — otherwise the test would be red on its
 * first run and be shut down instead of heeded.
 */
class PoolAnalyzerGateTest {

    /**
     * Severe findings on definitions whose repair has its own TODO: {@code "<file> <rule>"}
     * → reason. Only for phasing out — if the finding no longer occurs on the checked file, the test breaks.
     * Currently empty: {@code saas-feature-loop.json} had two entries here until foreach with
     * {@code workflow_id} could start a sub-workflow per entry (a5c9fbbf).
     */
    private static final Map<String, String> KNOWN_SEVERE = Map.of();

    private final WorkflowAnalyzer analyzer = new WorkflowAnalyzer();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void ausgelieferteDefinitionenTragenKeineSchwerenBaufehler() throws IOException {
        List<String> severe = new ArrayList<>();
        Set<String> known = new TreeSet<>();
        Set<String> labels = new TreeSet<>();
        Map<String, Integer> byRule = new TreeMap<>();
        int checked = 0;

        for (Path file : definitionFiles()) {
            for (ShippedDefinitions.Variant variant : ShippedDefinitions.variants(file)) {
                JsonNode root = variant.root();
                List<BlockDefinition> blocks = blocksOf(root);
                if (blocks.isEmpty()) continue;
                checked++;
                labels.add(variant.label());

                String instructions = root.path("instructions").isTextual()
                        ? root.path("instructions").asText() : null;
                for (AnalyzerFinding finding : analyzer.analyze(blocks, instructions, metadataOf(root))) {
                    byRule.merge(finding.rule(), 1, Integer::sum);
                    if (finding.rule().endsWith("config-key") || finding.rule().equals("invalid-config-value")) {
                        System.out.println("  " + variant.label() + " [Block " + finding.blockIndex() + "] "
                                + finding.severity() + " " + finding.rule() + ": " + finding.detail());
                    }
                    if (KNOWN_SEVERE.containsKey(variant.label() + " " + finding.rule())) {
                        known.add(variant.label() + " " + finding.rule());
                    } else if (finding.severity() == AnalyzerFinding.Severity.HIGH) {
                        severe.add(variant.label() + " [Block " + finding.blockIndex() + "] "
                                + finding.rule() + ": " + finding.detail());
                    }
                }
            }
        }

        assertThat(checked).as("keine Definitionen mit Blöcken gefunden").isPositive();
        System.out.println("Analyzer über " + checked + " ausgelieferte Definitionen: " + byRule);
        assertThat(severe).as("schwere Baufehler in ausgelieferten Definitionen").isEmpty();
        assertThat(KNOWN_SEVERE.keySet().stream()
                .filter(key -> labels.contains(key.substring(0, key.indexOf(' '))) && !known.contains(key)))
                .as("behoben — aus KNOWN_SEVERE streichen").isEmpty();
    }

    /** The blocks of a pool or template file; both formats keep them under {@code blocks}. */
    private List<BlockDefinition> blocksOf(JsonNode root) {
        JsonNode blocks = root.path("blocks");
        if (!blocks.isArray()) {
            blocks = root.path("definition").path("blocks");
        }
        List<BlockDefinition> out = new ArrayList<>();
        if (!blocks.isArray()) return out;
        for (JsonNode node : blocks) {
            BlockDefinition block = new BlockDefinition();
            block.setType(node.path("type").asText(null));
            block.setName(node.path("name").isTextual() ? node.path("name").asText() : null);
            block.setConfig(configOf(node.path("config")));
            // Since d3aa0b28 BlockDefinition keeps fields next to config — the gate sees them like the save path
            node.properties().forEach(field -> {
                if (!BlockDefinition.FIELDS.contains(field.getKey())) {
                    block.putUnknownField(field.getKey(), objectMapper.convertValue(field.getValue(), Object.class));
                }
            });
            out.add(block);
        }
        return out;
    }

    /** The definition's metadata: {@code metadata.audit} decides whether {@code audit_findings} is set. */
    private Map<String, Object> metadataOf(JsonNode root) {
        JsonNode metadata = root.path("metadata");
        return configOf(metadata.isObject() ? metadata : root.path("definition").path("metadata"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> configOf(JsonNode config) {
        if (!config.isObject()) return new HashMap<>();
        return objectMapper.convertValue(config, Map.class);
    }

    /**
     * What is really shipped: built-in templates live in the JAR, so they are always included.
     * From {@code workflow-pool/} only the <b>versioned</b> files count — next to them lie
     * local working copies that are never published (22 of 45 are tracked). A gate that
     * checks them too reports errors on files that nobody gets.
     */
    private static List<Path> definitionFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        files.addAll(ShippedDefinitions.templateFiles());
        Path pool = ShippedDefinitions.poolDirectoryOrNull();
        if (pool != null) files.addAll(ShippedDefinitions.poolFiles(pool));
        assertThat(files).as("keine Definitionen gefunden ab " + Paths.get("").toAbsolutePath()).isNotEmpty();
        return files;
    }
}
