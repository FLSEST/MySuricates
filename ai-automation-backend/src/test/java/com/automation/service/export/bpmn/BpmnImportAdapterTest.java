package com.automation.service.export.bpmn;

import com.automation.service.export.ImportedWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rejection and hardening of the BPMN import (spec §4): foreign BPMN, broken XML, XXE, size. */
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
                .hasMessageContaining("keine mysuricates:blockConfig-Erweiterung");
    }

    /**
     * The format marker is not proof of origin: a foreign file that simply writes {@code exporter} and
     * namespace along is accepted. This test records the current state
     * so the docs do not overstate it — a real origin check would need a
     * checksum over the export (TODO b18939a6).
     */
    @Test
    void gefaelschtesFormatmerkmalWirdNichtErkannt() {
        String gefaelscht = """
                <definitions xmlns="%s" xmlns:mysuricates="%s" exporter="MySuricates">
                  <process id="p" name="Fremd mit Marker"><startEvent id="s"/><endEvent id="e"/></process>
                </definitions>""".formatted(NS, NS_MS);
        ImportedWorkflow imported = importer.importFrom(gefaelscht);
        assertThat(imported.name()).isEqualTo("Fremd mit Marker");
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
        // A blockConfig deep inside a SubProcess child belongs to no top-level block.
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
