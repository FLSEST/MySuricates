package com.automation.exception;

public class TailscaleException extends RuntimeException {

    public TailscaleException(String message) {
        super(message);
    }

    public TailscaleException(String message, Throwable cause) {
        super(message, cause);
    }
}
