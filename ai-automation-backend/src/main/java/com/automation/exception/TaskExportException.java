package com.automation.exception;

import lombok.Getter;

/**
 * Thrown when exporting a TODO to an external tracker cannot proceed
 * (no target configured, provider not connected, provider not task-capable).
 * Carries a specific {@link ErrorCode} so the API returns a structured 4xx (never a 500)
 * and the UI can react precisely.
 */
@Getter
public class TaskExportException extends RuntimeException {

    private final ErrorCode code;

    public TaskExportException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
