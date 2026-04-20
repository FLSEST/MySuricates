package com.automation.service.cli;

import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses NDJSON (stream-json) output from Claude CLI.
 * Each line is a JSON object; assistant lines contain tool_use events,
 * and the final "result" line contains tokens/cost/response.
 */
@Slf4j
public class ClaudeOutputParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClaudeOutputParser() {}

    /**
     * Parses stream-json (NDJSON) output from Claude CLI.
     * Extracts tool-use events from assistant messages and the final result line.
     */
    public static ClaudeJsonResponse parseStreamJson(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) {
            return fallback("");
        }

        String[] lines = rawOutput.split("\n");
        List<ClaudeJsonResponse.ToolUseEvent> toolUses = new ArrayList<>();
        ClaudeJsonResponse resultLine = null;
        String assistantModel = null;

        for (String line : lines) {
            if (line.isBlank()) continue;

            try {
                JsonNode node = MAPPER.readTree(line);
                String type = node.path("type").asText("");

                if ("result".equals(type)) {
                    resultLine = MAPPER.treeToValue(node, ClaudeJsonResponse.class);
                } else if ("assistant".equals(type)) {
                    String messageModel = node.path("message").path("model").asText("");
                    if (!messageModel.isBlank()) {
                        assistantModel = messageModel;
                    }
                    JsonNode content = node.path("message").path("content");
                    if (content.isArray()) {
                        for (JsonNode item : content) {
                            if ("tool_use".equals(item.path("type").asText(""))) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> input = MAPPER.convertValue(
                                        item.path("input"), Map.class);
                                toolUses.add(new ClaudeJsonResponse.ToolUseEvent(
                                        item.path("name").asText("unknown"),
                                        input
                                ));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // For the last non-blank line, attempt JSON repair before skipping
                if (isLastNonBlankLine(lines, line)) {
                    String repaired = JsonRepairUtil.repairTruncatedJson(line);
                    if (repaired != null) {
                        try {
                            JsonNode node = MAPPER.readTree(repaired);
                            String type = node.path("type").asText("");
                            if ("result".equals(type)) {
                                resultLine = MAPPER.treeToValue(node, ClaudeJsonResponse.class);
                                log.debug("Repaired truncated result line successfully");
                                continue;
                            }
                        } catch (Exception re) {
                            log.trace("Repair attempt also failed for line: {}", truncate(line, 200));
                        }
                    }
                }
                log.trace("Skipping unparseable NDJSON line: {}", truncate(line, 200));
            }
        }

        if (resultLine != null) {
            return new ClaudeJsonResponse(
                    resultLine.result(),
                    resultLine.sessionId(),
                    resultLine.totalCostUsd(),
                    resultLine.durationMs(),
                    resultLine.durationApiMs(),
                    resultLine.numTurns(),
                    resultLine.usage(),
                    resultLine.modelUsage(),
                    assistantModel != null ? assistantModel : resultLine.assistantModel(),
                    toolUses.isEmpty() ? resultLine.toolUses() : toolUses
            );
        }

        return fallback(rawOutput);
    }

    /**
     * Extracts just the text result from raw stream-json output.
     * Falls back to returning the raw output if parsing fails.
     */
    public static String extractText(String rawOutput) {
        if (rawOutput == null || rawOutput.isBlank()) return "";

        try {
            ClaudeJsonResponse parsed = parseStreamJson(rawOutput);
            return parsed.result() != null ? parsed.result() : rawOutput;
        } catch (Exception e) {
            log.debug("Failed to extract text from stream-json, returning raw output");
            return rawOutput;
        }
    }

    private static ClaudeJsonResponse fallback(String rawText) {
        return new ClaudeJsonResponse(
                rawText, null, null, null, null, null, null, null, null, null
        );
    }

    private static boolean isLastNonBlankLine(String[] lines, String currentLine) {
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i].equals(currentLine);
            }
        }
        return false;
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
