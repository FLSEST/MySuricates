package com.automation.exception;

import jakarta.persistence.EntityNotFoundException;

import java.util.Map;

/**
 * "{resource} not found: {id}" as a 404 the UI translates (TODO 2b73ff48). Still an
 * {@link EntityNotFoundException}, so callers and tests that expect one keep working; the
 * {@link ElementNotFoundException} counterpart for {@code NoSuchElementException}.
 */
public class LocalizedEntityNotFoundException extends EntityNotFoundException implements LocalizedError {

    private final Map<String, String> params;

    public LocalizedEntityNotFoundException(String resource, String id) {
        this(Map.of("resource", String.valueOf(resource), "id", String.valueOf(id)));
    }

    private LocalizedEntityNotFoundException(Map<String, String> params) {
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
