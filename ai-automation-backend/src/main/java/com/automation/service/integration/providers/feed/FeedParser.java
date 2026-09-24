package com.automation.service.integration.providers.feed;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses RSS 2.0, Atom 1.0 and RSS 1.0 (RDF) into {@link ParsedFeed}. The JDK DOM parser is hardened
 * like {@code BpmnDom}: no DOCTYPE at all (so no XXE and no entity expansion), no external DTD/schema,
 * no XInclude. Child elements are matched by local name, so namespaced variants ({@code dc:date},
 * {@code content:encoded}) work without a namespace table.
 */
final class FeedParser {

    static final int SUMMARY_LENGTH = 500;
    private static final int TITLE_LENGTH = 300;
    private static final String ATOM_NS = "http://www.w3.org/2005/Atom";

    private FeedParser() {}

    record FeedEntry(String title, String link, Instant published, String summary, String author, String id) {}

    record ParsedFeed(String format, String title, String link, List<FeedEntry> entries) {}

    static ParsedFeed parse(byte[] xml) {
        Element root = parseDocument(xml).getDocumentElement();
        String name = root.getLocalName() != null ? root.getLocalName() : root.getNodeName();
        return switch (name) {
            case "rss" -> parseRss(root);
            case "feed" -> {
                if (!ATOM_NS.equals(root.getNamespaceURI())) throw new IllegalArgumentException("Unsupported feed: <feed> without Atom namespace");
                yield parseAtom(root);
            }
            case "RDF" -> parseRdf(root);
            default -> throw new IllegalArgumentException("Unsupported feed format: root element <" + name + ">");
        };
    }

    private static Document parseDocument(byte[] xml) {
        try {
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
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // silent: fatal errors still throw
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (SAXException e) {
            throw new IllegalArgumentException("Feed is not valid XML: " + e.getMessage(), e);
        } catch (ParserConfigurationException | IOException e) {
            throw new IllegalStateException("Feed could not be parsed: " + e.getMessage(), e);
        }
    }

    private static ParsedFeed parseRss(Element root) {
        Element channel = child(root, "channel");
        if (channel == null) throw new IllegalArgumentException("RSS feed without <channel>");
        List<FeedEntry> entries = new ArrayList<>();
        for (Element item : children(channel, "item")) {
            String link = text(child(item, "link"));
            String description = firstNonBlank(text(child(item, "description")), text(child(item, "encoded")));
            entries.add(new FeedEntry(
                    FeedText.plain(text(child(item, "title")), TITLE_LENGTH),
                    link,
                    FeedDates.parse(firstNonBlank(text(child(item, "pubDate")), text(child(item, "date")))),
                    FeedText.plain(description, SUMMARY_LENGTH),
                    firstNonBlank(text(child(item, "author")), text(child(item, "creator"))),
                    firstNonBlank(text(child(item, "guid")), link)));
        }
        return new ParsedFeed("rss", FeedText.plain(text(child(channel, "title")), TITLE_LENGTH),
                text(child(channel, "link")), entries);
    }

    private static ParsedFeed parseAtom(Element feed) {
        List<FeedEntry> entries = new ArrayList<>();
        for (Element entry : children(feed, "entry")) {
            String link = atomLink(entry);
            Element author = child(entry, "author");
            entries.add(new FeedEntry(
                    FeedText.plain(text(child(entry, "title")), TITLE_LENGTH),
                    link,
                    FeedDates.parse(firstNonBlank(text(child(entry, "published")), text(child(entry, "updated")))),
                    FeedText.plain(firstNonBlank(text(child(entry, "summary")), text(child(entry, "content"))), SUMMARY_LENGTH),
                    author != null ? text(child(author, "name")) : null,
                    firstNonBlank(text(child(entry, "id")), link)));
        }
        return new ParsedFeed("atom", FeedText.plain(text(child(feed, "title")), TITLE_LENGTH), atomLink(feed), entries);
    }

    private static ParsedFeed parseRdf(Element rdf) {
        Element channel = child(rdf, "channel");
        List<FeedEntry> entries = new ArrayList<>();
        for (Element item : children(rdf, "item")) {
            String link = text(child(item, "link"));
            String about = item.getAttributeNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "about");
            entries.add(new FeedEntry(
                    FeedText.plain(text(child(item, "title")), TITLE_LENGTH),
                    link,
                    FeedDates.parse(text(child(item, "date"))),
                    FeedText.plain(firstNonBlank(text(child(item, "description")), text(child(item, "encoded"))), SUMMARY_LENGTH),
                    text(child(item, "creator")),
                    firstNonBlank(about, link)));
        }
        return new ParsedFeed("rdf",
                channel != null ? FeedText.plain(text(child(channel, "title")), TITLE_LENGTH) : null,
                channel != null ? text(child(channel, "link")) : null, entries);
    }

    /** {@code rel="alternate"} (or no rel) wins; otherwise the first link with an href. */
    private static String atomLink(Element parent) {
        String fallback = null;
        for (Element link : children(parent, "link")) {
            String href = blankToNull(link.getAttribute("href"));
            if (href == null) continue;
            String rel = link.getAttribute("rel");
            if (rel.isEmpty() || rel.equals("alternate")) return href;
            if (fallback == null) fallback = href;
        }
        return fallback;
    }

    private static Element child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(localName(e))) return e;
        }
        return null;
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(localName(e))) result.add(e);
        }
        return result;
    }

    private static String localName(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getNodeName();
    }

    private static String text(Element e) {
        return e == null ? null : blankToNull(e.getTextContent().trim());
    }

    private static String firstNonBlank(String a, String b) {
        return a != null ? a : b;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
