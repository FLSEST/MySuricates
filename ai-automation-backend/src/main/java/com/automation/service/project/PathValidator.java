package com.automation.service.project;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PathValidator {

    @Value("${automation.workspace.root:}")
    private String workspaceRoot;

    public void validate(String path) {
        Path normalized = Path.of(path).normalize().toAbsolutePath();
        String normalizedStr = normalized.toString();

        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            Path root = Path.of(workspaceRoot).normalize().toAbsolutePath();
            if (!normalized.startsWith(root)) {
                throw new IllegalArgumentException("Path must be under workspace root: " + root);
            }
        } else {
            // Existing blocklist as fallback
            if (normalizedStr.equals("/") || normalizedStr.startsWith("/etc")
                    || normalizedStr.startsWith("/var/run") || normalizedStr.startsWith("/proc")
                    || normalizedStr.startsWith("/sys") || normalizedStr.startsWith("/dev")) {
                throw new IllegalArgumentException("Path points to a restricted system directory: " + path);
            }
        }

        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Path must be an existing directory: " + path);
        }
    }
}
