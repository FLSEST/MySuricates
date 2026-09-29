package com.automation.service.project.bundle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Reads the preview and import requests from the raw body, so the size limit applies before Jackson
 * builds anything from it: at most {@value #MAX_CHARS} characters (~5 MB), like the other imports.
 */
@Component
public class BundleRequestReader {

    static final int MAX_CHARS = 5 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    public BundleRequestReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T read(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Request body is missing");
        }
        if (body.length() > MAX_CHARS) {
            throw new IllegalArgumentException("Bundle request too large (max " + MAX_CHARS / (1024 * 1024) + " MB)");
        }
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bundle request is not valid JSON: " + e.getOriginalMessage());
        }
    }
}
