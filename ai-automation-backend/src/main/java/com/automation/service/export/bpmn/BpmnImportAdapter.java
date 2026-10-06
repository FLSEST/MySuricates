package com.automation.service.export.bpmn;

import com.automation.entity.BlockDefinition;
import com.automation.service.export.ImportedWorkflow;
import com.automation.service.export.ProcessFormatImporter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.automation.service.export.bpmn.BpmnXmlRenderer.NS_MODEL;
import static com.automation.service.export.bpmn.BpmnXmlRenderer.NS_MYSURICATES;

/**
 * Reads a BPMN process exported by MySuricates back into a block list (round trip).
 * Deterministic, JDK DOM, no AI call. BPMN without a {@code mysuricates} extension is
 * rejected — nothing is created.
 *
 * <p><b>No proof of authenticity.</b> What is checked here is the <i>format</i>: does the file carry
 * the {@code mysuricates} extension from which blocks can be read? Where it comes from, that does
 * not say — {@code exporter="MySuricates"} and the namespace are freely writable attributes,
 * and the supplied {@code blockConfig} content is adopted as the definition. Whoever imports a
 * BPMN file thus takes responsibility for its content just as with a
 * JSON import. A reliable origin would need a checksum or signature over the export
 * (TODO b18939a6); until then the check is deliberately only a format filter.
 *
 * <p>Principle: for each block the export writes exactly one element carrying {@code blockConfig} as a
 * direct child of {@code <process>}, in block order. Nested switch cases are already in the
 * config JSON of the parent block; inner loop nodes carry no blockConfig.
 * So: walk the direct child nodes in document order and collect their {@code blockConfig}
 * — no flow walking needed.
 */
@Component
@RequiredArgsConstructor
public class BpmnImportAdapter implements ProcessFormatImporter {

    /** Upper limit for the input (characters). Exports are in the KB range; protects against DoS via DOM. */
    static final int MAX_CHARS = 2 * 1024 * 1024;

    static final String MSG_FOREIGN =
            "Diese BPMN enthält keine mysuricates:blockConfig-Erweiterung — daraus lässt sich kein Workflow lesen; Import fremder BPMN folgt in einer späteren Version";
    static final String MSG_NO_PROCESS = "Keine Prozess-Definition gefunden";
    static final String MSG_INVALID_XML = "Ungültiges BPMN-XML";
    static final String MSG_TOO_LARGE = "BPMN-Datei zu groß (max. " + (MAX_CHARS / 1024) + " KB)";

    private final ObjectMapper objectMapper;

    @Override
    public String formatKey() {
        return "bpmn";
    }

    @Override
    public ImportedWorkflow importFrom(String content) {
        if (content == null || content.isBlank()) {
            throw new BpmnImportException(MSG_INVALID_XML + ": leerer Inhalt");
        }
        if (content.length() > MAX_CHARS) {
            throw new BpmnImportException(MSG_TOO_LARGE);
        }
        Document doc = parse(content);
        Element definitions = doc.getDocumentElement();
        Element process = BpmnDom.firstChild(definitions, NS_MODEL, "process");
        if (process == null) {
            throw new BpmnImportException(MSG_NO_PROCESS);
        }

        List<BlockDefinition> blocks = new ArrayList<>();
        for (Element node : BpmnDom.childElements(process)) {
            Element cfg = blockConfigOf(node);
            if (cfg == null) {
                continue;
            }
            BlockDefinition block = new BlockDefinition();
            block.setType(emptyToNull(cfg.getAttribute("type")));
            block.setName(emptyToNull(cfg.getAttribute("name")));
            block.setConfig(parseConfig(cfg.getTextContent(), blocks.size()));
            blocks.add(block);
        }

        // An empty process (only start→end) is valid, provided the file carries the format marker;
        // without a format marker AND without blockConfig there is nothing to read.
        if (blocks.isEmpty() && !hasMySuricatesFormatHint(definitions)) {
            throw new BpmnImportException(MSG_FOREIGN);
        }
        String name = process.getAttribute("name");
        return new ImportedWorkflow(name.isBlank() ? "Importierter Workflow" : name, blocks);
    }

    private Document parse(String xml) {
        try {
            return BpmnDom.secureParse(xml);
        } catch (Exception ex) {
            throw new BpmnImportException(MSG_INVALID_XML + ": " + firstLine(ex.getMessage()));
        }
    }

    /** {@code node/extensionElements/mysuricates:blockConfig} — direct children only, no deep search. */
    private static Element blockConfigOf(Element node) {
        Element ext = BpmnDom.firstChild(node, NS_MODEL, "extensionElements");
        return ext == null ? null : BpmnDom.firstChild(ext, NS_MYSURICATES, "blockConfig");
    }

    /**
     * Format marker, not proof of origin: anyone can write both attributes into any file.
     * Serves only to distinguish an empty export (start→end without a block) from foreign BPMN
     * from which nothing could be read anyway.
     */
    private static boolean hasMySuricatesFormatHint(Element definitions) {
        return "MySuricates".equals(definitions.getAttribute("exporter"))
                || NS_MYSURICATES.equals(definitions.getAttribute("xmlns:mysuricates"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json, int blockIndex) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json.strip(), Map.class);
        } catch (Exception ex) {
            throw new BpmnImportException("Block " + (blockIndex + 1) + ": Konfiguration nicht lesbar");
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String firstLine(String message) {
        return message == null ? "" : message.lines().findFirst().orElse("").strip();
    }
}
