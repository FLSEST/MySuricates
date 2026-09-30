package com.automation.exception;

import java.util.Map;

/** Invalid input with a message the UI translates. Still an {@link IllegalArgumentException}, so existing callers that catch one keep working. */
public class LocalizedArgumentException extends IllegalArgumentException implements LocalizedError {

    private final ErrorMessage errorMessage;
    private final Map<String, String> params;

    public LocalizedArgumentException(ErrorMessage errorMessage) {
        this(errorMessage, Map.of());
    }

    public LocalizedArgumentException(ErrorMessage errorMessage, Map<String, String> params) {
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
