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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Serves the Markdown documentation from the configured docs directory ({@code automation.docs.directory}) to the UI. */
@Slf4j
@RestController
@RequestMapping("/api/v1/docs")
@RequiredArgsConstructor
public class DocsController {

    private static final Map<String, String> LABEL_MAP = Map.ofEntries(
            Map.entry("WORKFLOW-GUIDE.md", "Workflow Guide"),
            Map.entry("architektur.md", "Architektur"),
            Map.entry("api-referenz.md", "API-Referenz"),
            Map.entry("baukasten.md", "Baukasten"),
            Map.entry("block-referenz.md", "Block-Referenz"),
            Map.entry("assistant-system.md", "Assistant System"),
            Map.entry("datenmodell.md", "Datenmodell"),
            Map.entry("deployment.md", "Deployment"),
            Map.entry("entwicklung.md", "Entwicklung"),
            Map.entry("frontend.md", "Frontend")
    );

    private final DocsConfig docsConfig;

    /** Lists the Markdown documents of the docs directory, German and English, each with name, label and language. */
    @GetMapping
    public List<Map<String, String>> listDocs() {
        Path docsDir = resolveDocsDir();
        List<Map<String, String>> result = new ArrayList<>(scanDir(docsDir, "de"));
        Path enDir = docsDir.resolve("en");
        if (Files.isDirectory(enDir)) {
            result.addAll(scanDir(enDir, "en"));
        }
        return result;
    }

    private List<Map<String, String>> scanDir(Path dir, String lang) {
        try {
            return Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        String label = LABEL_MAP.getOrDefault(name, formatLabel(name));
                        return Map.of("name", name, "label", label, "lang", lang);
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("Could not scan docs directory {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the Markdown content of one document; {@code lang} is {@code de} (default) or {@code en}.
     * Answers 400 for names with path parts or without {@code .md}, 404 if the file does not exist.
     */
    @GetMapping("/{name}")
    public ResponseEntity<Map<String, String>> getDoc(
            @PathVariable String name,
            @RequestParam(defaultValue = "de") String lang) {
        validateName(name);
        if (!"de".equals(lang) && !"en".equals(lang)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid lang parameter");
        }
        Path docsDir = resolveDocsDir();
        Path baseDir = "en".equals(lang) ? docsDir.resolve("en") : docsDir;
        Path file = baseDir.resolve(name).normalize();

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
        String[] words = base.replace("-", " ").replace("_", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!sb.isEmpty()) sb.append(" ");
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return sb.toString();
    }
}
