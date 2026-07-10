package com.automation.service.export.bpmn;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;

/** Serialisiert das interne Modell zu BPMN-2.0-XML (JDK-DOM, keine externe Library). */
class BpmnXmlRenderer {

    static final String NS_MODEL = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    static final String NS_DI = "http://www.omg.org/spec/BPMN/20100524/DI";
    static final String NS_DC = "http://www.omg.org/spec/DD/20100524/DC";
    static final String NS_DDI = "http://www.omg.org/spec/DD/20100524/DI";
    static final String NS_MYSURICATES = "https://mysuricates.dev/schema/bpmn/1.0";
    private static final String XMLNS = "http://www.w3.org/2000/xmlns/";

    String render(BpmnModel model) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder().newDocument();

            Element definitions = doc.createElementNS(NS_MODEL, "definitions");
            definitions.setAttribute("id", "Definitions_1");
            definitions.setAttribute("targetNamespace", "http://bpmn.io/schema/bpmn");
            definitions.setAttribute("exporter", "MySuricates");
            definitions.setAttribute("exporterVersion", "1.0");
            definitions.setAttributeNS(XMLNS, "xmlns:bpmndi", NS_DI);
            definitions.setAttributeNS(XMLNS, "xmlns:dc", NS_DC);
            definitions.setAttributeNS(XMLNS, "xmlns:di", NS_DDI);
            definitions.setAttributeNS(XMLNS, "xmlns:mysuricates", NS_MYSURICATES);
            doc.appendChild(definitions);

            Element process = appendChild(doc, definitions, "process");
            process.setAttribute("id", model.processId);
            process.setAttribute("name", model.processName);
            process.setAttribute("isExecutable", "true");
            for (BpmnModel.Node n : model.nodes) {
                appendNode(doc, process, n);
            }
            for (BpmnModel.Edge e : model.edges) {
                appendFlow(doc, process, e);
            }

