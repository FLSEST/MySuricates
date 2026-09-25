package com.automation.architecture;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Was MySuricates ausliefert, darf die eigenen Baufehler nicht enthalten (TODO 182bd224).
 *
 * <p>Geprüft werden die Definitionen unter {@code workflow-pool/} und die Built-in-Templates mit
 * demselben deterministischen Regelsatz, den der REST-Endpunkt auf Abruf anwendet. Der Analyzer
 * misst, er bewertet nicht — deshalb ist dieser Test ein Gate und keine Empfehlung.
 *
 * <p>Die Schwelle steht bei {@code HIGH}: Ein Verweis auf einen Block, den es nicht gibt, und ein
 * Pfad mit Klarnamen sind Fehler, die auf einem fremden Rechner sofort auffallen. Leichtere
 * Befunde werden gezählt und ausgegeben, brechen den Build aber nicht — sonst wäre der Test beim
 * ersten Lauf rot und würde stillgelegt statt beachtet.
 */
class PoolAnalyzerGateTest {

    /**
     * Schwere Befunde an Definitionen, deren Reparatur ein eigenes TODO trägt: {@code "<Datei> <Regel>"}
     * → Grund. Nur zum Abbauen — tritt der Befund an der geprüften Datei nicht mehr auf, bricht der Test.
     * Der Schlüssel ist der Dateiname: {@code saas-feature-loop.json} liegt seit 7ddacd1d im Pool
     * (Scope {@code external}) und wird dort weiter geprüft.
     */
    private static final Map<String, String> KNOWN_SEVERE = Map.of(
            "saas-feature-loop.json items-command-without-source",
            "46b9b229 (Built-in-Template saas-feature-loop läuft nicht): foreach kann noch keinen"
                    + " Unter-Workflow ausführen, items_source allein repariert ihn nicht");

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

    /** Die Blöcke einer Pool- oder Template-Datei; beide Formate führen sie unter {@code blocks}. */
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
            out.add(block);
        }
        return out;
    }

    /** Die Metadaten der Definition: {@code metadata.audit} entscheidet, ob {@code audit_findings} belegt wird. */
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
     * Was wirklich ausgeliefert wird: Built-in-Templates liegen im JAR, sind also immer dabei.
     * Aus {@code workflow-pool/} zählen nur die <b>versionierten</b> Dateien — dort liegen daneben
     * lokale Arbeitskopien, die nie veröffentlicht werden (22 von 45 sind getrackt). Ein Gate, das
     * sie mitprüft, meldet Fehler an Dateien, die niemand bekommt.
     */
    private static List<Path> definitionFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        files.addAll(ShippedDefinitions.templateFiles());
        Path pool = firstExistingOrNull(Paths.get("../workflow-pool"), Paths.get("workflow-pool"));
        if (pool != null) files.addAll(trackedJsonFiles(pool));
        assertThat(files).as("keine Definitionen gefunden ab " + Paths.get("").toAbsolutePath()).isNotEmpty();
        return files;
    }

    /** Die versionierten JSON-Dateien eines Verzeichnisses; ohne git bleibt die Liste leer. */
    private static List<Path> trackedJsonFiles(Path dir) throws IOException {
        try {
            Process process = new ProcessBuilder("git", "ls-files", "--", ".")
                    .directory(dir.toFile()).redirectErrorStream(true).start();
            List<Path> tracked = new ArrayList<>();
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.endsWith(".json")) tracked.add(dir.resolve(line.strip()));
                }
            }
            process.waitFor();
            return tracked.stream().filter(Files::isRegularFile).sorted().toList();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private static Path firstExistingOrNull(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }
}
