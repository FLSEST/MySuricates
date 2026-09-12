package com.automation.service.cli;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Allowlist for Docker images a workflow shell block may request via {@code container_image}.
 * An empty list means allow-all (legacy behavior); this is logged once at startup since it
 * disables the check entirely. Digest references ({@code image@sha256:...}) are only matched
 * by an entry that is byte-identical to them — a tag-free entry never matches a digest.
 */
@Component
@Slf4j
public class ContainerImageAllowlist {

    @Value("${automation.execution.allowed-container-images:}")
    private String allowedImagesRaw;

    private volatile List<String> entries = List.of();

    /** Replaces the in-memory allowlist. Package-private for tests; production loads from YAML at startup. */
    void setEntries(List<String> entries) {
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public List<String> getEntries() {
        return entries;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        if (allowedImagesRaw == null || allowedImagesRaw.isBlank()) {
            this.entries = List.of();
            log.warn("Container image allowlist empty — any image allowed");
            return;
        }
        List<String> parsed = new ArrayList<>();
        for (String raw : allowedImagesRaw.split(",")) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;
            parsed.add(trimmed);
        }
        this.entries = List.copyOf(parsed);
        log.info("Container image allowlist active: {}", entries);
    }

    /**
     * True if {@code image} is allowed: an empty allowlist allows everything; otherwise an entry
     * matches by exact equality, as a registry/namespace prefix when it ends with "/", or, for a
     * tag-free entry, against the image's bare name or any of its tags.
     */
    public boolean isAllowed(String image) {
        List<String> current = entries;
        if (current.isEmpty()) {
            return true;
        }
        if (image == null || image.isBlank()) {
            return false;
        }
        for (String entry : current) {
            if (matches(entry, image)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String entry, String image) {
        if (entry.equals(image)) return true;
        if (entry.endsWith("/")) return image.startsWith(entry);
        if (isTagFree(entry)) return image.startsWith(entry + ":");
        return false;
    }

    /**
     * True if {@code ref} has no tag: a registry host may itself contain a "{@code :}" (e.g. a
     * port in {@code localhost:5000/myimage}), so "has a tag" is decided from the last path
     * segment only — the part after the last "/", or the whole string when there is none.
     */
    private static boolean isTagFree(String ref) {
        int lastSlash = ref.lastIndexOf('/');
        String lastSegment = lastSlash >= 0 ? ref.substring(lastSlash + 1) : ref;
        return !lastSegment.contains(":");
    }
}
