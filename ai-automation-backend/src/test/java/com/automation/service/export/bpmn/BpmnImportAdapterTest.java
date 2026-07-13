package com.automation.service.export.bpmn;

import com.automation.service.export.ImportedWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ablehnung und Härtung des BPMN-Imports (Spec §4): Fremd-BPMN, kaputtes XML, XXE, Größe. */
class BpmnImportAdapterTest {

    private static final String NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String NS_MS = "https://mysuricates.dev/schema/bpmn/1.0";

    private final BpmnImportAdapter importer = new BpmnImportAdapter(new ObjectMapper());

    @Test
    void fremdBpmnOhneErweiterungWirdAbgelehnt() {
        String fremd = """
                <?xml version="1.0"?>
                <definitions xmlns="%s" exporter="Camunda Modeler">
                  <process id="p" name="Fremd"><startEvent id="s"/><task id="t" name="Etwas"/></process>
                </definitions>""".formatted(NS);
        assertThatThrownBy(() -> importer.importFrom(fremd))
                .isInstanceOf(BpmnImportException.class)
                .hasMessageContaining("stammt nicht aus MySuricates");
    }

    @Test
    void leererProzessAusMySuricatesIstGueltigerLeererWorkflow() {
        String leer = """
                <definitions xmlns="%s" xmlns:mysuricates="%s" exporter="MySuricates">
                  <process id="p" name="Leer"><startEvent id="s"/><endEvent id="e"/></process>
                </definitions>""".formatted(NS, NS_MS);
        ImportedWorkflow imported = importer.importFrom(leer);
        assertThat(imported.name()).isEqualTo("Leer");
        assertThat(imported.blocks()).isEmpty();
    }

    @Test
    void kaputtesXmlWirdAbgelehnt() {
        assertThatThrownBy(() -> importer.importFrom("kein xml <<<"))
                .isInstanceOf(BpmnImportException.class)
                .hasMessageContaining("Ungültiges BPMN-XML");
        assertThatThrownBy(() -> importer.importFrom("   "))
                .isInstanceOf(BpmnImportException.class)
                .hasMessageContaining("Ungültiges BPMN-XML");
    }

    @Test
    void ohneProzessWirdAbgelehnt() {
        String xml = "<definitions xmlns=\"%s\" exporter=\"MySuricates\"/>".formatted(NS);
        assertThatThrownBy(() -> importer.importFrom(xml))
                .isInstanceOf(BpmnImportException.class)
                .hasMessage("Keine Prozess-Definition gefunden");
    }

    @Test
    void unlesbareBlockConfigNenntDenBlockIndex() {
        String xml = """
                <definitions xmlns="%s" xmlns:mysuricates="%s" exporter="MySuricates">
                  <process id="p" name="X">
                    <task id="a"><extensionElements><mysuricates:blockConfig type="shell">{"command":"ok"}</mysuricates:blockConfig></extensionElements></task>
                    <task id="b"><extensionElements><mysuricates:blockConfig type="shell">{nicht json</mysuricates:blockConfig></extensionElements></task>
                  </process>
                </definitions>""".formatted(NS, NS_MS);
        assertThatThrownBy(() -> importer.importFrom(xml))
                .isInstanceOf(BpmnImportException.class)
                .hasMessage("Block 2: Konfiguration nicht lesbar");
    }

    @Test
    void doctypeUndExterneEntitiesWerdenAbgelehnt() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE definitions [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <definitions xmlns="%s" xmlns:mysuricates="%s" exporter="MySuricates">
                  <process id="p" name="&xxe;">
                    <task id="a"><extensionElements><mysuricates:blockConfig type="shell">{"command":"ok"}</mysuricates:blockConfig></extensionElements></task>
                  </process>
                </definitions>""".formatted(NS, NS_MS);
        assertThatThrownBy(() -> importer.importFrom(xxe))
                .isInstanceOf(BpmnImportException.class)
                .hasMessageContaining("Ungültiges BPMN-XML");
    }

    @Test
    void zuGrosseEingabeWirdAbgelehnt() {
        String huge = "<definitions>" + "x".repeat(BpmnImportAdapter.MAX_CHARS) + "</definitions>";
        assertThatThrownBy(() -> importer.importFrom(huge))
                .isInstanceOf(BpmnImportException.class)
                .hasMessageContaining("zu groß");
    }

    @Test
    void blockConfigWirdNurAusDirektenKindernDesProzessesGelesen() {
        // Ein blockConfig tief in einem SubProcess-Kind gehört zu keinem Top-Level-Block.
        String xml = """
                <definitions xmlns="%s" xmlns:mysuricates="%s" exporter="MySuricates">
                  <process id="p" name="X">
                    <subProcess id="s"><extensionElements><mysuricates:blockConfig type="foreach">{"command":"outer"}</mysuricates:blockConfig></extensionElements>
                      <task id="inner"><extensionElements><mysuricates:blockConfig type="shell">{"command":"inner"}</mysuricates:blockConfig></extensionElements></task>
                    </subProcess>
                  </process>
                </definitions>""".formatted(NS, NS_MS);
        ImportedWorkflow imported = importer.importFrom(xml);
        assertThat(imported.blocks()).hasSize(1);
        assertThat(imported.blocks().get(0).getType()).isEqualTo("foreach");
    }

    @Test
    void fehlenderProzessnameErhaeltFallback() {
        String xml = """
                <definitions xmlns="%s" xmlns:mysuricates="%s" exporter="MySuricates">
                  <process id="p"><task id="a"><extensionElements><mysuricates:blockConfig type="shell">{}</mysuricates:blockConfig></extensionElements></task></process>
                </definitions>""".formatted(NS, NS_MS);
        assertThat(importer.importFrom(xml).name()).isEqualTo("Importierter Workflow");
    }
}
