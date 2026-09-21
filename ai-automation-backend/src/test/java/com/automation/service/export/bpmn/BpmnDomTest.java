package com.automation.service.export.bpmn;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BpmnDomTest {

    private static final String NS = "urn:a";

    @Test
    void parstNamespaceAwareUndLiefertNurElementKinder() throws Exception {
        Document doc = BpmnDom.secureParse("""
                <root xmlns="urn:a" xmlns:b="urn:b">
                  text<!-- kommentar --><child id="1"/><b:child id="2"/><child id="3"/>
                </root>""");

        Element root = doc.getDocumentElement();
        assertThat(BpmnDom.childElements(root)).extracting(e -> e.getAttribute("id"))
                .containsExactly("1", "2", "3");
        assertThat(BpmnDom.firstChild(root, "urn:b", "child").getAttribute("id")).isEqualTo("2");
        assertThat(BpmnDom.firstChild(root, NS, "child").getAttribute("id")).isEqualTo("1");
        assertThat(BpmnDom.firstChild(root, NS, "fehlt")).isNull();
        assertThat(BpmnDom.firstChild(root, "urn:c", "child")).isNull();
    }

    @Test
    void firstChildSuchtNurDirekteKinder() throws Exception {
        Element root = BpmnDom.secureParse("<r xmlns=\"urn:a\"><x><y/></x></r>").getDocumentElement();
        assertThat(BpmnDom.firstChild(root, NS, "y")).isNull();
    }

    @Test
    void doctypeWirdAbgelehntXxe() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <r>&x;</r>""";
        assertThatThrownBy(() -> BpmnDom.secureParse(xxe)).hasMessageContaining("DOCTYPE");
    }

    @Test
    void kaputtesXmlWirft() {
        assertThatThrownBy(() -> BpmnDom.secureParse("<r><offen></r>"))
                .isInstanceOf(org.xml.sax.SAXParseException.class);
    }
}
