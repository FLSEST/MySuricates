package com.automation.service.export.bpmn;

/**
 * Error during BPMN import (broken XML, no process definition, foreign BPMN, unreadable config).
 * Extends {@link IllegalArgumentException} → mapped by the GlobalExceptionHandler to HTTP 400.
 */
public class BpmnImportException extends IllegalArgumentException {
    public BpmnImportException(String message) {
        super(message);
    }
}
