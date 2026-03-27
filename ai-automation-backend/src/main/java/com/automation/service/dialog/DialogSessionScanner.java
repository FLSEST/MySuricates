package com.automation.service.dialog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scannt ~/.claude/projects/ nach JSONL-Session-Dateien und liefert
 * Metadaten ohne KI-Aufruf.
 */
@Component
@Slf4j
public class DialogSessionScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CLAUDE_PROJECTS_BASE =
            Path.of(System.getProperty("user.home"), ".claude", "projects");

    public record DialogSessionInfo(
            String sessionId,
            String filePath,
            String projectPath,
            Instant startedAt,
            Instant endedAt,
            int turnCount,
            int toolCallCount,
            List<String> dominantTools,
            String firstUserMessage,
            boolean substantial  // toolCallCount >= 3 && turnCount >= 2
    ) {}

    /**
     * Listet Sessions für einen bestimmten Projekt-Pfad.
     * @param projectPathFilter absoluter Pfad des Projekts (z.B. /home/user/project), oder null für alle
     * @param limit maximale Anzahl zurückgegebener Sessions (nach Aktualität sortiert)
     */
    public List<DialogSessionInfo> scanSessions(String projectPathFilter, int limit) {
        if (!Files.exists(CLAUDE_PROJECTS_BASE)) {
            log.warn("Claude projects directory not found: {}", CLAUDE_PROJECTS_BASE);
            return List.of();
        }

        Path targetDir = projectPathFilter != null
                ? CLAUDE_PROJECTS_BASE.resolve(encodeProjectPath(projectPathFilter))
                : CLAUDE_PROJECTS_BASE;

        return collectJsonlFiles(targetDir, limit);
    }

    /**
     * Listet alle Sessions über alle Projekte hinweg.
     */
    public List<DialogSessionInfo> scanAllSessions(int limit) {
        return scanSessions(null, limit);
    }

    /**
     * Gibt alle bekannten Projekt-Pfade aus den gescannten Sessions zurück.
     */
    public List<String> listProjectPaths() {
        if (!Files.exists(CLAUDE_PROJECTS_BASE)) return List.of();

        Set<String> paths = new LinkedHashSet<>();
        try (Stream<Path> dirs = Files.list(CLAUDE_PROJECTS_BASE)) {
            dirs.filter(Files::isDirectory)
                    .map(dir -> decodeProjectPath(dir.getFileName().toString()))
                    .filter(p -> !p.isBlank())
                    .sorted()
                    .forEach(paths::add);
        } catch (IOException e) {
            log.debug("Error listing project dirs: {}", e.getMessage());
        }
        return new ArrayList<>(paths);
    }

    private List<DialogSessionInfo> collectJsonlFiles(Path searchRoot, int limit) {
        List<Path> jsonlFiles = new ArrayList<>();

        try {
            if (Files.isDirectory(searchRoot)) {
                // Direkt im Verzeichnis und 1 Ebene tiefer suchen
                collectJsonlRecursive(searchRoot, jsonlFiles, 1);
            }
        } catch (IOException e) {
            log.debug("Error scanning for JSONL files: {}", e.getMessage());
        }

        // Nach letzter Änderung sortieren (neueste zuerst)
        jsonlFiles.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });

        return jsonlFiles.stream()
                .limit(limit)
                .map(this::buildSessionInfo)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private void collectJsonlRecursive(Path dir, List<Path> result, int remainingDepth) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> children = entries.toList();
            for (Path child : children) {
                if (Files.isRegularFile(child) && child.toString().endsWith(".jsonl")) {
                    result.add(child);
                } else if (remainingDepth > 0 && Files.isDirectory(child)) {
                    collectJsonlRecursive(child, result, remainingDepth - 1);
                }
            }
        }
    }

    private DialogSessionInfo buildSessionInfo(Path jsonlFile) {
        try {
            List<String> lines = Files.readAllLines(jsonlFile);

            String sessionId = null;
            String projectPath = "";
            Instant firstTs = null;
            Instant lastTs = null;
            int turnCount = 0;
            int toolCallCount = 0;
            Map<String, Integer> toolFreq = new LinkedHashMap<>();
            String firstUserMessage = null;

            for (String line : lines) {
                if (line.isBlank()) continue;

                Map<String, Object> entry;
                try {
                    entry = MAPPER.readValue(line, new TypeReference<>() {});
                } catch (Exception e) {
                    continue;
                }

                String type = (String) entry.get("type");
                if (type == null || "file-history-snapshot".equals(type) || "progress".equals(type)) {
                    continue;
                }

                if (sessionId == null) {
                    sessionId = (String) entry.get("sessionId");
                    projectPath = (String) entry.getOrDefault("cwd", "");
                }

                Instant ts = parseTimestamp(entry.get("timestamp"));
                if (ts != null) {
                    if (firstTs == null || ts.isBefore(firstTs)) firstTs = ts;
                    if (lastTs == null || ts.isAfter(lastTs)) lastTs = ts;
                }

                if (!"user".equals(type) && !"assistant".equals(type)) continue;
                turnCount++;

                Object msgObj = entry.get("message");
                if (!(msgObj instanceof Map<?, ?> msg)) continue;

                Object contentObj = msg.get("content");

                // Erste User-Nachricht als Preview
                if ("user".equals(type) && firstUserMessage == null) {
                    firstUserMessage = extractFirstText(contentObj);
                }

                // Tool-Calls zählen
                if ("assistant".equals(type) && contentObj instanceof List<?> blocks) {
                    for (Object block : blocks) {
                        if (block instanceof Map<?, ?> b && "tool_use".equals(b.get("type"))) {
                            toolCallCount++;
                            String toolName = (String) b.get("name");
                            if (toolName != null) {
                                toolFreq.merge(toolName, 1, Integer::sum);
                            }
                        }
                    }
                }
            }

            // Top-3 Tools
            List<String> dominantTools = toolFreq.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .map(e -> e.getKey() + " ×" + e.getValue())
                    .collect(Collectors.toList());

            boolean substantial = toolCallCount >= 3 && turnCount >= 2;

            return new DialogSessionInfo(
                    sessionId != null ? sessionId : jsonlFile.getFileName().toString().replace(".jsonl", ""),
                    jsonlFile.toAbsolutePath().toString(),
                    projectPath,
                    firstTs,
                    lastTs,
                    turnCount,
                    toolCallCount,
                    dominantTools,
                    truncate(firstUserMessage, 200),
                    substantial
            );

        } catch (Exception e) {
            log.debug("Error reading session metadata from {}: {}", jsonlFile, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractFirstText(Object contentObj) {
        if (contentObj instanceof String s) return s.strip();
        if (contentObj instanceof List<?> blocks) {
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> b && "text".equals(b.get("type"))) {
                    Object text = b.get("text");
                    if (text instanceof String s && !s.isBlank()) return s.strip();
                }
            }
        }
        return null;
    }

    private Instant parseTimestamp(Object tsObj) {
        if (tsObj == null) return null;
        try {
            return Instant.parse(tsObj.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    /**
     * Kodiert einen absoluten Pfad so, wie Claude Code es tut:
     * "/home/user/project" → "-home-user-project"
     */
    static String encodeProjectPath(String absolutePath) {
        return absolutePath.replace("/", "-");
    }

    /**
     * Dekodiert einen kodierten Verzeichnisnamen zurück zu einem Pfad:
     * "-home-user-project" → "/home/user/project"
     */
    static String decodeProjectPath(String encoded) {
        if (!encoded.startsWith("-")) return encoded;
        return encoded.replaceFirst("-", "/").replace("-", "/");
    }
}
