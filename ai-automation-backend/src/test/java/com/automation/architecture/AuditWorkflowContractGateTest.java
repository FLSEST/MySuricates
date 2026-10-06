package com.automation.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate for the audit center protocol (docs/audit-workflow-protocol.md) in shipped
 * workflow definitions (workflow-pool/).
 *
 * <p>A workflow with {@code metadata.audit} delivers its findings via the envelope
 * {@code mysuricates:audit-result:v1} of the report block. Therefore:
 * <ol>
 * <li>No simultaneous {@code metadata.autoTodo} — TODOs are managed from the structured
 *     findings, not from marker lines (otherwise both paths count twice).</li>
 * <li>The block named in {@code reportBlock} exists exactly once with this {@code name};
 *     {@code AuditIngestionService} finds the report block via the step metadatum
 *     {@code blockName}.</li>
 * <li>The report block prompt requests the envelope.</li>
 * <li>No {@code todo_item:} instruction in any block of the audit workflow.</li>
 * </ol>
 */
class AuditWorkflowContractGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENVELOPE_MARKER = "mysuricates:audit-result:v1";
    private static final Pattern TODO_ITEM_LINE = Pattern.compile("(?m)^\\s*todo_item:");

    @Test
    void should_notCombineAuditAndAutoTodo_inShippedAuditWorkflows() throws IOException {
        List<String> violations = new ArrayList<>();
        for (ShippedDefinitions.Variant variant : auditVariants()) {
            JsonNode metadata = variant.root().path("metadata");
            if (!metadata.path("autoTodo").isMissingNode()) {
                violations.add(variant.label() + " -> metadata.autoTodo neben metadata.audit");
            }
        }
        assertThat(violations)
                .as("Audit-Workflows verwalten TODOs aus Findings, nicht zusaetzlich ueber autoTodo")
                .isEmpty();
    }

    @Test
    void should_declareExistingReportBlock_inShippedAuditWorkflows() throws IOException {
        List<String> violations = new ArrayList<>();
        for (ShippedDefinitions.Variant variant : auditVariants()) {
            JsonNode root = variant.root();
            String reportBlock = root.path("metadata").path("audit").path("reportBlock").asText("");
            long matches = blocks(root).stream()
                    .filter(block -> reportBlock.equals(block.path("name").asText(null)))
                    .count();
            if (reportBlock.isBlank() || matches != 1) {
                violations.add(variant.label() + " -> reportBlock='" + reportBlock + "', passende Bloecke: " + matches);
            }
        }
        assertThat(violations)
                .as("reportBlock muss genau einen Block mit diesem name treffen (Step-Metadatum blockName)")
                .isEmpty();
    }

    @Test
    void should_requestResultEnvelope_inReportBlockPrompt() throws IOException {
        List<String> violations = new ArrayList<>();
        for (ShippedDefinitions.Variant variant : auditVariants()) {
            JsonNode root = variant.root();
            String reportBlock = root.path("metadata").path("audit").path("reportBlock").asText("");
            blocks(root).stream()
                    .filter(block -> reportBlock.equals(block.path("name").asText(null)))
                    .filter(block -> !block.path("config").path("command").asText("").contains(ENVELOPE_MARKER))
                    .forEach(block -> violations.add(variant.label() + " -> Report-Block ohne " + ENVELOPE_MARKER));
        }
        assertThat(violations)
                .as("Der Report-Block muss den Ergebnis-Envelope anfordern")
                .isEmpty();
    }

    @Test
    void should_notInstructTodoItemMarkers_inShippedAuditWorkflows() throws IOException {
        List<String> violations = new ArrayList<>();
        for (ShippedDefinitions.Variant variant : auditVariants()) {
            for (JsonNode block : blocks(variant.root())) {
                String command = block.path("config").path("command").asText("");
                if (TODO_ITEM_LINE.matcher(command).find()) {
                    violations.add(variant.label() + " -> todo_item-Anweisung in Block '"
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

    /** Pool definitions with {@code metadata.audit.enabled == true}. */
    /** Every language variant of every shipped audit definition ({@link ShippedDefinitions}). */
    private static List<ShippedDefinitions.Variant> auditVariants() throws IOException {
        List<ShippedDefinitions.Variant> variants = new ArrayList<>();
        for (Path file : auditDefinitions()) {
            variants.addAll(ShippedDefinitions.variants(file));
        }
        return variants;
    }

    private static List<Path> auditDefinitions() throws IOException {
        Path pool = ShippedDefinitions.poolDirectoryOrNull();
        assertThat(pool).as("workflow-pool nicht gefunden ab " + Paths.get("").toAbsolutePath()).isNotNull();
        List<Path> files = new ArrayList<>();
        for (Path candidate : ShippedDefinitions.poolFiles(pool)) {
            if (MAPPER.readTree(candidate.toFile()).path("metadata").path("audit").path("enabled").asBoolean(false)) {
                files.add(candidate);
            }
        }
        assertThat(files).as("keine Audit-Definitionen im Pool gefunden").isNotEmpty();
        return files;
    }
}
