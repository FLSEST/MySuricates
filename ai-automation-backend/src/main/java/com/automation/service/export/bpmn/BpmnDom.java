package com.automation.service.export.bpmn;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** DOM-Helfer für den BPMN-Import: XXE-sicheres Parsen und Kind-Element-Zugriff. */
final class BpmnDom {

    private BpmnDom() {
    }

    /**
     * Parst namespace-aware und gehärtet: kein DOCTYPE, keine externen Entities, kein XInclude,
     * keine Entity-Expansion (OWASP XXE Prevention Cheat Sheet).
     */
    static Document secureParse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element el) {
                result.add(el);
            }
        }
        return result;
    }

    /** Erstes direktes Kind-Element mit Namespace + lokalem Namen, sonst {@code null}. */
    static Element firstChild(Element parent, String namespace, String localName) {
        for (Element el : childElements(parent)) {
            if (namespace.equals(el.getNamespaceURI()) && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }
}
