package com.automation.exception;

import java.util.Map;

public class ResourceNotFoundException extends RuntimeException {

    private final Map<String, String> params;

    public ResourceNotFoundException(String resourceType, String id) {
        super(resourceType + " not found with id: " + id);
        this.params = Map.of("resource", String.valueOf(resourceType), "id", String.valueOf(id));
    }

    /** Resource type and id, for the UI's own translation of the message (see {@link CodedException}). */
    public Map<String, String> getParams() {
        return params;
    }
}
