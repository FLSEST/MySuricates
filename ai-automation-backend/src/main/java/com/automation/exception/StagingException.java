package com.automation.exception;

public class StagingException extends RuntimeException {

    public StagingException(String message) {
        super(message);
    }

    public StagingException(String message, Throwable cause) {
        super(message, cause);
    }
}
