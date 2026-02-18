package com.automation.service.ai;

import com.automation.config.HostClaudeConfig;
import com.automation.service.cli.ClaudeJsonResponse;
import com.automation.service.cli.ClaudeOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class HostClaudeService {

    public record Turn(String role, String content) {}

    public record ReasoningResult(String text, ClaudeJsonResponse parsed) {}

    private final HostClaudeConfig config;

    /**
     * Sends a single reasoning prompt to the local Claude CLI on the host.
     * Uses one-off mode with stream-json for structured metadata capture.
     */
    public String reason(String prompt) throws IOException {
        return reasonStructured(prompt).text();
    }

    /**
     * Sends a reasoning prompt and returns both text and structured metadata
     * (tokens, cost, model, tool usage).
     */
    public ReasoningResult reasonStructured(String prompt) throws IOException {
        List<String> cmd = new ArrayList<>(List.of(
                "claude", "-p", prompt,
                "--output-format", "stream-json"
        ));

        if (config.getMaxTokens() != null) {
            cmd.add("--max-tokens");
            cmd.add(config.getMaxTokens().toString());
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        log.debug("Host Claude reasoning (prompt length: {} chars)", prompt.length());
        Process proc = pb.start();

        try {
            String rawOutput = new String(proc.getInputStream().readAllBytes()).trim();
            boolean finished = proc.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("Host Claude timed out after " + config.getTimeoutSeconds() + " seconds");
            }

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                log.warn("Host Claude returned exit code {}: {}", exitCode, truncate(rawOutput, 500));
            }

            ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(rawOutput);
            String text = parsed.result() != null ? parsed.result() : rawOutput;
            return new ReasoningResult(text, parsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Host Claude interrupted");
        }
    }

    /**
     * Builds a full conversation prompt from system instructions and turn history.
     * This concatenates everything into a single prompt for claude -p one-off mode.
     */
    public String buildConversationPrompt(String systemPrompt, List<Turn> history) {
        StringBuilder sb = new StringBuilder();

        sb.append("=== SYSTEM INSTRUCTIONS ===\n");
        sb.append(systemPrompt);
        sb.append("\n\n");

        if (!history.isEmpty()) {
            sb.append("=== CONVERSATION HISTORY ===\n");
            for (Turn turn : history) {
                String label = "assistant".equals(turn.role()) ? "ASSISTANT" : "USER";
                sb.append("[").append(label).append("]\n");
                sb.append(turn.content());
                sb.append("\n\n");
            }
        }

        sb.append("=== YOUR RESPONSE ===\n");
        sb.append("Respond following the format specified in the system instructions.");

        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
