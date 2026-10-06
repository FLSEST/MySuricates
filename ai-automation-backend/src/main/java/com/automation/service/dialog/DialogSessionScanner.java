package com.automation.service.dialog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Scans ~/.claude/projects/ for JSONL session files and returns
 * metadata without an AI call. Uses history.jsonl as title source.
 */
@Component
@Slf4j
public class DialogSessionScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CLAUDE_BASE =
            Path.of(System.getProperty("user.home"), ".claude");
    private static final Path CLAUDE_PROJECTS_BASE = CLAUDE_BASE.resolve("projects");
    private static final Path HISTORY_FILE = CLAUDE_BASE.resolve("history.jsonl");

    private static final int SUBSTANTIAL_TOOL_CALLS = 3;
    private static final int SUBSTANTIAL_TURNS = 2;

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
            boolean substantial
    ) {}

    /**
     * Counts .jsonl files per project directory (fast, no file reading).
     * @return map of absolute project path → number of sessions
     */
    public Map<String, Integer> countPerProject() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (!Files.exists(CLAUDE_PROJECTS_BASE)) return counts;

        try (Stream<Path> dirs = Files.list(CLAUDE_PROJECTS_BASE)) {
            dirs.filter(Files::isDirectory).forEach(dir -> {
                try (Stream<Path> files = Files.list(dir)) {
                    int count = (int) files
                            .filter(f -> f.toString().endsWith(".jsonl") && Files.isRegularFile(f))
                            .count();
                    if (count > 0) {
                        String decoded = decodeProjectPath(dir.getFileName().toString());
                        counts.put(decoded, count);
                    }
                } catch (IOException e) {
                    log.debug("Error counting sessions in {}: {}", dir, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Error listing project dirs: {}", e.getMessage());
        }

        return counts;
    }

    /**
     * Lists sessions for a specific project path.
     */
    public List<DialogSessionInfo> scanSessions(String projectPathFilter, int limit) {
        if (!Files.exists(CLAUDE_PROJECTS_BASE)) {
            log.warn("Claude projects directory not found: {}", CLAUDE_PROJECTS_BASE);
            return List.of();
        }

        Path targetDir = projectPathFilter != null
                ? CLAUDE_PROJECTS_BASE.resolve(encodeProjectPath(projectPathFilter))
                : CLAUDE_PROJECTS_BASE;

        // Load history titles once (costs ~50ms for 2 MB)
        Map<String, String> historyTitles = loadHistoryTitles();

        return collectJsonlFiles(targetDir, limit, historyTitles);
    }

    /**
     * Lists all sessions across all projects.
     */
    public List<DialogSessionInfo> scanAllSessions(int limit) {
        return scanSessions(null, limit);
    }

    /**
     * Returns all known project paths from the scanned sessions.
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

    // ── History titles ──────────────────────────────────────

    /**
     * Loads session titles from ~/.claude/history.jsonl.
     * The first substantial message (display > 15 characters) is stored per sessionId.
     * Cost: a one-time parse of ~2 MB.
     */
    private Map<String, String> loadHistoryTitles() {
        Map<String, String> titles = new HashMap<>();
        if (!Files.exists(HISTORY_FILE)) return titles;

        try (BufferedReader reader = Files.newBufferedReader(HISTORY_FILE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> entry = MAPPER.readValue(line, new TypeReference<>() {});
                    String sessionId = (String) entry.get("sessionId");
                    String display = (String) entry.get("display");
                    if (sessionId != null && display != null
                            && display.length() > 15
                            && !titles.containsKey(sessionId)) {
                        titles.put(sessionId, truncate(display.strip(), 150));
                    }
                } catch (Exception e) {
                    // Ignore individual lines
                }
            }
        } catch (IOException e) {
            log.debug("Could not load history titles: {}", e.getMessage());
        }

        return titles;
    }

    // ── Session scan ───────────────────────────────────────

    private List<DialogSessionInfo> collectJsonlFiles(Path searchRoot, int limit,
                                                       Map<String, String> historyTitles) {
        List<Path> jsonlFiles = new ArrayList<>();

        try {
            if (Files.isDirectory(searchRoot)) {
                collectJsonlRecursive(searchRoot, jsonlFiles, 1);
            }
        } catch (IOException e) {
            log.debug("Error scanning for JSONL files: {}", e.getMessage());
        }

        // Sort by last modification (newest first)
        jsonlFiles.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });

        return jsonlFiles.stream()
                .limit(limit)
                .map(f -> buildSessionInfo(f, historyTitles))
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

    private DialogSessionInfo buildSessionInfo(Path jsonlFile, Map<String, String> historyTitles) {
        try {
            List<String> lines = Files.readAllLines(jsonlFile);

            String sessionId = null;
            String projectPath = "";
            Instant firstTs = null;
            Instant lastTs = null;
            int turnCount = 0;
            int toolCallCount = 0;
            Map<String, Integer> toolFreq = new LinkedHashMap<>();
            String firstSubstantialMessage = null;

            for (String line : lines) {
                if (line.isBlank()) continue;

                Map<String, Object> entry;
                try {
                    entry = MAPPER.readValue(line, new TypeReference<>() {});
                } catch (Exception e) {
                    continue;
                }

                String type = (String) entry.get("type");
                if (type == null || "file-history-snapshot".equals(type)
                        || "progress".equals(type) || "queue-operation".equals(type)) {
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

                // First substantial user message (>25 characters, no system wrapper)
                if ("user".equals(type) && firstSubstantialMessage == null) {
                    String text = extractFirstText(contentObj);
                    if (text != null && text.length() > 25 && !isSystemWrapper(text)) {
                        firstSubstantialMessage = text;
                    }
                }

                // Count tool calls
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

            // Top 3 tools
            List<String> dominantTools = toolFreq.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(3)
                    .map(e -> e.getKey() + " ×" + e.getValue())
                    .collect(Collectors.toList());

            boolean substantial = toolCallCount >= SUBSTANTIAL_TOOL_CALLS && turnCount >= SUBSTANTIAL_TURNS;

            // Title choice: 1. history.jsonl 2. first substantial JSONL message 3. sessionId
            String effectiveId = sessionId != null
                    ? sessionId
                    : jsonlFile.getFileName().toString().replace(".jsonl", "");
            String title = historyTitles.getOrDefault(effectiveId, firstSubstantialMessage);

            return new DialogSessionInfo(
                    effectiveId,
                    jsonlFile.toAbsolutePath().toString(),
                    projectPath,
                    firstTs,
                    lastTs,
                    turnCount,
                    toolCallCount,
                    dominantTools,
                    truncate(title, 200),
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

    /**
     * Detects system-injected messages that are unusable as titles.
     */
    private boolean isSystemWrapper(String text) {
        return text.startsWith("<local-command-caveat>")
                || text.startsWith("<system-reminder>")
                || text.startsWith("[Request interrupted")
                || text.startsWith("<command-name>");
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

    static String encodeProjectPath(String absolutePath) {
        return absolutePath.replace("/", "-");
    }

    static String decodeProjectPath(String encoded) {
        if (!encoded.startsWith("-")) return encoded;
        return encoded.replaceFirst("-", "/").replace("-", "/");
    }
}
