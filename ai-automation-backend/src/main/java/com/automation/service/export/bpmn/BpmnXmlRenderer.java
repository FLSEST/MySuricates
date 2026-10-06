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

/** Serializes the internal model to BPMN 2.0 XML (JDK DOM, no external library). */
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
            // false as long as serviceTasks carry no engine implementation (stage 1) —
            // a Camunda deploy would otherwise fail; modeler view unaffected.
            process.setAttribute("isExecutable", "false");
            for (BpmnModel.Node n : model.nodes) {
                appendNode(doc, process, n);
            }
            for (BpmnModel.Edge e : model.edges) {
                appendFlow(doc, process, e);
            }
            for (BpmnModel.DataObject d : model.dataObjects) {
                appendDataObject(doc, process, d);
            }

            appendDiagram(doc, definitions, model);
            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException ex) {
            throw new IllegalStateException("BPMN-Rendering fehlgeschlagen", ex);
        }
    }

    private void appendNode(Document doc, Element parent, BpmnModel.Node n) {
        if (n.warning != null) {
            // "--" is not allowed in XML comments and would break on re-parsing
            parent.appendChild(doc.createComment(" " + n.warning.replace("--", "- -") + " "));
        }
        Element el = appendChild(doc, parent, elementName(n.kind));
        el.setAttribute("id", n.id);
        if (n.name != null && !n.name.isBlank()) {
            el.setAttribute("name", n.name);
        }
        if (n.kind == BpmnModel.Kind.SCRIPT_TASK) {
            el.setAttribute("scriptFormat", n.scriptFormat != null ? n.scriptFormat : "bash");
        }
        if (n.kind == BpmnModel.Kind.CALL_ACTIVITY && n.calledElement != null && !n.calledElement.isBlank()) {
            el.setAttribute("calledElement", n.calledElement);
        }
        if (n.defaultFlowId != null) {
            el.setAttribute("default", n.defaultFlowId);
        }
        // Order according to the XSD (tActivity): documentation, extensionElements, property,
        // dataInputAssociation, dataOutputAssociation, loopCharacteristics, script, children
        if (n.documentation != null && !n.documentation.isBlank()) {
            appendChild(doc, el, "documentation").setTextContent(n.documentation);
        }
        if (n.configJson != null) {
            Element ext = appendChild(doc, el, "extensionElements");
            Element cfg = doc.createElementNS(NS_MYSURICATES, "mysuricates:blockConfig");
            if (n.blockType != null && !n.blockType.isBlank()) {
                cfg.setAttribute("type", n.blockType);
            }
            if (n.blockName != null && !n.blockName.isBlank()) {
                cfg.setAttribute("name", n.blockName);
            }
            cfg.setTextContent(n.configJson);
            ext.appendChild(cfg);
        }
        appendDataAssociations(doc, el, n);
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

    /**
     * Data associations of the activity. {@code dataInputAssociation/targetRef} is, per the XSD, an
     * IDREF to an element of the activity — an empty {@code property} serves as placeholder for that
     * (as in bpmn.io/Camunda); the semantics of the data flow lies in the {@code sourceRef}.
     */
    private void appendDataAssociations(Document doc, Element el, BpmnModel.Node n) {
        if (n.dataPropertyId != null) {
            Element property = appendChild(doc, el, "property");
            property.setAttribute("id", n.dataPropertyId);
            property.setAttribute("name", "__targetRef_placeholder");
        }
        for (BpmnModel.DataAssociation a : n.dataInputs) {
            Element assoc = appendChild(doc, el, "dataInputAssociation");
            assoc.setAttribute("id", a.id);
            appendChild(doc, assoc, "sourceRef").setTextContent(a.dataReferenceId);
            appendChild(doc, assoc, "targetRef").setTextContent(n.dataPropertyId);
        }
        for (BpmnModel.DataAssociation a : n.dataOutputs) {
            Element assoc = appendChild(doc, el, "dataOutputAssociation");
            assoc.setAttribute("id", a.id);
            appendChild(doc, assoc, "targetRef").setTextContent(a.dataReferenceId);
        }
    }

    private void appendDataObject(Document doc, Element process, BpmnModel.DataObject d) {
        Element object = appendChild(doc, process, "dataObject");
        object.setAttribute("id", d.id);
        Element reference = appendChild(doc, process, "dataObjectReference");
        reference.setAttribute("id", d.referenceId);
        reference.setAttribute("dataObjectRef", d.id);
        reference.setAttribute("name", d.name);
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
        appendDataShapes(doc, plane, model.dataObjects);
        appendEdgesDi(doc, plane, model.edges);
        appendDataEdgesDi(doc, plane, model.dataAssociations);
        appendChildDi(doc, plane, model.nodes);
    }

    private void appendDataShapes(Document doc, Element plane, java.util.List<BpmnModel.DataObject> objects) {
        for (BpmnModel.DataObject d : objects) {
            Element shape = doc.createElementNS(NS_DI, "bpmndi:BPMNShape");
            shape.setAttribute("id", "shape_" + d.referenceId);
            shape.setAttribute("bpmnElement", d.referenceId);
            Element bounds = doc.createElementNS(NS_DC, "dc:Bounds");
            bounds.setAttribute("x", fmt(d.x));
            bounds.setAttribute("y", fmt(d.y));
            bounds.setAttribute("width", fmt(d.w));
            bounds.setAttribute("height", fmt(d.h));
            shape.appendChild(bounds);
            plane.appendChild(shape);
        }
    }

    private void appendDataEdgesDi(Document doc, Element plane, java.util.List<BpmnModel.DataAssociation> associations) {
        for (BpmnModel.DataAssociation a : associations) {
            Element edge = doc.createElementNS(NS_DI, "bpmndi:BPMNEdge");
            edge.setAttribute("id", "edge_" + a.id);
            edge.setAttribute("bpmnElement", a.id);
            for (double[] wp : a.waypoints) {
                Element waypoint = doc.createElementNS(NS_DDI, "di:waypoint");
                waypoint.setAttribute("x", fmt(wp[0]));
                waypoint.setAttribute("y", fmt(wp[1]));
                edge.appendChild(waypoint);
            }
            plane.appendChild(edge);
        }
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
