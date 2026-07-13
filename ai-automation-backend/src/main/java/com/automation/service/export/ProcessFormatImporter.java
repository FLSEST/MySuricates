package com.automation.service.export;

/**
 * Adapter, der ein externes Prozess-Definitionsformat in einen MySuricates-Workflow zurückliest.
 * Gegenstück zu {@link ProcessFormatAdapter}; getrennt, damit ein reiner Exporter nicht zum
 * Import gezwungen wird. BPMN ist die erste Implementierung (nur eigene Exporte, Roundtrip).
 */
public interface ProcessFormatImporter {

    /** Eindeutiger Format-Schlüssel, z. B. "bpmn". */
    String formatKey();

    /** Parst + validiert den Inhalt und liefert Name + Block-Liste. Wirft bei ungültiger/fremder Eingabe. */
    ImportedWorkflow importFrom(String content);
}
