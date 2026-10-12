package com.automation.exception;

/**
 * An AI provider call failed (CLI missing, timeout, error answer). Answers 503 SERVICE_UNAVAILABLE with the
 * message instead of a generic 500, so the UI can tell a provider outage from a programming error.
 */
public class AiProviderException extends RuntimeException {

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
