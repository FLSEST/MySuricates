package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.service.export.ImportedWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roundtrip-Beweis (Spec §5): Export → Import ergibt exakt die ursprüngliche Block-Liste
 * (Typ, Name, Config) — über den gesamten Fixture-Katalog inkl. verschachtelter Switch-Cases.
 */
class BpmnRoundtripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BpmnExportAdapter exporter = new BpmnExportAdapter(objectMapper);
    private final BpmnImportAdapter importer = new BpmnImportAdapter(objectMapper);

    static Stream<Workflow> fixtures() {
        return WorkflowFixtures.roundtripCatalog().stream();
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void roundtripErhaeltNameTypUndConfigJedesBlocks(Workflow wf) {
        String xml = exporter.export(wf);
        ImportedWorkflow imported = importer.importFrom(xml);

        assertThat(imported.name()).isEqualTo(wf.getName());
        assertBlocksEqual(imported.blocks(), wf.getBlocks());
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void doppelterRoundtripIstStabil(Workflow wf) {
        // Export(Import(Export(wf))) muss byte-identisch zu Export(wf) sein (Idempotenz).
        String first = exporter.export(wf);
        ImportedWorkflow imported = importer.importFrom(first);
        Workflow again = WorkflowFixtures.workflow(imported.name(), imported.blocks());
        assertThat(exporter.export(again)).isEqualTo(first);
    }

    @Test
    void goldenFileLinearEntsprichtDerFixture() throws Exception {
        String golden = Files.readString(Path.of("src/test/resources/bpmn/golden/linear.bpmn"), StandardCharsets.UTF_8);
        ImportedWorkflow imported = importer.importFrom(golden);
        Workflow expected = WorkflowFixtures.linear();
        assertThat(imported.name()).isEqualTo(expected.getName());
        assertBlocksEqual(imported.blocks(), expected.getBlocks());
    }

    @Test
    void namenloserBlockKommtOhneNameZurueck() {
        Workflow wf = WorkflowFixtures.workflow("N", List.of(WorkflowFixtures.block("shell", null, Map.of("command", "ls"))));
        BlockDefinition restored = importer.importFrom(exporter.export(wf)).blocks().get(0);
        assertThat(restored.getName()).isNull(); // NICHT der abgeleitete "Shell: ls"-Text
    }

    /** Datenobjekte sind reine Darstellung — der Import überliest sie, die Config bleibt Quelle der Wahrheit. */
    @Test
    void datenobjekteWerdenBeimImportIgnoriert() {
        Workflow wf = WorkflowFixtures.dataFlow();
        String xml = exporter.export(wf);
        assertThat(xml).contains("dataObjectReference");
        assertThat(xml).contains("dataInputAssociation");

        ImportedWorkflow imported = importer.importFrom(xml);
        assertThat(imported.blocks()).hasSize(3);
        assertBlocksEqual(imported.blocks(), wf.getBlocks());
    }

    @Test
    void switchCaseInhalteWerdenNichtDoppeltGezaehlt() {
        Workflow wf = WorkflowFixtures.workflow("S", List.of(
                WorkflowFixtures.block("switch", null, Map.of("expression", "{{ x }}",
                        "cases", List.of(Map.of("value", "1", "blocks", List.of(
                                Map.of("type", "shell", "config", Map.of("command", "a")),
                                Map.of("type", "foreach", "config", Map.of("variable_name", "i", "command", "b"))))))),
                WorkflowFixtures.block("shell", null, Map.of("command", "c"))));
        String xml = exporter.export(wf);
        assertThat(xml.split("mysuricates:blockConfig ", -1).length - 1).as("blockConfig-Elemente").isEqualTo(2);
        assertThat(importer.importFrom(xml).blocks()).hasSize(2);
    }

    private void assertBlocksEqual(List<BlockDefinition> restored, List<BlockDefinition> original) {
        assertThat(restored).hasSameSizeAs(original);
        for (int i = 0; i < original.size(); i++) {
            BlockDefinition o = original.get(i);
            BlockDefinition r = restored.get(i);
            assertThat(r.getType()).as("Block %d Typ", i).isEqualTo(o.getType());
            assertThat(nullIfBlank(r.getName())).as("Block %d Name", i).isEqualTo(nullIfBlank(o.getName()));
            // Config über JSON normalisiert vergleichen (gleiche Zahlentypen wie nach dem Reimport)
            assertThat(r.getConfig()).as("Block %d Config", i).isEqualTo(normalize(o.getConfig()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> config) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(config), Map.class);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
