package com.automation.service.memory;

import com.automation.entity.StepResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based preprocessing pipeline that compresses step outputs before the AI call: large
 * code blocks and data dumps become placeholders, meta comments are dropped, repeated
 * lines and agentic iterations are condensed. How much this shortens depends on the runs.
 */
@Component
public class ConversationPreprocessor {

    private static final int CODE_BLOCK_LINE_THRESHOLD = 30;
    private static final int DATA_DUMP_LINE_THRESHOLD = 20;

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "```(\\w*)\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern META_PATTERN = Pattern.compile(
            "(?m)^(Let me|I'll now|I see that|I notice that|Now I will|I'm going to|Looking at|I can see).*$");
    private static final Pattern REPEATED_EMPTY_LINES = Pattern.compile("\\n{3,}");

    /**
     * Preprocesses a list of step results into a compact text summary.
     */
    public String preprocess(List<StepResult> steps) {
        StringBuilder sb = new StringBuilder();

        for (StepResult step : steps) {
            sb.append("=== Step ").append(step.getStepIndex()).append(" ===\n");

            if (step.getInput() != null && !step.getInput().isBlank()) {
                sb.append("Input: ").append(step.getInput()).append("\n");
            }

            if (step.getOutput() != null && !step.getOutput().isBlank()) {
                String processed = processOutput(step.getOutput());
                sb.append("Output:\n").append(processed).append("\n");
            }

            sb.append("Status: ").append(step.getStatus()).append("\n");

            // Extract iteration data from agentic blocks
            Map<String, Object> metadata = step.getMetadata();
            if (metadata != null && metadata.containsKey("iterations")) {
                String iterationSummary = compressIterations(metadata);
                if (!iterationSummary.isEmpty()) {
                    sb.append(iterationSummary);
                }
            }

            sb.append("\n");
        }

        return sb.toString().trim();
    }

    /**
     * Applies all compression rules to a single output string.
     */
    public String processOutput(String output) {
        String result = output;
        result = compressCodeBlocks(result);
        result = compressDataDumps(result);
        result = stripMetaCommentary(result);
        result = collapseRepeatedFailures(result);
        result = REPEATED_EMPTY_LINES.matcher(result).replaceAll("\n\n");
        return result.trim();
    }

    /**
     * Replaces code blocks larger than threshold with a placeholder.
     */
    String compressCodeBlocks(String input) {
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(input);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String language = matcher.group(1);
            String code = matcher.group(2);
            int lineCount = code.split("\n").length;

            if (lineCount > CODE_BLOCK_LINE_THRESHOLD) {
                String lang = language.isEmpty() ? "" : ", " + language;
                matcher.appendReplacement(sb,
                        Matcher.quoteReplacement("[Code block: " + lineCount + " lines" + lang + "]"));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Detects and compresses large JSON/XML data dumps.
     */
    String compressDataDumps(String input) {
        String[] lines = input.split("\n");
        StringBuilder result = new StringBuilder();
        int dumpStart = -1;
        int braceDepth = 0;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            if (dumpStart == -1 && (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("<"))) {
                dumpStart = i;
                braceDepth = countBraces(trimmed);
            } else if (dumpStart >= 0) {
                braceDepth += countBraces(trimmed);
                if (braceDepth <= 0 || i == lines.length - 1) {
                    int dumpLength = i - dumpStart + 1;
                    if (dumpLength > DATA_DUMP_LINE_THRESHOLD) {
                        result.append("[Data dump: ").append(dumpLength).append(" lines]\n");
                    } else {
                        // Short enough to keep
                        for (int j = dumpStart; j <= i; j++) {
                            result.append(lines[j]).append("\n");
                        }
                    }
                    dumpStart = -1;
                    braceDepth = 0;
                    continue;
                }
                continue;
            }

            if (dumpStart == -1) {
                result.append(lines[i]).append("\n");
            }
        }

        return result.toString();
    }

    private int countBraces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == '{' || c == '[') count++;
            else if (c == '}' || c == ']') count--;
        }
        return count;
    }

    /**
     * Removes meta-commentary lines (e.g., "Let me...", "I'll now...").
     */
    String stripMetaCommentary(String input) {
        return META_PATTERN.matcher(input).replaceAll("").trim();
    }

    /**
     * Collapses repeated identical consecutive failure lines.
     */
    String collapseRepeatedFailures(String input) {
        String[] lines = input.split("\n");
        if (lines.length <= 1) return input;

        StringBuilder result = new StringBuilder();
        String lastLine = null;
        int repeatCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.equals(lastLine) && !trimmed.isEmpty()) {
                repeatCount++;
            } else {
                if (repeatCount > 1) {
                    result.append("[Repeated ").append(repeatCount).append("x: ")
                          .append(lastLine).append("]\n");
                } else if (lastLine != null) {
                    result.append(lastLine).append("\n");
                }
                lastLine = trimmed;
                repeatCount = 1;
            }
        }

        // Flush last
        if (repeatCount > 1) {
            result.append("[Repeated ").append(repeatCount).append("x: ")
                  .append(lastLine).append("]\n");
        } else if (lastLine != null) {
            result.append(lastLine).append("\n");
        }

        return result.toString();
    }

    /**
     * Compresses agentic iteration metadata into compact headers.
     */
    @SuppressWarnings("unchecked")
    private String compressIterations(Map<String, Object> metadata) {
        Object iterObj = metadata.get("iterations");
        if (!(iterObj instanceof List<?> iterations)) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < iterations.size(); i++) {
            if (!(iterations.get(i) instanceof Map<?, ?> iter)) continue;

            String reasoning = getStr(iter, "reasoning");
            String action = getStr(iter, "action");
            String output = getStr(iter, "output");
            String phase = getStr(iter, "phase");

            sb.append("[Iter ").append(i + 1).append("/").append(iterations.size());
            if (phase != null && !phase.isEmpty()) {
                sb.append(" | ").append(phase);
            }
            sb.append("]\n");

            if (reasoning != null && !reasoning.isBlank()) {
                String compressed = stripMetaCommentary(reasoning);
                if (!compressed.isBlank()) {
                    sb.append("Reasoning: ").append(truncate(compressed, 500)).append("\n");
                }
            }

            if (action != null && !action.isBlank()) {
                sb.append("Action: ").append(truncate(action, 200)).append("\n");
            }

            if (output != null && !output.isBlank()) {
                String processedOutput = processOutput(output);
                sb.append("Result: ").append(truncate(processedOutput, 500)).append("\n");
            }
        }

        return sb.toString();
    }

    private String getStr(Map<?, ?> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
