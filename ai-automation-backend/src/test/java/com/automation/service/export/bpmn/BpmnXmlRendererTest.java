package com.automation.service.export.bpmn;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class BpmnXmlRendererTest {

    private static final Set<String> FLOW_NODE_NAMES = Set.of(
            "startEvent", "endEvent", "task", "serviceTask", "scriptTask", "userTask",
            "exclusiveGateway", "subProcess", "callActivity");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String render(com.automation.entity.Workflow wf) {
        return new BpmnXmlRenderer().render(new BpmnModelBuilder(objectMapper).build(wf));
    }

    private List<com.automation.entity.Workflow> alleFixtures() {
        return List.of(
                WorkflowFixtures.linear(),
                WorkflowFixtures.workflow("Leer", List.of()),
                WorkflowFixtures.workflow("Cond", List.of(WorkflowFixtures.block("conditional", null, Map.of(
                        "condition", "Fehler?", "then_command", "Analysiere", "else_command", "Weiter",
                        "execution_mode", "shell")))),
                WorkflowFixtures.workflow("Loop", List.of(WorkflowFixtures.block("foreach", null, Map.of(
                        "variable_name", "item", "command", "Verarbeite {{ item }}")))),
                WorkflowFixtures.workflow("Mix", List.of(
                        WorkflowFixtures.block("shell", null, Map.of("command", "ls -la")),
                        WorkflowFixtures.block("switch", null, Map.of(
                                "expression", "{{ gate }}",
                                "cases", List.of(Map.of("value", "RED", "blocks", List.of(
                                        Map.of("type", "shell", "config", Map.of("command", "echo alarm"))))))),
                        WorkflowFixtures.block("sub-workflow", "Unterprozess", Map.of("workflow_id", "wf-child-1")))),
                WorkflowFixtures.workflow("While", List.of(WorkflowFixtures.block("while", null, Map.of(
                        "command", "npm test 2>&1 | tail -5",
                        "condition_command", "npm test > /dev/null || echo weiter",
                        "execution_mode", "shell")))),
                WorkflowFixtures.workflow("Unbekannt", List.of(WorkflowFixtures.block("quantum", null, Map.of(
                        "command", "irgendwas")))),
                WorkflowFixtures.workflow("Sonderzeichen", List.of(WorkflowFixtures.block("shell", "A & B <C>", Map.of(
                        "command", "if [ $x -lt 5 ] && echo \"<ok>\" || echo 'A & B'")))));
    }

    @Test
    void xmlSonderzeichenWerdenKorrektEscaped() {
        String xml = render(WorkflowFixtures.workflow("Sonderzeichen", List.of(
                WorkflowFixtures.block("shell", "A & B <C>", Map.of(
                        "command", "grep 'x' && echo \"<ok>\"")))));
        // Roh-Zeichen dürfen nicht unescaped im Dokument stehen …
        assertThat(xml).doesNotContain("<ok>");
        assertThat(xml).doesNotContain("A & B");
        // … sondern als Entities (das XML bleibt wohlgeformt, s. alleFixturesSindSchemaValide)
        assertThat(xml).contains("&amp;");
        assertThat(xml).contains("&lt;ok&gt;");
    }

    @Test
    void alleFixturesSindSchemaValide() throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        Validator validator = factory
                .newSchema(new File("src/test/resources/bpmn/xsd/BPMN20.xsd"))
                .newValidator();
        for (com.automation.entity.Workflow wf : alleFixtures()) {
            String xml = render(wf);
            assertThatCode(() -> validator.validate(new StreamSource(new StringReader(xml))))
                    .as("XSD-Validierung für Fixture '%s':\n%s", wf.getName(), xml)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void invarianteJederKnotenIstVerbunden() throws Exception {
        for (com.automation.entity.Workflow wf : alleFixtures()) {
            Document doc = parse(render(wf));
            Set<String> nodeIds = new HashSet<>();
            Set<String> referenced = new HashSet<>();
            collect(doc.getDocumentElement(), nodeIds, referenced);
            assertThat(nodeIds).as("Fixture '%s': unverbundene Knoten", wf.getName()).isSubsetOf(referenced);
        }
    }

    @Test
    void invarianteJedesElementHatDiagrammKoordinaten() throws Exception {
        for (com.automation.entity.Workflow wf : alleFixtures()) {
            Document doc = parse(render(wf));
            Set<String> nodeIds = new HashSet<>();
            collect(doc.getDocumentElement(), nodeIds, new HashSet<>());
            Set<String> shapeRefs = new HashSet<>();
            NodeList shapes = doc.getElementsByTagNameNS(BpmnXmlRenderer.NS_DI, "BPMNShape");
            for (int i = 0; i < shapes.getLength(); i++) {
                shapeRefs.add(((Element) shapes.item(i)).getAttribute("bpmnElement"));
            }
            assertThat(nodeIds).as("Fixture '%s': Knoten ohne BPMNShape", wf.getName()).isSubsetOf(shapeRefs);

            NodeList edges = doc.getElementsByTagNameNS(BpmnXmlRenderer.NS_DI, "BPMNEdge");
            NodeList flows = doc.getElementsByTagNameNS(BpmnXmlRenderer.NS_MODEL, "sequenceFlow");
            assertThat(edges.getLength()).isEqualTo(flows.getLength());
            for (int i = 0; i < edges.getLength(); i++) {
                NodeList wps = ((Element) edges.item(i))
                        .getElementsByTagNameNS(BpmnXmlRenderer.NS_DDI, "waypoint");
                assertThat(wps.getLength()).isGreaterThanOrEqualTo(2);
            }
        }
    }

    @Test
    void extensionElementsTragenDieOriginalConfig() {
        String xml = render(WorkflowFixtures.linear());
        assertThat(xml).contains("mysuricates:blockConfig");
        assertThat(xml).contains("git log --oneline -20");
        assertThat(xml).contains("scriptFormat=\"bash\"");
        // Finding 1 (Verlustfreiheit, Spec §6): der Block-Typ wird als Attribut mitgeführt,
        // damit der Import nicht aus der BPMN-Semantik geraten werden muss.
        assertThat(xml).contains("blockConfig type=\"shell\"");
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new StringReader(xml)));
    }

    private static void collect(Element element, Set<String> nodeIds, Set<String> referenced) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element child)) {
                continue;
            }
            if (FLOW_NODE_NAMES.contains(child.getLocalName())) {
                nodeIds.add(child.getAttribute("id"));
            }
            if ("sequenceFlow".equals(child.getLocalName())) {
                referenced.add(child.getAttribute("sourceRef"));
                referenced.add(child.getAttribute("targetRef"));
            }
            collect(child, nodeIds, referenced);
        }
    }
}
