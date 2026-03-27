package com.automation.service.dialog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Parst Claude Code .jsonl-Dateien in strukturierte ParsedSession-Objekte.
 * Kein AI-Aufruf — reine I/O-Verarbeitung.
 */
@Component
@Slf4j
public class ClaudeJsonlParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ParsedSession(
            String sessionId,
            String projectPath,
            Instant startedAt,
            Instant endedAt,
            List<DialogTurn> turns
    ) {}

    public record DialogTurn(
            String uuid,
            String parentUuid,
            String role,   // "user" | "assistant"
            Instant timestamp,
            List<DialogMessage> messages
    ) {}

    public record DialogMessage(
            String type,          // "text" | "tool_use" | "tool_result"
            String text,          // for type=text
            String toolName,      // for type=tool_use
            String toolInput,     // for type=tool_use: serialized JSON or key excerpt
            String toolUseId,     // for linking tool_use to tool_result
            String resultText     // for type=tool_result
    ) {}

    /**
     * Liest eine .jsonl-Datei und gibt eine strukturierte ParsedSession zurück.
     */
    public ParsedSession parse(Path jsonlFile) throws IOException {
        List<DialogTurn> turns = new ArrayList<>();
        String sessionId = null;
        String projectPath = null;
        Instant startedAt = null;
        Instant endedAt = null;

        List<String> lines = Files.readAllLines(jsonlFile);
        for (String line : lines) {
            if (line.isBlank()) continue;

            Map<String, Object> entry;
            try {
                entry = MAPPER.readValue(line, new TypeReference<>() {});
            } catch (Exception e) {
                log.debug("Skipping unparseable JSONL line: {}", e.getMessage());
                continue;
            }

            String type = (String) entry.get("type");
            if (type == null || "file-history-snapshot".equals(type) || "progress".equals(type)) {
                continue;
            }

            // Extract session metadata from first user entry
            if (sessionId == null) {
                sessionId = (String) entry.get("sessionId");
                projectPath = (String) entry.get("cwd");
            }

            Instant ts = parseTimestamp(entry.get("timestamp"));
            if (ts != null) {
                if (startedAt == null || ts.isBefore(startedAt)) startedAt = ts;
                if (endedAt == null || ts.isAfter(endedAt)) endedAt = ts;
            }

            if (!"user".equals(type) && !"assistant".equals(type)) continue;

            Object messageObj = entry.get("message");
            if (!(messageObj instanceof Map<?, ?> msgMap)) continue;

            String role = (String) msgMap.get("role");
            Object contentObj = msgMap.get("content");

            List<DialogMessage> messages = extractMessages(contentObj);
            if (messages.isEmpty()) continue;

            turns.add(new DialogTurn(
                    (String) entry.get("uuid"),
                    (String) entry.get("parentUuid"),
                    role != null ? role : type,
                    ts,
                    messages
            ));
        }

        return new ParsedSession(
                sessionId != null ? sessionId : jsonlFile.getFileName().toString().replace(".jsonl", ""),
                projectPath != null ? projectPath : "",
                startedAt,
                endedAt,
                turns
        );
    }

    @SuppressWarnings("unchecked")
    private List<DialogMessage> extractMessages(Object contentObj) {
        List<DialogMessage> messages = new ArrayList<>();

        if (contentObj instanceof String text) {
            if (!text.isBlank()) {
                messages.add(new DialogMessage("text", text.strip(), null, null, null, null));
            }
            return messages;
        }

        if (!(contentObj instanceof List<?> contentList)) return messages;

        for (Object blockObj : contentList) {
            if (!(blockObj instanceof Map<?, ?> block)) continue;

            String blockType = (String) block.get("type");
            if (blockType == null) continue;

            switch (blockType) {
                case "text" -> {
                    String text = (String) block.get("text");
                    if (text != null && !text.isBlank()) {
                        messages.add(new DialogMessage("text", text.strip(), null, null, null, null));
                    }
                }
                case "tool_use" -> {
                    String toolName = (String) block.get("name");
                    String toolId = (String) block.get("id");
                    Object inputObj = block.get("input");
                    String inputStr = extractToolInput(toolName, inputObj);
                    messages.add(new DialogMessage("tool_use", null, toolName, inputStr, toolId, null));
                }
                case "tool_result" -> {
                    String toolUseId = (String) block.get("tool_use_id");
                    String resultText = extractToolResult(block.get("content"));
                    if (resultText != null) {
                        messages.add(new DialogMessage("tool_result", null, null, null, toolUseId, resultText));
                    }
                }
            }
        }

        return messages;
    }

    @SuppressWarnings("unchecked")
    private String extractToolInput(String toolName, Object inputObj) {
        if (inputObj == null) return "";

        try {
            if (inputObj instanceof Map<?, ?> inputMap) {
                // For Bash: extract command field directly
                if ("Bash".equals(toolName) && inputMap.containsKey("command")) {
                    return (String) inputMap.get("command");
                }
                // For Read/Write/Edit: extract file_path
                if (inputMap.containsKey("file_path")) {
                    return (String) inputMap.get("file_path");
                }
                // For others: serialize to compact JSON (truncated)
                String json = MAPPER.writeValueAsString(inputMap);
                return json.length() > 500 ? json.substring(0, 500) + "..." : json;
            }
            return inputObj.toString();
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private String extractToolResult(Object contentObj) {
        if (contentObj == null) return null;
        if (contentObj instanceof String s) return s.isBlank() ? null : s;
        if (contentObj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m && "text".equals(m.get("type"))) {
                    Object text = m.get("text");
                    if (text != null) sb.append(text);
                }
            }
            String result = sb.toString();
            return result.isBlank() ? null : result;
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
}
