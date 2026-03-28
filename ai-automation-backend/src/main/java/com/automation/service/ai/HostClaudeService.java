package com.automation.service.ai;

import com.automation.config.CliPermissionsConfig;
import com.automation.config.HostClaudeConfig;
import com.automation.service.cli.ClaudeJsonResponse;
import com.automation.service.cli.ClaudeOutputParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import com.automation.concurrency.DynamicSemaphore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class HostClaudeService {

    public record Turn(String role, String content) {}

    public record ReasoningResult(String text, ClaudeJsonResponse parsed) {}

    public record ActionResult(String text, int exitCode, ClaudeJsonResponse parsed) {}

    private final HostClaudeConfig config;
    private final CliPermissionsConfig permissionsConfig;
    private final DynamicSemaphore callSemaphore;
    private final AtomicLong lastCallTimestamp = new AtomicLong(0);

    public HostClaudeService(HostClaudeConfig config, CliPermissionsConfig permissionsConfig) {
        this.config = config;
        this.permissionsConfig = permissionsConfig;
        this.callSemaphore = new DynamicSemaphore(config.getMaxConcurrentCalls());
        log.info("Claude CLI throttle: maxConcurrent={}, interCallDelay={}ms",
                config.getMaxConcurrentCalls(), config.getInterCallDelayMs());
    }

    public void updateMaxConcurrentCalls(int newMax) {
        callSemaphore.setMaxPermits(newMax);
        log.info("Claude CLI max concurrent calls updated to {}", newMax);
    }

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
        return reasonStructured(prompt, systemPrompt, workingDirectory, maxTurns, null);
    }

    public ReasoningResult reasonStructured(String prompt, String systemPrompt,
                                             String workingDirectory, Integer maxTurns,
                                             Integer timeoutSeconds) throws IOException {
        List<String> cmd = buildReasonCommand(prompt, systemPrompt, maxTurns);
        var result = executeProcess(cmd, prompt, workingDirectory, timeoutSeconds, "Host Claude reasoning");
        return new ReasoningResult(result.text, result.parsed);
    }

    /**
     * Executes a Claude CLI action on the host with the project as working directory.
     * Uses --append-system-prompt for block instructions, --model for model selection,
     * and --dangerously-skip-permissions with --allowedTools from CliPermissionsConfig.
     * Removes CLAUDE_CODE env var to prevent nested-session detection.
     */
    public ActionResult executeAction(String prompt, String model,
                                       String systemPrompt, String workingDirectory) throws IOException {
        return executeAction(prompt, model, systemPrompt, workingDirectory, null);
    }

    public ActionResult executeAction(String prompt, String model,
                                       String systemPrompt, String workingDirectory,
                                       Integer timeoutSeconds) throws IOException {
        List<String> cmd = buildActionCommand(prompt, model, systemPrompt);
        var result = executeProcess(cmd, prompt, workingDirectory, timeoutSeconds, "Host Claude action");
        return new ActionResult(result.text, result.exitCode, result.parsed);
    }

    /**
     * Shared process execution logic — eliminates duplication between reasonStructured and executeAction.
     */
    private record ProcessResult(String text, int exitCode, ClaudeJsonResponse parsed) {}

    private ProcessResult executeProcess(List<String> cmd, String prompt, String workingDirectory,
                                          Integer timeoutSeconds, String label) throws IOException {
        acquireThrottle();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().remove("CLAUDE_CODE");
            pb.environment().remove("CLAUDECODE");
            if (workingDirectory != null && !workingDirectory.isBlank()) {
                pb.directory(new File(workingDirectory));
            }

            log.debug("{} (prompt length: {} chars, workDir: {})", label, prompt.length(), workingDirectory);
            Process proc = pb.start();
            writePromptToStdin(proc, prompt);

            int effectiveTimeout = timeoutSeconds != null ? timeoutSeconds : config.getTimeoutSeconds();
            String rawOutput = drainProcess(proc, effectiveTimeout, label);

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                log.warn("{} returned exit code {}: {}", label, exitCode, truncate(rawOutput, 500));
            }

            ClaudeJsonResponse parsed = ClaudeOutputParser.parseStreamJson(rawOutput);
            String text = parsed.result() != null ? parsed.result() : rawOutput;
            return new ProcessResult(text, exitCode, parsed);
        } finally {
            callSemaphore.release();
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
     * Acquires a throttle permit: limits concurrency via semaphore and enforces
     * a minimum inter-call delay to prevent API rate limiting.
     */
    private void acquireThrottle() throws IOException {
        try {
            callSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Claude CLI throttle permit", e);
        }
        long delayMs = config.getInterCallDelayMs();
        if (delayMs > 0) {
            long remaining = delayMs - (System.currentTimeMillis() - lastCallTimestamp.get());
            if (remaining > 0) {
                log.debug("Claude CLI throttle: waiting {}ms before next call", remaining);
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    callSemaphore.release();
                    throw new IOException("Interrupted during Claude CLI throttle delay", e);
                }
            }
        }
        lastCallTimestamp.set(System.currentTimeMillis());
    }

    /**
     * Drains process stdout, waits for completion with timeout, and ensures all streams
     * are closed — even on timeout or interruption.
     */
    private String drainProcess(Process proc, int timeoutSeconds, String label) throws IOException {
        InputStream stdout = proc.getInputStream();
        CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
            try { return stdout.readAllBytes(); }
            catch (IOException e) { return new byte[0]; }
        });

        try {
            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                proc.waitFor(5, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                closeQuietly(stdout);
                throw new IOException(label + " timed out after " + timeoutSeconds + " seconds");
            }

            return new String(outputFuture.get(5, TimeUnit.SECONDS)).trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            outputFuture.cancel(true);
            closeQuietly(stdout);
            throw new IOException(label + " interrupted");
        } catch (ExecutionException | TimeoutException e) {
            proc.destroyForcibly();
            outputFuture.cancel(true);
            closeQuietly(stdout);
            throw new IOException("Failed to read " + label + " output", e);
        }
    }

    private void closeQuietly(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) { /* already destroyed */ }
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
