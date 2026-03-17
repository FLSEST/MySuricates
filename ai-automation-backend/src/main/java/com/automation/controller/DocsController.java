package com.automation.controller;

import com.automation.config.DocsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/docs")
@RequiredArgsConstructor
public class DocsController {

    private static final Map<String, String> LABEL_MAP = Map.ofEntries(
            Map.entry("WORKFLOW-GUIDE.md", "Workflow Guide"),
            Map.entry("architektur.md", "Architektur"),
            Map.entry("api-referenz.md", "API-Referenz"),
            Map.entry("datenmodell.md", "Datenmodell"),
            Map.entry("deployment.md", "Deployment"),
            Map.entry("entwicklung.md", "Entwicklung"),
            Map.entry("frontend.md", "Frontend"),
            Map.entry("workflow-memory-architecture.md", "Workflow Memory")
    );

    private final DocsConfig docsConfig;

    @GetMapping
    public List<Map<String, String>> listDocs() {
        Path docsDir = resolveDocsDir();
        try {
            return Files.list(docsDir)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        String label = LABEL_MAP.getOrDefault(name, formatLabel(name));
                        return Map.of("name", name, "label", label);
                    })
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list docs directory: {}", docsDir, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not list docs directory");
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<Map<String, String>> getDoc(@PathVariable String name) {
        validateName(name);
        Path docsDir = resolveDocsDir();
        Path file = docsDir.resolve(name).normalize();

        if (!file.startsWith(docsDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid document name");
        }
        if (!file.getFileName().toString().endsWith(".md")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .md files are allowed");
        }
        if (!Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found");
        }

        try {
            String content = Files.readString(file);
            return ResponseEntity.ok(Map.of("content", content));
        } catch (IOException e) {
            log.error("Failed to read doc file: {}", file, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read document");
        }
    }

    private void validateName(String name) {
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid document name");
        }
    }

    private Path resolveDocsDir() {
        return Path.of(docsConfig.getDirectory()).toAbsolutePath().normalize();
    }

    private String formatLabel(String filename) {
        String base = filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
        return base.replace("-", " ").replace("_", " ");
    }
}
