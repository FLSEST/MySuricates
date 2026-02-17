package com.automation.service.project;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PathValidator {

    public void validate(String path) {
        Path normalized = Path.of(path).normalize().toAbsolutePath();
        String normalizedStr = normalized.toString();

        // Block access to sensitive system directories
        if (normalizedStr.equals("/") || normalizedStr.startsWith("/etc")
                || normalizedStr.startsWith("/var/run") || normalizedStr.startsWith("/proc")
                || normalizedStr.startsWith("/sys") || normalizedStr.startsWith("/dev")) {
            throw new IllegalArgumentException("Path points to a restricted system directory: " + path);
        }

        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Path must be an existing directory: " + path);
        }
    }
}
