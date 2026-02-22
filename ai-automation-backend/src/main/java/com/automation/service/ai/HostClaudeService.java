package com.automation.service.ai;

import com.automation.config.CliPermissionsConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.service.cli.ClaudeJsonResponse;
import com.automation.service.cli.ClaudeOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
@RequiredArgsConstructor
@Slf4j
public class HostClaudeService {

    public record Turn(String role, String content) {}

    public record ReasoningResult(String text, ClaudeJsonResponse parsed) {}

    public record ActionResult(String text, int exitCode, ClaudeJsonResponse parsed) {}

    private final HostClaudeConfig config;
    private final CliPermissionsConfig permissionsConfig;

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
        return reasonStructured(prompt, null);
    }

    public ReasoningResult reasonStructured(String prompt, String systemPrompt) throws IOException {
        return reasonStructured(prompt, systemPrompt, null, null);
    }

    public ReasoningResult reasonStructured(String prompt, String systemPrompt,
                                             String workingDirectory, Integer maxTurns) throws IOException {
        List<String> cmd = buildReasonCommand(prompt, systemPrompt, maxTurns);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // Prevent nested-session detection (same as executeAction)
        pb.environment().remove("CLAUDECODE");
        pb.environment().remove("CLAUDE_CODE");
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            pb.directory(new File(workingDirectory));
        }

        log.debug("Host Claude reasoning (prompt length: {} chars, workDir: {})", prompt.length(), workingDirectory);
        Process proc = pb.start();
        // Write prompt via stdin to avoid OS command-line length limits (E2BIG)
        writePromptToStdin(proc, prompt);

        try {
            CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
                try { return proc.getInputStream().readAllBytes(); }
                catch (IOException e) { return new byte[0]; }
            });

            boolean finished = proc.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("Host Claude timed out after " + config.getTimeoutSeconds() + " seconds");
            }

            String rawOutput = new String(outputFuture.get(5, TimeUnit.SECONDS)).trim();

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
        } catch (ExecutionException | TimeoutException e) {
            proc.destroyForcibly();
            throw new IOException("Failed to read Host Claude output", e);
        }
    }

    /**
     * Executes a Claude CLI action on the host with the project as working directory.
     * Uses --append-system-prompt for block instructions, --model for model selection,
     * and --dangerously-skip-permissions with --allowedTools from CliPermissionsConfig.
     * Removes CLAUDE_CODE env var to prevent nested-session detection.
     */
    public ActionResult executeAction(String prompt, String model,
                                       String systemPrompt, String workingDirectory) throws IOException {
        List<String> cmd = buildActionCommand(prompt, model, systemPrompt);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            pb.directory(new File(workingDirectory));
        }
        // Remove nested-session detection env vars
        pb.environment().remove("CLAUDE_CODE");
        pb.environment().remove("CLAUDECODE");

        log.debug("Host Claude action (prompt length: {} chars, workDir: {})", prompt.length(), workingDirectory);
        Process proc = pb.start();
        // Write prompt via stdin to avoid OS command-line length limits (E2BIG)
        writePromptToStdin(proc, prompt);

        try {
            CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
                try { return proc.getInputStream().readAllBytes(); }
                catch (IOException e) { return new byte[0]; }
            });

            boolean finished = proc.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new IOException("Host Claude action timed out after " + config.getTimeoutSeconds() + " seconds");
            }

            String rawOutput = new String(outputFuture.get(5, TimeUnit.SECONDS)).trim();

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                log.warn("Host Claude action returned exit code {}: {}", exitCode, truncate(rawOutput, 500));
            }

            ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(rawOutput);
            String text = parsed.result() != null ? parsed.result() : rawOutput;
            return new ActionResult(text, exitCode, parsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("Host Claude action interrupted");
        } catch (ExecutionException | TimeoutException e) {
            proc.destroyForcibly();
            throw new IOException("Failed to read Host Claude action output", e);
        }
    }

    /**
     * Builds the CLI command list for a reasoning call.
     * Package-private for testability.
     */
    List<String> buildReasonCommand(String prompt, String systemPrompt) {
        return buildReasonCommand(prompt, systemPrompt, null);
    }

    List<String> buildReasonCommand(String prompt, String systemPrompt, Integer maxTurns) {
        List<String> cmd = new ArrayList<>(List.of(
                "claude", "-p",
                "--output-format", "stream-json", "--verbose"
        ));

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--system-prompt");
            cmd.add(systemPrompt);
        }

        // Skip permissions to prevent hanging on interactive tool approval prompts
        if (permissionsConfig.isSkipPermissions()) {
            cmd.add("--dangerously-skip-permissions");
        }

        cmd.add("--max-turns");
        cmd.add(String.valueOf(maxTurns != null ? maxTurns : 3));

        if (config.getMaxTokens() != null) {
            cmd.add("--max-tokens");
            cmd.add(config.getMaxTokens().toString());
        }

        return cmd;
    }

    /**
     * Builds the CLI command list for an action call.
     * Package-private for testability.
     */
    List<String> buildActionCommand(String prompt, String model, String systemPrompt) {
        List<String> cmd = new ArrayList<>(List.of(
                "claude", "-p",
                "--output-format", "stream-json", "--verbose"
        ));

        if (model != null && !model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--append-system-prompt");
            cmd.add(systemPrompt);
        }
        if (permissionsConfig.isSkipPermissions()) {
            cmd.add("--dangerously-skip-permissions");
        }
        for (String tool : permissionsConfig.getAllowedTools()) {
            cmd.add("--allowedTools");
            cmd.add(tool);
        }
        if (config.getMaxTokens() != null) {
            cmd.add("--max-tokens");
            cmd.add(config.getMaxTokens().toString());
        }

        return cmd;
    }

    /**
     * Builds a conversation prompt from turn history (without system prompt).
     * The system prompt should be passed via --system-prompt flag for proper separation.
     */
    public String buildConversationPrompt(String systemPrompt, List<Turn> history) {
        StringBuilder sb = new StringBuilder();

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

    /**
     * Writes the prompt to the process's stdin and closes it.
     * This avoids OS command-line argument length limits (E2BIG / errno 7).
     */
    private void writePromptToStdin(Process proc, String prompt) throws IOException {
        try (OutputStream stdin = proc.getOutputStream()) {
            stdin.write(prompt.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
