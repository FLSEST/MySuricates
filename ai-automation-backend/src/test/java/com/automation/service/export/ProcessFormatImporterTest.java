package com.automation.service.export;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.service.export.bpmn.BpmnExportAdapter;
import com.automation.service.export.bpmn.BpmnImportAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract of the importer: counterpart to the exporter with the same format key, roundtrip, rejection. */
class ProcessFormatImporterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProcessFormatAdapter exporter = new BpmnExportAdapter(mapper);
    private final ProcessFormatImporter importer = new BpmnImportAdapter(mapper);

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(new LinkedHashMap<>(config));
        return b;
    }

    @Test
    void formatSchluesselPasstZumExporter() {
        assertThat(importer.formatKey()).isEqualTo(exporter.formatKey());
    }

    @Test
    void eigenerExportKommtVerlustfreiZurueck() {
        List<BlockDefinition> blocks = List.of(
                block("shell", "Sammeln", Map.of("command", "ls")),
                block("simple", null, Map.of("command", "Fasse zusammen: {{ previous_output }}")));
        Workflow wf = Workflow.builder().id("wf").name("Rundreise").blocks(blocks).build();

        ImportedWorkflow imported = importer.importFrom(exporter.export(wf));

        assertThat(imported.name()).isEqualTo("Rundreise");
        assertThat(imported.blocks()).extracting(BlockDefinition::getType).containsExactly("shell", "simple");
        assertThat(imported.blocks()).extracting(BlockDefinition::getName).containsExactly("Sammeln", null);
        assertThat(imported.blocks().get(1).getConfig())
                .containsEntry("command", "Fasse zusammen: {{ previous_output }}");
    }

    @Test
    void ungueltigeEingabeWirftIllegalArgument() {
        assertThatThrownBy(() -> importer.importFrom(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> importer.importFrom("<nicht-bpmn/>"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
