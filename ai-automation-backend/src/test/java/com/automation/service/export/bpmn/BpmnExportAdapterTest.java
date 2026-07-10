package com.automation.service.export.bpmn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class BpmnExportAdapterTest {

    private final BpmnExportAdapter adapter = new BpmnExportAdapter(new ObjectMapper());

    @Test
    void metadatenSindKorrekt() {
        assertThat(adapter.formatKey()).isEqualTo("bpmn");
        assertThat(adapter.fileExtension()).isEqualTo("bpmn");
        assertThat(adapter.contentType()).isEqualTo("application/xml");
    }

    @Test
    void exportLiefertVollstaendigesBpmn() {
        String xml = adapter.export(WorkflowFixtures.linear());
        assertThat(xml).startsWith("<?xml");
        assertThat(xml).contains("<definitions");
        assertThat(xml).contains("BPMNDiagram");
        assertThat(xml).contains("name=\"Linear Demo\"");
    }

    @Test
    void exportIstDeterministisch() {
        assertThat(adapter.export(WorkflowFixtures.linear()))
                .isEqualTo(adapter.export(WorkflowFixtures.linear()));
    }

    @Test
    void goldenFileLinearBleibtStabil() throws Exception {
        Path golden = Path.of("src/test/resources/bpmn/golden/linear.bpmn");
        String actual = adapter.export(WorkflowFixtures.linear());
        if (!Files.exists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual);
            fail("Golden-File neu erzeugt — prüfen (z. B. unter https://demo.bpmn.io öffnen) und committen: " + golden);
        }
        assertThat(actual).isEqualTo(Files.readString(golden));
    }
}
