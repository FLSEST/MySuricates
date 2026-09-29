package com.automation.exception;

import java.util.Map;

/**
 * Thrown when exporting a TODO to an external tracker cannot proceed
 * (no target configured, provider not connected, provider not task-capable).
 * Carries a specific {@link ErrorCode} so the API returns a structured 4xx (never a 500)
 * and the UI can react precisely.
 */
public class TaskExportException extends CodedException {

    public TaskExportException(ErrorCode code, String message) {
        super(code, message);
    }

    /** @param params describe the message completely — see {@link CodedException} */
    public TaskExportException(ErrorCode code, String message, Map<String, String> params) {
        super(code, message, params);
    }
}
