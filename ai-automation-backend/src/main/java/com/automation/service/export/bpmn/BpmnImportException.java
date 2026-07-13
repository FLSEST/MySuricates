package com.automation.service.export.bpmn;

/**
 * Fehler beim BPMN-Import (kaputtes XML, keine Prozess-Definition, fremde BPMN, unlesbare Config).
 * Erweitert {@link IllegalArgumentException} → wird vom GlobalExceptionHandler als HTTP 400 abgebildet.
 */
public class BpmnImportException extends IllegalArgumentException {
    public BpmnImportException(String message) {
        super(message);
    }
}
