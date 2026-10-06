package com.automation.service.export;

/**
 * Adapter that reads an external process definition format back into a MySuricates workflow.
 * Counterpart to {@link ProcessFormatAdapter}; separate so that a pure exporter is not forced
 * to import. BPMN is the first implementation (own exports only, round trip).
 */
public interface ProcessFormatImporter {

    /** Unique format key, e.g. "bpmn". */
    String formatKey();

    /** Parses + validates the content and returns name + block list. Throws on invalid/foreign input. */
    ImportedWorkflow importFrom(String content);
}
