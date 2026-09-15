package com.automation.executor.compress;

/** Failure of a compression with a user-facing German message; the step becomes FAILED, no partial result. */
public class CompressionException extends RuntimeException {
    public CompressionException(String message) {
        super(message);
    }
}
