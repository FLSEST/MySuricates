package com.automation.exception;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * "{resource} not found: {id}" as a 404 the UI translates. Still a {@link NoSuchElementException},
 * so callers and tests that expect one keep working; for the "… not found with id" form use
 * {@link ResourceNotFoundException}.
 */
public class ElementNotFoundException extends NoSuchElementException implements LocalizedError {

    private final Map<String, String> params;

    public ElementNotFoundException(String resource, String id) {
        this(Map.of("resource", String.valueOf(resource), "id", String.valueOf(id)));
    }

    private ElementNotFoundException(Map<String, String> params) {
        super(ErrorMessage.NAMED_NOT_FOUND.format(params));
        this.params = params;
    }

    @Override
    public ErrorMessage errorMessage() {
        return ErrorMessage.NAMED_NOT_FOUND;
    }

    @Override
    public Map<String, String> params() {
        return params;
    }
}