            appendDiagram(doc, definitions, model);
            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException ex) {
            throw new IllegalStateException("BPMN-Rendering fehlgeschlagen", ex);
        }
    }

    private void appendNode(Document doc, Element parent, BpmnModel.Node n) {
        if (n.warning != null) {
            parent.appendChild(doc.createComment(" " + n.warning + " "));
        }
        Element el = appendChild(doc, parent, elementName(n.kind));
        el.setAttribute("id", n.id);
        if (n.name != null && !n.name.isBlank()) {
            el.setAttribute("name", n.name);
        }
        if (n.kind == BpmnModel.Kind.SCRIPT_TASK) {
            el.setAttribute("scriptFormat", "bash");
        }
        if (n.kind == BpmnModel.Kind.CALL_ACTIVITY && n.calledElement != null && !n.calledElement.isBlank()) {
            el.setAttribute("calledElement", n.calledElement);
        }
        if (n.defaultFlowId != null) {
            el.setAttribute("default", n.defaultFlowId);
        }
        // Reihenfolge laut XSD: documentation, extensionElements, loopCharacteristics, script, Kinder
        if (n.documentation != null && !n.documentation.isBlank()) {
            appendChild(doc, el, "documentation").setTextContent(n.documentation);
        }
        if (n.configJson != null) {
            Element ext = appendChild(doc, el, "extensionElements");
            Element cfg = doc.createElementNS(NS_MYSURICATES, "mysuricates:blockConfig");
            if (n.blockType != null && !n.blockType.isBlank()) {
                cfg.setAttribute("type", n.blockType);
            }
            cfg.setTextContent(n.configJson);
            ext.appendChild(cfg);
        }
        if ("multiInstanceSequential".equals(n.loop)) {
            appendChild(doc, el, "multiInstanceLoopCharacteristics").setAttribute("isSequential", "true");
        } else if ("standardLoop".equals(n.loop)) {
            appendChild(doc, el, "standardLoopCharacteristics");
        }
        if (n.script != null && !n.script.isBlank()) {
            appendChild(doc, el, "script").setTextContent(n.script);
        }
        for (BpmnModel.Node child : n.children) {
            appendNode(doc, el, child);
        }
        for (BpmnModel.Edge childEdge : n.childEdges) {
            appendFlow(doc, el, childEdge);
        }
    }

    private void appendFlow(Document doc, Element parent, BpmnModel.Edge e) {
        Element flow = appendChild(doc, parent, "sequenceFlow");
        flow.setAttribute("id", e.id);
        flow.setAttribute("sourceRef", e.sourceRef);
        flow.setAttribute("targetRef", e.targetRef);
        if (e.name != null && !e.name.isBlank()) {
            flow.setAttribute("name", e.name);
        }
    }

    private void appendDiagram(Document doc, Element definitions, BpmnModel model) {
        Element diagram = doc.createElementNS(NS_DI, "bpmndi:BPMNDiagram");
        diagram.setAttribute("id", "BPMNDiagram_1");
        definitions.appendChild(diagram);
        Element plane = doc.createElementNS(NS_DI, "bpmndi:BPMNPlane");
        plane.setAttribute("id", "BPMNPlane_1");
        plane.setAttribute("bpmnElement", model.processId);
        diagram.appendChild(plane);
        appendShapes(doc, plane, model.nodes);
        appendEdgesDi(doc, plane, model.edges);
        appendChildDi(doc, plane, model.nodes);
    }

    private void appendShapes(Document doc, Element plane, java.util.List<BpmnModel.Node> nodes) {
        for (BpmnModel.Node n : nodes) {
            Element shape = doc.createElementNS(NS_DI, "bpmndi:BPMNShape");
            shape.setAttribute("id", "shape_" + n.id);
            shape.setAttribute("bpmnElement", n.id);
            if (n.kind == BpmnModel.Kind.SUB_PROCESS) {
                shape.setAttribute("isExpanded", "true");
            }
            Element bounds = doc.createElementNS(NS_DC, "dc:Bounds");
            bounds.setAttribute("x", fmt(n.x));
            bounds.setAttribute("y", fmt(n.y));
            bounds.setAttribute("width", fmt(n.w));
            bounds.setAttribute("height", fmt(n.h));
            shape.appendChild(bounds);
            plane.appendChild(shape);
        }
    }

    private void appendEdgesDi(Document doc, Element plane, java.util.List<BpmnModel.Edge> edges) {
        for (BpmnModel.Edge e : edges) {
            Element edge = doc.createElementNS(NS_DI, "bpmndi:BPMNEdge");
            edge.setAttribute("id", "edge_" + e.id);
            edge.setAttribute("bpmnElement", e.id);
            for (double[] wp : e.waypoints) {
                Element waypoint = doc.createElementNS(NS_DDI, "di:waypoint");
                waypoint.setAttribute("x", fmt(wp[0]));
                waypoint.setAttribute("y", fmt(wp[1]));
                edge.appendChild(waypoint);
            }
            plane.appendChild(edge);
        }
    }

    private void appendChildDi(Document doc, Element plane, java.util.List<BpmnModel.Node> nodes) {
        for (BpmnModel.Node n : nodes) {
            appendShapes(doc, plane, n.children);
            appendEdgesDi(doc, plane, n.childEdges);
            appendChildDi(doc, plane, n.children);
        }
    }

    private static String elementName(BpmnModel.Kind kind) {
        return switch (kind) {
            case START_EVENT -> "startEvent";
            case END_EVENT -> "endEvent";
            case TASK -> "task";
            case SERVICE_TASK -> "serviceTask";
            case SCRIPT_TASK -> "scriptTask";
            case USER_TASK -> "userTask";
            case EXCLUSIVE_GATEWAY -> "exclusiveGateway";
            case SUB_PROCESS -> "subProcess";
            case CALL_ACTIVITY -> "callActivity";
        };
    }

    private static Element appendChild(Document doc, Element parent, String localName) {
        Element el = doc.createElementNS(NS_MODEL, localName);
        parent.appendChild(el);
        return el;
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String serialize(Document doc) throws TransformerException {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
