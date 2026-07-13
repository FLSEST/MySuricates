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
 * Liest einen von MySuricates exportierten BPMN-Prozess zurück in eine Block-Liste (Roundtrip).
 * Deterministisch, JDK-DOM, kein AI-Call. Fremde BPMN (ohne {@code mysuricates}-Erweiterung)
 * wird abgelehnt — nichts wird angelegt.
 *
 * <p>Prinzip: Je Block schreibt der Export genau ein {@code blockConfig}-tragendes Element als
 * direktes Kind von {@code <process>}, in Blockreihenfolge. Verschachtelte Switch-Cases stecken
 * bereits im Config-JSON des Eltern-Blocks; innere Schleifen-Knoten tragen kein blockConfig.
 * Also: direkte Kind-Knoten in Dokumentreihenfolge durchgehen und deren {@code blockConfig}
 * einsammeln — kein Flow-Walking nötig.
 */
@Component
@RequiredArgsConstructor
public class BpmnImportAdapter implements ProcessFormatImporter {

    /** Obergrenze für die Eingabe (Zeichen). Exporte liegen im KB-Bereich; schützt vor DoS via DOM. */
    static final int MAX_CHARS = 2 * 1024 * 1024;

    static final String MSG_FOREIGN =
            "Diese BPMN stammt nicht aus MySuricates (keine mysuricates:blockConfig-Erweiterung) — Fremd-Import folgt in einer späteren Version";
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

        // Leerer Prozess (nur Start→Ende) ist gültig, sofern die Datei erkennbar von uns stammt;
        // ohne Herkunftsmarker UND ohne blockConfig ist es Fremd-BPMN.
        if (blocks.isEmpty() && !isMySuricatesExport(definitions)) {
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

    /** {@code node/extensionElements/mysuricates:blockConfig} — nur direkte Kinder, kein Tiefensuchen. */
    private static Element blockConfigOf(Element node) {
        Element ext = BpmnDom.firstChild(node, NS_MODEL, "extensionElements");
        return ext == null ? null : BpmnDom.firstChild(ext, NS_MYSURICATES, "blockConfig");
    }

    private static boolean isMySuricatesExport(Element definitions) {
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
