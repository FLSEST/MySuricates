package com.automation.service.export;

import com.automation.entity.Workflow;

/**
 * Adapter für ein externes Prozess-Definitionsformat.
 * Das kanonische Modell bleibt das Workflow-JSON; jedes Format ist ein Adapter (Spec §3).
 */
public interface ProcessFormatAdapter {

    /** Eindeutiger Format-Schlüssel, z. B. "bpmn". */
    String formatKey();

    /** Dateiendung ohne Punkt, z. B. "bpmn". */
    String fileExtension();

    /** HTTP-Content-Type des Exports, z. B. "application/xml". */
    String contentType();

    /** Serialisiert den Workflow deterministisch in das Zielformat. */
    String export(Workflow workflow);
}
