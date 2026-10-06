package com.automation.service.export;

import com.automation.entity.Workflow;

/**
 * Adapter for an external process definition format.
 * The canonical model remains the workflow JSON; every format is an adapter (spec §3).
 */
public interface ProcessFormatAdapter {

    /** Unique format key, e.g. "bpmn". */
    String formatKey();

    /** File extension without dot, e.g. "bpmn". */
    String fileExtension();

    /** HTTP content type of the export, e.g. "application/xml". */
    String contentType();

    /** Serializes the workflow deterministically into the target format. */
    String export(Workflow workflow);
}
