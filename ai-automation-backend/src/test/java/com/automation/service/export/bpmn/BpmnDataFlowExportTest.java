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
 * Data-flow export: the placeholders of the block configs produce data objects
 * ({@code dataObject} + {@code dataObjectReference}) and data associations on the producing
 * or consuming task.
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
        // tests produces, compress consumes + produces, report consumes
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
            // targetRef must point to an existing ID (IDREF) — the property placeholder element
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

    /** Data associations must not break the schema on any block type (including loops/sub-processes). */
    @Test
    void ganzerFixtureKatalogBleibtSchemaValide() throws Exception {
        Validator validator = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new File("src/test/resources/bpmn/xsd/BPMN20.xsd"))
                .newValidator();
        List<Workflow> katalog = new ArrayList<>(WorkflowFixtures.roundtripCatalog());
        katalog.add(WorkflowFixtures.workflow("SchleifeAlsVerbraucher", List.of(
                WorkflowFixtures.block("shell", "tests", Map.of("command", "mvn test")),
                WorkflowFixtures.block("foreach", "je Fehler", Map.of(
                        "variable_name", "f", "command", "Analysiere {{ block.tests.output }} für {{ f }}")))));
        for (Workflow wf : katalog) {
            String xml = adapter.export(wf);
            assertThatCode(() -> validator.validate(new StreamSource(new StringReader(xml))))
                    .as("XSD-Validierung für Fixture '%s':\n%s", wf.getName(), xml)
                    .doesNotThrowAnyException();
        }
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

    /**
     * 7557b928: the data flow ends at the branch tasks of the branching, not at the gateway —
     * BPMN allows no data associations on gateways.
     */
    @Test
    void zweigTasksSindDieEndpunkteDerAssoziationen() throws Exception {
        Document doc = export(WorkflowFixtures.branching());

        // tests → analyse (into the case), analyse → report (out of the case)
        assertThat(ownerIdsOf(doc, "dataOutputAssociation"))
                .containsExactlyInAnyOrder("b_0_tests", "b_1_c0_0_analyse");
        assertThat(ownerIdsOf(doc, "dataInputAssociation"))
                .containsExactlyInAnyOrder("b_1_c0_0_analyse", "b_2_report");
        assertThat(elements(doc, "dataObjectReference")).hasSize(2);
    }

    /** No gateway node carries a data association (XSD limit of the notation). */
    @Test
    void keinGatewayTraegtEineDatenassoziation() throws Exception {
        Document doc = export(WorkflowFixtures.branching());
        for (Element gateway : elements(doc, "exclusiveGateway")) {
            assertThat(gateway.getElementsByTagNameNS(NS_MODEL, "dataInputAssociation").getLength()).isZero();
            assertThat(gateway.getElementsByTagNameNS(NS_MODEL, "dataOutputAssociation").getLength()).isZero();
        }
    }

    @Test
    void verzweigterDatenflussIstSchemaValide() throws Exception {
        Validator validator = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
                .newSchema(new File("src/test/resources/bpmn/xsd/BPMN20.xsd"))
                .newValidator();
        String xml = adapter.export(WorkflowFixtures.branching());
        assertThatCode(() -> validator.validate(new StreamSource(new StringReader(xml))))
                .as("XSD-Validierung verzweigter Datenfluss:\n%s", xml)
                .doesNotThrowAnyException();
    }

    /**
     * The gateway node itself never carries an association — BPMN does not allow that on gateways.
     *
     * <p>This is the lasting notation limit and not a gap: the reference sits here in
     * {@code condition}, i.e. in the gateway expression itself, and there is no node there to which
     * an association could belong. For the BRANCHES this no longer holds since TODO 7557b928 —
     * see {@link #zweigKommandosEinesConditionalTragenAssoziationen()}.
     */
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

    /**
     * Branch commands of a {@code conditional} carry their association on their own task.
     *
     * <p>Before TODO 7557b928 the reference ended up at the conditional block itself — i.e. at the gateway —
     * and was discarded: the data flow broke off at every branching although the tasks
     * {@code _then}/{@code _else} long existed. They just were not registered as
     * endpoints.
     */
    @Test
    void zweigKommandosEinesConditionalTragenAssoziationen() throws Exception {
        Workflow wf = WorkflowFixtures.workflow("Gate", List.of(
                WorkflowFixtures.block("shell", "tests", WorkflowFixtures.ordered("command", "mvn test")),
                WorkflowFixtures.block("conditional", "pruefung", WorkflowFixtures.ordered(
                        "condition", "exit == 0",
                        "then_command", "echo ok: {{ block.tests.output }}",
                        "else_command", "echo fehler: {{ block.tests.output }}",
                        "execution_mode", "shell"))));

        Document doc = export(wf);

        assertThat(ownerIdsOf(doc, "dataInputAssociation"))
                .as("beide Zweig-Tasks verbrauchen die Ausgabe des Test-Blocks")
                .anySatisfy(owner -> assertThat(owner).endsWith("_then"))
                .anySatisfy(owner -> assertThat(owner).endsWith("_else"));
        assertThat(ownerIdsOf(doc, "dataInputAssociation"))
                .as("nie am Gateway selbst")
                .noneSatisfy(owner -> assertThat(owner).endsWith("_split"));
        assertThat(elements(doc, "dataObjectReference")).isNotEmpty();
    }

    /** Without else_command there is only the then task — and only there an association. */
    @Test
    void ohneElseZweigTraegtNurDerThenTaskDieAssoziation() throws Exception {
        Workflow wf = WorkflowFixtures.workflow("Gate", List.of(
                WorkflowFixtures.block("shell", "tests", WorkflowFixtures.ordered("command", "mvn test")),
                WorkflowFixtures.block("conditional", "pruefung", WorkflowFixtures.ordered(
                        "condition", "exit == 0",
                        "then_command", "echo ok: {{ block.tests.output }}",
                        "execution_mode", "shell"))));

        Document doc = export(wf);

        assertThat(ownerIdsOf(doc, "dataInputAssociation"))
                .singleElement().satisfies(owner -> assertThat(owner).endsWith("_then"));
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
