package com.automation.controller;

import com.automation.config.ReportsConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportsController {

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4}-\\d{2}(?:-\\d{2})?)");

    private final ReportsConfig reportsConfig;

    @GetMapping
    public List<Map<String, String>> listReports() {
        Path dir = resolveReportsDir();
        try {
            return Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator
                            .comparing((Path p) -> extractDate(p.getFileName().toString()))
                            .reversed()
                            .thenComparing(p -> p.getFileName().toString()))
                    .map(p -> {
                        String name = p.getFileName().toString();
                        return Map.of("name", name, "label", formatLabel(name));
                    })
                    .toList();
        } catch (IOException e) {
            log.warn("Could not scan reports directory {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    @GetMapping("/{name}")
    public ResponseEntity<Map<String, String>> getReport(@PathVariable String name) {
        validateName(name);
        Path dir = resolveReportsDir();
        Path file = dir.resolve(name).normalize();

        if (!file.startsWith(dir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report name");
        }
        if (!file.getFileName().toString().endsWith(".md")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .md files are allowed");
        }
        if (!Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found");
        }

        try {
            String content = Files.readString(file);
            return ResponseEntity.ok(Map.of("content", content));
        } catch (IOException e) {
            log.error("Failed to read report file: {}", file, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read report");
        }
    }

    private void validateName(String name) {
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report name");
        }
    }

    private Path resolveReportsDir() {
        return Path.of(reportsConfig.getDirectory()).toAbsolutePath().normalize();
    }

    private String extractDate(String filename) {
        Matcher m = DATE_PATTERN.matcher(filename);
        return m.find() ? m.group(1) : "";
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
