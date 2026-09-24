package com.automation.service.integration.providers.caldav;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * WebDAV/CalDAV XML: request bodies and a hardened multistatus parser (RFC 4918 §13, RFC 4791).
 * The DOM factory refuses DOCTYPEs and external entities/DTDs, so a hostile server cannot use XXE
 * to read local files or reach internal hosts through the backend.
 */
final class CalDavXml {

    static final String DAV = "DAV:";
    static final String CALDAV = "urn:ietf:params:xml:ns:caldav";
    static final String CALSERVER = "http://calendarserver.org/ns/";
    static final String APPLE = "http://apple.com/ns/ical/";

    private CalDavXml() {}

    static final String PROPFIND_PRINCIPAL = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/><d:displayname/></d:prop></d:propfind>""";

    static final String PROPFIND_HOME = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">\
            <d:prop><c:calendar-home-set/><d:displayname/></d:prop></d:propfind>""";

    static final String PROPFIND_CALENDARS = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" \
            xmlns:cs="http://calendarserver.org/ns/" xmlns:a="http://apple.com/ns/ical/">\
            <d:prop><d:resourcetype/><d:displayname/><c:calendar-description/>\
            <c:supported-calendar-component-set/><cs:getctag/><a:calendar-color/></d:prop></d:propfind>""";

    /** calendar-query for VEVENTs overlapping [start, end), expanded to instances by the server. */
    static String calendarQuery(String start, String end) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">\
                <d:prop><d:getetag/><c:calendar-data><c:expand start="%1$s" end="%2$s"/></c:calendar-data></d:prop>\
                <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">\
                <c:time-range start="%1$s" end="%2$s"/></c:comp-filter></c:comp-filter></c:filter>\
                </c:calendar-query>""".formatted(start, end);
    }

    /** One {@code <d:response>}: its href and the {@code <d:prop>} elements of all 2xx propstats. */
    record DavResponse(String href, List<Element> props) {

        /** Text of the first matching property, trimmed; {@code null} when absent or empty. */
        String text(String ns, String local) {
            Element e = find(ns, local);
            if (e == null) return null;
            String t = e.getTextContent();
            return t == null || t.isBlank() ? null : t.trim();
        }

        /** Raw text (not trimmed) — for calendar-data, whose line structure matters. */
        String rawText(String ns, String local) {
            Element e = find(ns, local);
            return e == null ? null : e.getTextContent();
        }

        /** The {@code <d:href>} nested in a property such as current-user-principal. */
        String hrefIn(String ns, String local) {
            Element e = find(ns, local);
            if (e == null) return null;
            Element href = firstChild(e, DAV, "href");
            return href == null ? null : href.getTextContent().trim();
        }

        boolean hasChild(String ns, String local, String childNs, String childLocal) {
            Element e = find(ns, local);
            return e != null && firstChild(e, childNs, childLocal) != null;
        }

        /** {@code name} attributes of the {@code <c:comp>} entries of supported-calendar-component-set. */
        List<String> components() {
            List<String> out = new ArrayList<>();
            Element set = find(CALDAV, "supported-calendar-component-set");
            if (set == null) return out;
            NodeList comps = set.getElementsByTagNameNS(CALDAV, "comp");
            for (int i = 0; i < comps.getLength(); i++) out.add(((Element) comps.item(i)).getAttribute("name"));
            return out;
        }

        private Element find(String ns, String local) {
            for (Element prop : props) {
                Element e = firstChild(prop, ns, local);
                if (e != null) return e;
            }
            return null;
        }
    }

    static List<DavResponse> parseMultistatus(String xml) {
        Document doc;
        try {
            DocumentBuilder builder = secureFactory().newDocumentBuilder();
            doc = builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalStateException("CalDAV server sent an unreadable XML response: " + e.getMessage(), e);
        }
        Element root = doc.getDocumentElement();
        if (!DAV.equals(root.getNamespaceURI()) || !"multistatus".equals(root.getLocalName())) {
            throw new IllegalStateException("CalDAV server did not answer with a multistatus document");
        }
        List<DavResponse> responses = new ArrayList<>();
        for (Element response : children(root, DAV, "response")) {
            Element href = firstChild(response, DAV, "href");
            if (href == null) continue;
            List<Element> props = new ArrayList<>();
            for (Element propstat : children(response, DAV, "propstat")) {
                Element status = firstChild(propstat, DAV, "status");
                Element prop = firstChild(propstat, DAV, "prop");
                if (prop != null && (status == null || isSuccess(status.getTextContent()))) props.add(prop);
            }
            responses.add(new DavResponse(href.getTextContent().trim(), props));
        }
        return responses;
    }

    static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /** "HTTP/1.1 200 OK" → true for any 2xx. */
    static boolean isSuccess(String statusLine) {
        String[] parts = statusLine == null ? new String[0] : statusLine.trim().split("\\s+");
        return parts.length >= 2 && parts[1].startsWith("2");
    }

    private static List<Element> children(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) out.add(e);
        }
        return out;
    }

    private static Element firstChild(Element parent, String ns, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) return e;
        }
        return null;
    }
}
