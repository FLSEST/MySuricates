package com.automation.exception;

import java.util.Map;

/** A state conflict with a message the UI translates. Still an {@link IllegalStateException}, so existing callers that catch one keep working. */
public class LocalizedStateException extends IllegalStateException implements LocalizedError {

    private final ErrorMessage errorMessage;
    private final Map<String, String> params;

    public LocalizedStateException(ErrorMessage errorMessage) {
        this(errorMessage, Map.of());
    }

    public LocalizedStateException(ErrorMessage errorMessage, Map<String, String> params) {
        super(errorMessage.format(params));
        this.errorMessage = errorMessage;
        this.params = Map.copyOf(params);
    }

    @Override
    public ErrorMessage errorMessage() {
        return errorMessage;
    }

    @Override
    public Map<String, String> params() {
        return params;
    }
}
