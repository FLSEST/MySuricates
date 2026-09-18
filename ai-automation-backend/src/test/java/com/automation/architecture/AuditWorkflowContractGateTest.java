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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate fuer das Audit-Center-Protokoll (docs/audit-workflow-protocol.md) in ausgelieferten
 * Workflow-Definitionen (workflow-pool/).
 *
 * <p>Ein Workflow mit {@code metadata.audit} liefert seine Findings ueber den Envelope
 * {@code mysuricates:audit-result:v1} des Report-Blocks. Deshalb gilt:
 * <ol>
 * <li>Kein gleichzeitiges {@code metadata.autoTodo} — TODOs werden aus den strukturierten
 *     Findings verwaltet, nicht aus Marker-Zeilen (sonst zaehlen beide Pfade doppelt).</li>
 * <li>Der in {@code reportBlock} genannte Block existiert genau einmal mit diesem {@code name};
 *     {@code AuditIngestionService} findet den Report-Block ueber das Step-Metadatum
 *     {@code blockName}.</li>
 * <li>Der Report-Block-Prompt fordert den Envelope an.</li>
 * <li>Keine {@code todo_item:}-Anweisung in irgendeinem Block des Audit-Workflows.</li>
 * </ol>
 */
class AuditWorkflowContractGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENVELOPE_MARKER = "mysuricates:audit-result:v1";
    private static final Pattern TODO_ITEM_LINE = Pattern.compile("(?m)^\\s*todo_item:");

    @Test
    void should_notCombineAuditAndAutoTodo_inShippedAuditWorkflows() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : auditDefinitions()) {
            JsonNode metadata = MAPPER.readTree(file.toFile()).path("metadata");
            if (!metadata.path("autoTodo").isMissingNode()) {
                violations.add(file.getFileName() + " -> metadata.autoTodo neben metadata.audit");
            }
        }
        assertThat(violations)
                .as("Audit-Workflows verwalten TODOs aus Findings, nicht zusaetzlich ueber autoTodo")
                .isEmpty();
    }

    @Test
    void should_declareExistingReportBlock_inShippedAuditWorkflows() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : auditDefinitions()) {
            JsonNode root = MAPPER.readTree(file.toFile());
            String reportBlock = root.path("metadata").path("audit").path("reportBlock").asText("");
            long matches = blocks(root).stream()
                    .filter(block -> reportBlock.equals(block.path("name").asText(null)))
                    .count();
            if (reportBlock.isBlank() || matches != 1) {
                violations.add(file.getFileName() + " -> reportBlock='" + reportBlock + "', passende Bloecke: " + matches);
            }
        }
        assertThat(violations)
                .as("reportBlock muss genau einen Block mit diesem name treffen (Step-Metadatum blockName)")
                .isEmpty();
    }

    @Test
    void should_requestResultEnvelope_inReportBlockPrompt() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : auditDefinitions()) {
            JsonNode root = MAPPER.readTree(file.toFile());
            String reportBlock = root.path("metadata").path("audit").path("reportBlock").asText("");
            blocks(root).stream()
                    .filter(block -> reportBlock.equals(block.path("name").asText(null)))
                    .filter(block -> !block.path("config").path("command").asText("").contains(ENVELOPE_MARKER))
                    .forEach(block -> violations.add(file.getFileName() + " -> Report-Block ohne " + ENVELOPE_MARKER));
        }
        assertThat(violations)
                .as("Der Report-Block muss den Ergebnis-Envelope anfordern")
                .isEmpty();
    }

    @Test
    void should_notInstructTodoItemMarkers_inShippedAuditWorkflows() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : auditDefinitions()) {
            for (JsonNode block : blocks(MAPPER.readTree(file.toFile()))) {
                String command = block.path("config").path("command").asText("");
                if (TODO_ITEM_LINE.matcher(command).find()) {
                    violations.add(file.getFileName() + " -> todo_item-Anweisung in Block '"
                            + block.path("name").asText("(unbenannt)") + "'");
                }
            }
        }
        assertThat(violations)
                .as("Audit-Workflows geben keine todo_item-Zeilen aus (Findings kommen aus dem Envelope)")
                .isEmpty();
    }

    private static List<JsonNode> blocks(JsonNode root) {
        List<JsonNode> result = new ArrayList<>();
        collectBlocks(root, result);
        return result;
    }

    private static void collectBlocks(JsonNode node, List<JsonNode> out) {
        if (node.isObject()) {
            if (node.has("type") && node.path("config").isObject()) {
                out.add(node);
            }
            node.fields().forEachRemaining(entry -> collectBlocks(entry.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(child -> collectBlocks(child, out));
        }
    }

    /** Pool-Definitionen mit {@code metadata.audit.enabled == true}. */
    private static List<Path> auditDefinitions() throws IOException {
        Path pool = firstExisting(Paths.get("../workflow-pool"), Paths.get("workflow-pool"));
        assertThat(pool).as("workflow-pool nicht gefunden ab " + Paths.get("").toAbsolutePath()).isNotNull();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(pool)) {
            for (Path candidate : stream.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                if (MAPPER.readTree(candidate.toFile()).path("metadata").path("audit").path("enabled").asBoolean(false)) {
                    files.add(candidate);
                }
            }
        }
        assertThat(files).as("keine Audit-Definitionen im Pool gefunden").isNotEmpty();
        return files;
    }

    private static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
