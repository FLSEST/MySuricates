package com.automation.service.export.bpmn;

import com.automation.entity.Workflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.automation.service.export.bpmn.BpmnXmlRenderer.NS_DI;
import static com.automation.service.export.bpmn.BpmnXmlRenderer.NS_MODEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Datenfluss-Export: aus den Platzhaltern der Block-Configs entstehen Datenobjekte
 * ({@code dataObject} + {@code dataObjectReference}) und Datenassoziationen am erzeugenden
 * bzw. konsumierenden Task.
 */
class BpmnDataFlowExportTest {

    private final BpmnExportAdapter adapter = new BpmnExportAdapter(new ObjectMapper());

    private Document export(Workflow wf) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(adapter.export(wf))));
    }

    private static List<Element> elements(Document doc, String localName) {
        NodeList list = doc.getElementsByTagNameNS(NS_MODEL, localName);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }

    @Test
    void dreiBloeckeErgebenZweiDatenobjekteUndVierAssoziationen() throws Exception {
        Document doc = export(WorkflowFixtures.dataFlow());

        assertThat(elements(doc, "dataObject")).hasSize(2);
        assertThat(elements(doc, "dataObjectReference")).hasSize(2);
        assertThat(elements(doc, "dataOutputAssociation")).hasSize(2);
        assertThat(elements(doc, "dataInputAssociation")).hasSize(2);
    }

    @Test
    void assoziationenHaengenAmRichtigenTask() throws Exception {
        Document doc = export(WorkflowFixtures.dataFlow());
        // tests erzeugt, compress konsumiert + erzeugt, report konsumiert
        assertThat(ownerIdsOf(doc, "dataOutputAssociation")).allSatisfy(id ->
                assertThat(id).matches("b_0_tests|b_1_compress"));
        assertThat(ownerIdsOf(doc, "dataInputAssociation")).allSatisfy(id ->
                assertThat(id).matches("b_1_compress|b_2_report"));
    }

    @Test
    void jedeAssoziationZeigtAufEineExistierendeDatenobjektReferenz() throws Exception {
        Document doc = export(WorkflowFixtures.dataFlow());
        Set<String> refIds = new HashSet<>();
        elements(doc, "dataObjectReference").forEach(e -> refIds.add(e.getAttribute("id")));

        for (Element out : elements(doc, "dataOutputAssociation")) {
            assertThat(refIds).contains(childText(out, "targetRef"));
        }
        for (Element in : elements(doc, "dataInputAssociation")) {
            assertThat(refIds).contains(childText(in, "sourceRef"));
            // targetRef muss auf eine existierende ID zeigen (IDREF) — das property-Platzhalter-Element
            assertThat(childText(in, "targetRef")).isNotBlank();
        }
    }

    @Test
    void jedeDatenobjektReferenzZeigtAufEinDatenobjekt() throws Exception {
        Document doc = export(WorkflowFixtures.dataFlow());
        Set<String> objectIds = new HashSet<>();
        elements(doc, "dataObject").forEach(e -> objectIds.add(e.getAttribute("id")));
        for (Element ref : elements(doc, "dataObjectReference")) {
            assertThat(objectIds).contains(ref.getAttribute("dataObjectRef"));
            assertThat(ref.getAttribute("name")).isNotBlank();
        }
    }

    @Test
    void datenobjekteLiegenUnterhalbDerTaskReihe() throws Exception {
        Document doc = export(WorkflowFixtures.dataFlow());
        double taskBottom = 0;
        double dataTop = Double.MAX_VALUE;
        NodeList shapes = doc.getElementsByTagNameNS(NS_DI, "BPMNShape");
        for (int i = 0; i < shapes.getLength(); i++) {
            Element shape = (Element) shapes.item(i);
            Element bounds = (Element) shape.getElementsByTagNameNS(BpmnXmlRenderer.NS_DC, "Bounds").item(0);
            double y = Double.parseDouble(bounds.getAttribute("y"));
            double h = Double.parseDouble(bounds.getAttribute("height"));
            if (shape.getAttribute("bpmnElement").startsWith("dataref_")) {
                dataTop = Math.min(dataTop, y);
            } else {
                taskBottom = Math.max(taskBottom, y + h);
            }
        }
        assertThat(dataTop).isGreaterThan(taskBottom);
    }

    @Test
    void jedeAssoziationHatEineDiKanteMitWaypoints() throws Exception {
        Document doc = export(WorkflowFixtures.dataFlow());
        Set<String> assocIds = new HashSet<>();
        elements(doc, "dataInputAssociation").forEach(e -> assocIds.add(e.getAttribute("id")));
        elements(doc, "dataOutputAssociation").forEach(e -> assocIds.add(e.getAttribute("id")));

        Set<String> diRefs = new HashSet<>();
        NodeList edges = doc.getElementsByTagNameNS(NS_DI, "BPMNEdge");
        for (int i = 0; i < edges.getLength(); i++) {
            Element edge = (Element) edges.item(i);
            diRefs.add(edge.getAttribute("bpmnElement"));
            assertThat(edge.getElementsByTagNameNS(BpmnXmlRenderer.NS_DDI, "waypoint").getLength())
                    .isGreaterThanOrEqualTo(2);
        }
        assertThat(diRefs).containsAll(assocIds);
    }

    @Test
    void datenflussIstSchemaValide() throws Exception {
        Validator validator = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new File("src/test/resources/bpmn/xsd/BPMN20.xsd"))
                .newValidator();
        String xml = adapter.export(WorkflowFixtures.dataFlow());
        assertThatCode(() -> validator.validate(new StreamSource(new StringReader(xml))))
                .as("XSD-Validierung Datenfluss:\n%s", xml)
                .doesNotThrowAnyException();
    }

    @Test
    void exportIstDeterministisch() {
        assertThat(adapter.export(WorkflowFixtures.dataFlow()))
                .isEqualTo(adapter.export(WorkflowFixtures.dataFlow()));
    }

    @Test
    void ohneDatenflussEntstehenKeineDatenobjekte() throws Exception {
        Workflow ohne = WorkflowFixtures.workflow("Ohne", List.of(
                WorkflowFixtures.block("shell", "a", Map.of("command", "ls")),
                WorkflowFixtures.block("shell", "b", Map.of("command", "pwd"))));
        Document doc = export(ohne);
        assertThat(elements(doc, "dataObject")).isEmpty();
        assertThat(elements(doc, "dataObjectReference")).isEmpty();
        assertThat(elements(doc, "dataInputAssociation")).isEmpty();
        assertThat(elements(doc, "dataOutputAssociation")).isEmpty();
    }

    /** Gateway-Blöcke (conditional/switch) haben keinen einzelnen Aktivitäts-Knoten — kein Datenobjekt. */
    @Test
    void gatewayBloeckeBekommenKeineAssoziation() throws Exception {
        Workflow wf = WorkflowFixtures.workflow("Gate", List.of(
                WorkflowFixtures.block("shell", "tests", Map.of("command", "mvn test")),
                WorkflowFixtures.block("conditional", "pruefung", Map.of(
                        "condition", "{{ block.tests.output }}", "then_command", "a", "else_command", "b",
                        "execution_mode", "shell"))));
        Document doc = export(wf);
        assertThat(elements(doc, "dataInputAssociation")).isEmpty();
        assertThat(elements(doc, "dataOutputAssociation")).isEmpty();
        assertThat(elements(doc, "dataObjectReference")).isEmpty();
    }

    private static List<String> ownerIdsOf(Document doc, String localName) {
        List<String> owners = new ArrayList<>();
        for (Element assoc : elements(doc, localName)) {
            owners.add(((Element) assoc.getParentNode()).getAttribute("id"));
        }
        return owners;
    }

    private static String childText(Element parent, String localName) {
        NodeList list = parent.getElementsByTagNameNS(NS_MODEL, localName);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent().strip();
    }
}
