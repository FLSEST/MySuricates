package com.automation.service.export;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.service.export.bpmn.BpmnExportAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract of the export adapter, checked against the only implementation (BPMN). */
class ProcessFormatAdapterTest {

    private final ProcessFormatAdapter adapter = new BpmnExportAdapter(new ObjectMapper());

    private static Workflow workflow() {
        BlockDefinition b = new BlockDefinition();
        b.setType("shell");
        b.setName("Hallo");
        b.setConfig(Map.of("command", "echo hallo"));
        return Workflow.builder().id("wf").name("Vertrag").blocks(List.of(b)).build();
    }

    @Test
    void metadatenPassenZumFormat() {
        assertThat(adapter.formatKey()).isEqualTo("bpmn");
        assertThat(adapter.fileExtension()).isEqualTo("bpmn").doesNotStartWith(".");
        assertThat(adapter.contentType()).isEqualTo("application/xml");
    }

    @Test
    void exportIstDeterministischUndWohlgeformt() throws Exception {
        String first = adapter.export(workflow());
        String second = adapter.export(workflow());

        assertThat(first).isEqualTo(second);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var doc = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(first.getBytes(StandardCharsets.UTF_8)));
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("definitions");
        assertThat(first).contains("Vertrag").contains("echo hallo");
    }

    @Test
    void exportVerkraftetWorkflowOhneBloecke() {
        Workflow empty = Workflow.builder().id("wf").name("Leer").blocks(null).build();
        assertThat(adapter.export(empty)).contains("startEvent").contains("endEvent");
    }
}
