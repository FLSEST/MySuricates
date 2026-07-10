package com.automation.service.export.bpmn;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BpmnNamesTest {

    @Test
    void blockNameGewinntImmer() {
        assertThat(BpmnNames.taskName("Maven Tests", "Shell", "mvn test -q")).isEqualTo("Maven Tests");
    }

    @Test
    void ohneNamenLabelPlusErsteZeileGekuerzt() {
        String command = "Analysiere die folgenden Logs und erstelle einen ausführlichen Bericht über alle Fehler\nZeile 2";
        String name = BpmnNames.taskName(null, "KI", command);
        assertThat(name).startsWith("KI: Analysiere die folgenden Logs");
        assertThat(name.length()).isLessThanOrEqualTo(64); // "KI: " + 60
        assertThat(name).endsWith("...");
        assertThat(name).doesNotContain("Zeile 2");
    }

    @Test
    void ohneCommandNurLabel() {
        assertThat(BpmnNames.taskName(null, "Freigabe", null)).isEqualTo("Freigabe");
        assertThat(BpmnNames.taskName("  ", "Freigabe", "  ")).isEqualTo("Freigabe");
    }

    @Test
    void elementIdIstXmlSicherUndStabil() {
        assertThat(BpmnNames.elementId("b_0", "Maven Tests ausführen!")).isEqualTo("b_0_maven_tests_ausf_hren");
        assertThat(BpmnNames.elementId("b_1", null)).isEqualTo("b_1");
        assertThat(BpmnNames.elementId("b_2", "///")).isEqualTo("b_2");
    }
}
